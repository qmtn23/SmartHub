package com.hmdp.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.config.SemanticMemoryProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.*;

/** Canonical historical items and a transactional vector outbox. */
@Service
public class SemanticMemoryStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SemanticMemoryProperties settings;

    public SemanticMemoryStore(JdbcTemplate jdbc, ObjectMapper json, SemanticMemoryProperties settings) {
        this.jdbc = jdbc; this.json = json; this.settings = settings;
    }

    public boolean enabled() { return settings.isEnabled(); }

    /** Bounded migration of existing canonical task summaries; no re-extraction. */
    @Transactional
    public void backfill() {
        if (!enabled()) return;
        var rows=jdbc.queryForList("SELECT m.user_id,m.memory_content,m.version FROM tb_customer_task_memory m "
                + "LEFT JOIN tb_customer_memory_backfill b ON b.user_id=m.user_id "
                + "WHERE m.scope_type='PLATFORM' AND m.scope_id=0 AND b.user_id IS NULL ORDER BY m.user_id LIMIT 10 FOR UPDATE");
        for(var row:rows) {
            long userId=((Number)row.get("user_id")).longValue();
            capture(userId,decode(row.get("memory_content")));
            jdbc.update("INSERT IGNORE INTO tb_customer_memory_backfill(user_id,memory_version,processed_at) VALUES(?,?,NOW())",
                    userId,row.get("version"));
        }
    }

    /** Invoked inside TaskMemoryStore's CAS transaction, before working-set pruning. */
    public void capture(long userId, JsonNode memory) {
        if (!enabled()) return;
        for (JsonNode task : memory.path("tasks")) {
            String taskId = task.path("taskId").asText();
            if (taskId.isBlank() || !task.path("fieldEvidence").isObject()) continue;
            ObjectNode body = task.deepCopy();
            JsonNode evidence = body.remove("fieldEvidence");
            String content = body.toString();
            String hash = DigestUtils.md5DigestAsHex((content + evidence).getBytes(StandardCharsets.UTF_8));
            String id = DigestUtils.md5DigestAsHex((userId + ":PLATFORM:0:" + taskId).getBytes(StandardCharsets.UTF_8));
            LocalDateTime observed = LocalDateTime.parse(task.path("updatedAt").asText());
            LocalDateTime expires = observed.plusDays(Math.max(1, settings.getRetentionDays()));
            List<Map<String,Object>> old = jdbc.queryForList(
                    "SELECT content_hash,version,status FROM tb_customer_memory_item WHERE memory_id=? FOR UPDATE", id);
            // A tombstone is never resurrected by replay or later extraction of the same task.
            if (!old.isEmpty() && (!"ACTIVE".equals(old.get(0).get("status"))
                    || hash.equals(old.get(0).get("content_hash")))) continue;
            if (!expires.isAfter(LocalDateTime.now())) continue;
            long version = old.isEmpty() ? 1 : ((Number)old.get(0).get("version")).longValue() + 1;
            String type = task.path("decisions").isEmpty() ? "EPISODE" : "DECISION";
            jdbc.update("INSERT INTO tb_customer_memory_item(memory_id,user_id,task_id,memory_type,content,source_refs,"
                            + "content_hash,version,observed_at,valid_until,update_time) VALUES(?,?,?,?,?,?,?,?,?,?,NOW()) "
                            + "ON DUPLICATE KEY UPDATE memory_type=VALUES(memory_type),content=VALUES(content),source_refs=VALUES(source_refs),"
                            + "content_hash=VALUES(content_hash),version=VALUES(version),observed_at=VALUES(observed_at),"
                            + "valid_until=VALUES(valid_until),update_time=NOW()",
                    id,userId,taskId,type,content,evidence.toString(),hash,version,observed,expires);
            enqueue(id,version);
        }
    }

    private void enqueue(String id, long version) {
        jdbc.update("INSERT IGNORE INTO tb_customer_memory_index_job(memory_id,memory_version,next_attempt_at,update_time) "
                + "VALUES(?,?,NOW(),NOW())",id,version);
    }

    public List<Map<String,Object>> lookup(long userId, List<String> ids) {
        if (!enabled() || ids.isEmpty()) return List.of();
        if (ids.size()>50 || ids.stream().anyMatch(id -> id == null || !id.matches("[a-f0-9]{32}")))
            throw new IllegalArgumentException("INVALID_MEMORY_IDS");
        List<Object> args = new ArrayList<>(); args.add(userId); args.addAll(ids);
        return jdbc.queryForList("SELECT * FROM tb_customer_memory_item WHERE user_id=? AND scope_type='PLATFORM' AND scope_id=0 "
                + "AND status='ACTIVE' AND valid_until>NOW() AND memory_id IN ("
                + String.join(",", Collections.nCopies(ids.size(),"?")) + ")",args.toArray());
    }

    public List<Map<String,Object>> recent(long userId) {
        if (!enabled()) return List.of();
        return jdbc.queryForList("SELECT * FROM tb_customer_memory_item WHERE user_id=? AND scope_type='PLATFORM' AND scope_id=0 "
                + "AND status='ACTIVE' AND valid_until>NOW() ORDER BY observed_at DESC LIMIT 50",userId);
    }

    public List<Map<String,Object>> byEntities(long userId,List<String> ids) {
        if(!enabled() || ids==null || ids.isEmpty()) return List.of();
        if(ids.size()>10 || ids.stream().anyMatch(id -> id==null || !id.matches("[0-9]{1,20}")))
            throw new IllegalArgumentException("INVALID_MEMORY_ENTITIES");
        List<Object> args=new ArrayList<>(); args.add(userId); args.addAll(ids);
        String terms=String.join(" OR ",Collections.nCopies(ids.size(),"JSON_CONTAINS(content,JSON_OBJECT('id',?),'$.entities')"));
        return jdbc.queryForList("SELECT * FROM tb_customer_memory_item WHERE user_id=? AND scope_type='PLATFORM' AND scope_id=0 "
                + "AND status='ACTIVE' AND valid_until>NOW() AND ("+terms+") ORDER BY observed_at DESC LIMIT 30",args.toArray());
    }

    public Map<String,Object> publicItem(Map<String,Object> row) {
        return Map.of("memory_id", row.get("memory_id"), "version", row.get("version"),
                "type", row.get("memory_type"), "content", decode(row.get("content")),
                "source_refs", decode(row.get("source_refs")),
                "observed_at", row.get("observed_at").toString(), "valid_until", row.get("valid_until").toString(),
                "historical_only",true);
    }

    /** Explicit application operation; never registered as an LLM tool. */
    @Transactional
    public void forget(long userId, List<String> ids) {
        for (Map<String,Object> row : lookup(userId,ids)) {
            String id = row.get("memory_id").toString();
            jdbc.update("UPDATE tb_customer_memory_item SET status='DELETED',version=version+1,update_time=NOW() "
                    + "WHERE memory_id=? AND user_id=? AND status='ACTIVE'",id,userId);
            long version = jdbc.queryForObject("SELECT version FROM tb_customer_memory_item WHERE memory_id=?",Long.class,id);
            enqueue(id,version);
        }
    }

    @Transactional
    public void expire() {
        if (!enabled()) return;
        for (Map<String,Object> row : jdbc.queryForList("SELECT memory_id FROM tb_customer_memory_item "
                + "WHERE status='ACTIVE' AND valid_until<=NOW() ORDER BY valid_until LIMIT 100 FOR UPDATE")) {
            String id = row.get("memory_id").toString();
            jdbc.update("UPDATE tb_customer_memory_item SET status='EXPIRED',version=version+1,update_time=NOW() WHERE memory_id=?",id);
            enqueue(id,jdbc.queryForObject("SELECT version FROM tb_customer_memory_item WHERE memory_id=?",Long.class,id));
        }
    }

    public void appendEvent(long userId,long chatId,long messageId,String runId,String eventId,String type,JsonNode payload) {
        if (!enabled()) return;
        if (!runId.matches("[A-Za-z0-9_-]{1,64}") || !eventId.matches("[a-f0-9]{64}")
                || !Set.of("MODEL_INPUT","MODEL_OUTPUT","TOOL_RESULT","RUN_FINALIZED").contains(type)
                || payload == null || payload.toString().getBytes(StandardCharsets.UTF_8).length>1_000_000)
            throw new IllegalArgumentException("INVALID_MEMORY_EVENT");
        Integer owned = jdbc.queryForObject("SELECT COUNT(*) FROM tb_customer_chat_message WHERE message_id=? AND user_id=? AND chat_id=?",
                Integer.class,messageId,userId,chatId);
        if (owned == null || owned != 1) throw new IllegalArgumentException("INVALID_MEMORY_EVENT_OWNER");
        jdbc.update("INSERT IGNORE INTO tb_customer_memory_event(event_id,user_id,chat_id,user_message_id,run_id,event_type,payload,create_time) "
                + "VALUES(?,?,?,?,?,?,?,NOW())",eventId,userId,chatId,messageId,runId,type,payload.toString());
    }

    private JsonNode decode(Object value) {
        try { return json.readTree(value.toString()); }
        catch (Exception e) { throw new IllegalStateException("INVALID_MEMORY_ITEM",e); }
    }
}
