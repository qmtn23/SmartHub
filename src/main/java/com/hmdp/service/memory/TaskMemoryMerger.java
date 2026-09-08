package com.hmdp.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.config.TaskMemoryProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/** Deterministic field patches. The model cannot set identity, provenance, TTL or version. */
@Component
public class TaskMemoryMerger {
    private static final Set<String> FIELDS = Set.of("intent", "domains", "entities", "constraints",
            "facts", "openQuestions", "status", "priority");
    private final ObjectMapper json;
    private final TaskMemoryProperties settings;

    public TaskMemoryMerger(ObjectMapper json, TaskMemoryProperties settings) {
        this.json = json;
        this.settings = settings;
    }

    public ObjectNode empty() {
        ObjectNode value = json.createObjectNode();
        value.put("schemaVersion", 1);
        value.putArray("tasks");
        return value;
    }

    public ObjectNode merge(JsonNode previous, JsonNode diff, List<Map<String, Object>> messages, LocalDateTime now) {
        if (diff == null || !diff.path("operations").isArray() || diff.path("operations").size() > 16)
            throw new IllegalArgumentException("INVALID_MEMORY_DIFF");
        Map<Long, Map<String, Object>> sources = new HashMap<>();
        messages.forEach(m -> sources.put(((Number) m.get("messageId")).longValue(), m));
        ObjectNode result = previous.deepCopy();
        Map<String, ObjectNode> tasks = new LinkedHashMap<>();
        result.withArray("tasks").forEach(t -> tasks.put(t.path("taskId").asText(), (ObjectNode) t));
        for (JsonNode op : diff.path("operations")) {
            String id = op.path("taskId").asText("");
            JsonNode patch = op.path("patch");
            JsonNode ids = op.path("sourceMessageIds");
            if (!patch.isObject() || patch.isEmpty() || !ids.isArray() || ids.isEmpty() || ids.size() > 50)
                throw new IllegalArgumentException("INVALID_MEMORY_PATCH");
            ArrayNode evidence = json.createArrayNode();
            LocalDateTime observed = LocalDateTime.MIN;
            for (JsonNode source : ids) {
                if (!source.isIntegralNumber() || !sources.containsKey(source.asLong()))
                    throw new IllegalArgumentException("UNKNOWN_MEMORY_SOURCE");
                Map<String, Object> message = sources.get(source.asLong());
                LocalDateTime time = LocalDateTime.parse(message.get("createTime").toString());
                if (time.isAfter(observed)) observed = time;
                if (evidence.size() < 12) {
                    ObjectNode ref = evidence.addObject();
                    ref.put("messageId", source.asLong());
                    ref.put("senderType", message.get("senderType").toString());
                    ref.put("observedAt", time.toString());
                }
            }
            ObjectNode task;
            if (id.isEmpty()) {
                if (!patch.hasNonNull("intent")) throw new IllegalArgumentException("MISSING_TASK_INTENT");
                task = json.createObjectNode();
                id = UUID.randomUUID().toString();
                task.put("taskId", id);
                task.put("status", "ACTIVE");
                task.put("priority", "P1");
                task.put("updatedAt", observed.toString());
                tasks.put(id, task);
            } else {
                task = tasks.get(id);
                if (task == null) throw new IllegalArgumentException("UNKNOWN_MEMORY_TASK");
            }
            ObjectNode fieldEvidence = task.with("fieldEvidence");
            Iterator<Map.Entry<String, JsonNode>> fields = patch.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                String name = field.getKey();
                JsonNode value = field.getValue();
                if (!FIELDS.contains(name)) throw new IllegalArgumentException("UNKNOWN_MEMORY_FIELD");
                if (value.isNull()) continue; // null means omitted; [] explicitly clears an array
                validate(name, value);
                String oldTime = fieldEvidence.path(name).path("observedAt").asText("");
                if (!oldTime.isEmpty() && LocalDateTime.parse(oldTime).isAfter(observed)) continue;
                task.set(name, value.deepCopy());
                ObjectNode provenance = json.createObjectNode();
                provenance.put("observedAt", observed.toString());
                provenance.set("sources", evidence.deepCopy());
                fieldEvidence.set(name, provenance);
            }
            if (observed.isAfter(LocalDateTime.parse(task.path("updatedAt").asText())))
                task.put("updatedAt", observed.toString());
        }
        result.set("tasks", json.valueToTree(tasks.values()));
        return prune(result, now);
    }

    private void validate(String name, JsonNode value) {
        if (Set.of("intent", "status", "priority").contains(name)) {
            if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 300)
                throw new IllegalArgumentException("INVALID_MEMORY_FIELD");
            if (name.equals("status") && !Set.of("ACTIVE", "RESOLVED", "ABANDONED").contains(value.asText()))
                throw new IllegalArgumentException("INVALID_TASK_STATUS");
            if (name.equals("priority") && !Set.of("P0", "P1", "P2").contains(value.asText()))
                throw new IllegalArgumentException("INVALID_TASK_PRIORITY");
            return;
        }
        int limit = name.equals("domains") ? 4 : name.equals("entities") ? 10 : 8;
        if (!value.isArray() || value.size() > limit) throw new IllegalArgumentException("INVALID_MEMORY_ARRAY");
        for (JsonNode item : value) {
            if (name.equals("entities")) {
                if (!Set.of("SHOP", "VOUCHER", "ORDER").contains(item.path("type").asText())
                        || !item.path("id").isTextual() || !item.path("id").asText().matches("[0-9]{1,20}"))
                    throw new IllegalArgumentException("INVALID_TASK_ENTITY");
            } else if (!item.isTextual() || item.asText().isBlank() || item.asText().length() > 300) {
                throw new IllegalArgumentException("INVALID_MEMORY_ITEM");
            } else if (name.equals("domains") && !Set.of("FAQ", "RECOMMENDATION", "AFTER_SALES", "COMPLAINT").contains(item.asText())) {
                throw new IllegalArgumentException("INVALID_TASK_DOMAIN");
            }
        }
    }

    public ObjectNode prune(JsonNode memory, LocalDateTime now) {
        ObjectNode result = empty();
        List<JsonNode> tasks = new ArrayList<>();
        for (JsonNode task : memory.path("tasks")) {
            int days = "ACTIVE".equals(task.path("status").asText()) ? settings.getActiveTtlDays() : settings.getEndedTtlDays();
            if (LocalDateTime.parse(task.path("updatedAt").asText()).plusDays(days).isAfter(now)) tasks.add(task.deepCopy());
        }
        tasks.sort(Comparator.<JsonNode, Boolean>comparing(t -> !"ACTIVE".equals(t.path("status").asText()))
                .thenComparing(t -> t.path("priority").asText("P1"))
                .thenComparing(t -> t.path("updatedAt").asText(), Comparator.reverseOrder()));
        ArrayNode kept = result.withArray("tasks");
        int active = 0;
        boolean primary = false;
        for (JsonNode task : tasks) {
            if (kept.size() >= settings.getMaxTasks()) break;
            if ("ACTIVE".equals(task.path("status").asText()) && ++active > settings.getMaxActiveTasks()) continue;
            if ("P0".equals(task.path("priority").asText())) {
                if (primary) ((ObjectNode) task).put("priority", "P1");
                primary = true;
            }
            kept.add(task);
        }
        return result;
    }

    public JsonNode extractionView(JsonNode memory) {
        ObjectNode view = memory.deepCopy();
        for (JsonNode task : view.path("tasks")) ((ObjectNode) task).remove("fieldEvidence");
        return view;
    }
}
