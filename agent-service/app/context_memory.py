"""Shared, bounded context selection for Router, Master and domain Agents."""
import json
import re


def recent_context(messages: list[dict], *, current_message_id=None, char_budget: int = 8000) -> list[dict]:
    # UTF-8 byte length is a conservative proxy for token cost, including CJK text.
    selected = []
    remaining = char_budget
    for message in reversed(messages[-20:]):
        if current_message_id is not None and message.get("message_id", message.get("messageId")) == current_message_id:
            continue
        content = str(message.get("content") or "")
        cost = len(content.encode("utf-8")) + 64
        if cost > remaining:
            break  # keep complete messages, without cutting a sentence or JSON value
        remaining -= cost
        selected.append(message)
    return list(reversed(selected))


def relevant_memory(summary: str, goal: str, *, domain: str | None = None, byte_budget: int = 9000) -> str:
    try:
        memory = json.loads(summary)
    except (TypeError, ValueError):
        return (summary or "暂无")[:3000]  # legacy summary during rollout
    if not isinstance(memory, dict) or memory.get("schemaVersion") != 1:
        return "暂无"
    terms = set(re.findall(r"[0-9]+|[a-zA-Z]+|[\u4e00-\u9fff]{2}", goal.lower()))
    def score(task):
        text = json.dumps(task, ensure_ascii=False).lower()
        return (sum(term in text for term in terms), task.get("status") == "ACTIVE",
                task.get("priority") == "P0", task.get("updatedAt", ""))
    candidates = [t for t in memory.get("tasks", [])
                  if domain is None or not t.get("domains") or domain in t["domains"]]
    selected = []
    for task in sorted(candidates, key=score, reverse=True):
        # Provenance stays in MySQL; a compact source list is sufficient in the response context.
        task = {k: v for k, v in task.items() if k != "fieldEvidence"}
        candidate = {"schemaVersion": 1, "historicalOnly": True, "tasks": selected + [task]}
        if len(json.dumps(candidate, ensure_ascii=False).encode("utf-8")) <= byte_budget:
            selected.append(task)
        if len(selected) == (3 if domain else 5):
            break
    return json.dumps({"schemaVersion": 1, "historicalOnly": True, "tasks": selected}, ensure_ascii=False)


_PROFILE_FIELDS = (
    "dietaryPreference", "budgetPreference", "areaPreference", "cuisinePreference",
    "tastePreference", "servicePreference", "environmentPreference", "communicationPreference",
)
_PROFILE_DOMAINS = {
    "FAQ": ("dietaryPreference", "tastePreference", "servicePreference", "budgetPreference"),
    "AFTER_SALES": ("communicationPreference",),
    "COMPLAINT": ("communicationPreference",),
    "REPLY": ("communicationPreference",),
}


def select_profile(profile: dict | None, *, domain: str | None = None, byte_budget: int = 6000) -> dict:
    # Java filters expired/tombstoned fields before injection. No profile changes routing permissions.
    selected = {}
    if not isinstance(profile, dict) or profile.get("schemaVersion") != 1:
        return {"schemaVersion": 1, "fields": selected}
    fields = profile.get("fields", {})
    if not isinstance(fields, dict):
        return {"schemaVersion": 1, "fields": selected}
    for name in _PROFILE_DOMAINS.get(domain, _PROFILE_FIELDS):
        entry = fields.get(name)
        if not isinstance(entry, dict) or entry.get("status") != "CONFIRMED":
            continue
        values = entry.get("value")
        if not isinstance(values, list) or not values or len(values) > 6:
            continue
        if any(not isinstance(value, str) or not value.strip() or len(value) > 120 for value in values):
            continue
        value = {"value": values, "status": "CONFIRMED", "observedAt": entry.get("observedAt")}
        if len(json.dumps({**selected, name: value}, ensure_ascii=False).encode("utf-8")) <= byte_budget:
            selected[name] = value
    return {"schemaVersion": 1, "fields": selected}
