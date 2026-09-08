package com.hmdp.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.config.UserProfileProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

/** Field patches require verbatim USER evidence; clocks, identity and TTL are server-owned. */
@Component
public class UserProfileMerger {
    public static final Set<String> FIELDS = Set.of("cuisinePreference", "tastePreference", "dietaryPreference",
            "budgetPreference", "areaPreference", "servicePreference", "environmentPreference", "communicationPreference");
    private final ObjectMapper json;
    private final UserProfileProperties settings;

    public UserProfileMerger(ObjectMapper json, UserProfileProperties settings) {
        this.json = json; this.settings = settings;
    }

    public ObjectNode empty() {
        ObjectNode value = json.createObjectNode().put("schemaVersion", 1);
        value.putObject("fields");
        return value;
    }

    public ObjectNode merge(JsonNode previous, JsonNode diff, List<Map<String, Object>> messages) {
        if (diff == null || !diff.isObject() || diff.size() != 1 || !diff.path("operations").isArray()
                || diff.path("operations").size() > FIELDS.size()) throw invalid();
        Map<Long, Map<String, Object>> users = new HashMap<>();
        for (var message : messages) if ("USER".equals(message.get("senderType")))
            users.put(((Number) message.get("messageId")).longValue(), message);
        ObjectNode result = previous.deepCopy();
        ObjectNode fields = result.with("fields");
        Set<String> changed = new HashSet<>();
        for (JsonNode op : diff.path("operations")) {
            String field = op.path("field").asText();
            String action = op.path("action").asText();
            if (!op.isObject() || !FIELDS.contains(field) || !changed.add(field)
                    || !Set.of("SET", "CLEAR").contains(action)) throw invalid();
            op.fieldNames().forEachRemaining(name -> {
                if (!Set.of("field", "action", "values", "evidence").contains(name)) throw invalid();
            });
            JsonNode values = op.path("values");
            JsonNode evidence = op.path("evidence");
            if (!values.isArray() || values.size() > 6 || !evidence.isArray() || evidence.isEmpty() || evidence.size() > 5)
                throw invalid();
            if ("SET".equals(action) == values.isEmpty()) throw invalid();
            for (JsonNode value : values)
                if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > 120) throw invalid();
            LocalDateTime observed = LocalDateTime.MIN;
            long latestId = 0;
            ArrayNode provenance = json.createArrayNode();
            for (JsonNode source : evidence) {
                if (!source.path("messageId").isIntegralNumber() || !source.path("quote").isTextual()) throw invalid();
                long id = source.path("messageId").asLong();
                String quote = source.path("quote").asText();
                Map<String, Object> user = users.get(id);
                if (user == null || quote.isBlank() || quote.length() > 300
                        || !Objects.toString(user.get("content"), "").contains(quote)) throw invalid();
                LocalDateTime time = LocalDateTime.parse(user.get("createTime").toString());
                if (time.isAfter(observed) || (time.equals(observed) && id > latestId)) {
                    observed = time; latestId = id;
                }
                provenance.addObject().put("messageId", id).put("senderType", "USER").put("quote", quote);
            }
            JsonNode old = fields.path(field);
            if (old.hasNonNull("observedAt")) {
                LocalDateTime oldTime = LocalDateTime.parse(old.path("observedAt").asText());
                // A tombstone uses the same ordering rule and is never removed by TTL.
                if (observed.isBefore(oldTime) || (observed.equals(oldTime) && latestId <= old.path("lastMessageId").asLong())) continue;
            }
            ObjectNode entry = json.createObjectNode();
            entry.put("status", "SET".equals(action) ? "CONFIRMED" : "CLEARED");
            entry.put("observedAt", observed.toString());
            entry.put("lastMessageId", latestId);
            entry.set("sources", provenance);
            if ("SET".equals(action)) {
                entry.set("value", values.deepCopy());
                entry.put("expiresAt", observed.plusDays(Math.max(1, settings.getConfirmedTtlDays())).toString());
            }
            fields.set(field, entry);
        }
        return result;
    }

    /** Expired values are excluded, while barriers remain visible to the extraction model. */
    public ObjectNode extractionView(JsonNode profile, LocalDateTime now) {
        ObjectNode result = empty();
        for (String field : FIELDS) {
            JsonNode entry = profile.path("fields").path(field);
            if (entry.isMissingNode()) continue;
            ObjectNode copy = entry.deepCopy();
            copy.remove("sources");
            if (!active(entry, now) && !"CLEARED".equals(entry.path("status").asText())) {
                copy.remove("value");
                copy.put("status", "EXPIRED");
            }
            result.with("fields").set(field, copy);
        }
        return result;
    }

    public ObjectNode context(JsonNode profile, LocalDateTime now) {
        ObjectNode result = empty();
        for (String field : FIELDS) {
            JsonNode entry = profile.path("fields").path(field);
            if (!active(entry, now)) continue;
            ObjectNode copy = json.createObjectNode().put("status", "CONFIRMED");
            copy.set("value", entry.path("value").deepCopy());
            copy.put("observedAt", entry.path("observedAt").asText());
            copy.put("expiresAt", entry.path("expiresAt").asText());
            result.with("fields").set(field, copy);
        }
        return result;
    }

    private boolean active(JsonNode entry, LocalDateTime now) {
        return "CONFIRMED".equals(entry.path("status").asText()) && entry.hasNonNull("expiresAt")
                && LocalDateTime.parse(entry.path("expiresAt").asText()).isAfter(now);
    }
    private IllegalArgumentException invalid() { return new IllegalArgumentException("INVALID_PROFILE_DIFF"); }
}
