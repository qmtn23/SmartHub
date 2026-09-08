package com.hmdp.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.UserProfileProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
public class UserProfileStore {
    public record Batch(long userId, List<Long> versions, List<Long> sourceIds, String lease, int attempts) {}
    public record Snapshot(long version, JsonNode content) {}
    public static class VersionConflict extends RuntimeException {}
    public static class LostLease extends RuntimeException {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final UserProfileMerger merger;
    private final UserProfileProperties settings;

    public UserProfileStore(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager,
                             ObjectMapper json, UserProfileMerger merger, UserProfileProperties settings) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(manager);
        this.json = json; this.merger = merger; this.settings = settings;
    }

    /** Outbox insertion participates in the task-memory CAS transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void enqueue(long userId, long memoryVersion, List<Map<String, Object>> messages) {
        if (!settings.isEnabled()) return;
        List<Long> ids = new ArrayList<>();
        boolean urgent = false;
        for (var message : messages) {
            if (!"USER".equals(message.get("senderType"))) continue;
            ids.add(((Number) message.get("messageId")).longValue());
            String content = Objects.toString(message.get("content"), "");
            urgent |= content.matches("(?s).*(以后|今后|记住|忘记|忘掉|不要再|清除|删除|不再).*?");
        }
        if (ids.isEmpty()) return;
        jdbc.update("INSERT IGNORE INTO tb_customer_profile_job(user_id,memory_version,source_message_ids,status,"
                        + "next_attempt_at,create_time,update_time) VALUES(?,?,?,'PENDING',DATE_ADD(NOW(),INTERVAL ? SECOND),NOW(),NOW())",
                userId, memoryVersion, json.valueToTree(ids).toString(), urgent ? 0 : Math.max(0, settings.getCoalesceSeconds()));
    }

    public Batch claim() {
        return tx.execute(transaction -> {
            jdbc.update("UPDATE tb_customer_profile_job SET status='FAILED',error_code='LEASE_EXHAUSTED' "
                    + "WHERE status='RUNNING' AND lease_until<NOW() AND attempts>=?", settings.getMaxAttempts());
            List<Map<String,Object>> ready = jdbc.queryForList("SELECT user_id FROM tb_customer_profile_job WHERE attempts<? "
                    + "AND ((status='PENDING' AND next_attempt_at<=NOW()) OR (status='RUNNING' AND lease_until<NOW())) "
                    + "ORDER BY next_attempt_at,user_id,memory_version LIMIT 1", settings.getMaxAttempts());
            if (ready.isEmpty()) return null;
            long userId = number(ready.get(0), "user_id");
            // Fresh events for the same user are coalesced; failed events still respect retry delay.
            List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM tb_customer_profile_job WHERE user_id=? AND attempts<? "
                    + "AND ((status='PENDING' AND (attempts=0 OR next_attempt_at<=NOW())) OR (status='RUNNING' AND lease_until<NOW())) "
                    + "ORDER BY memory_version LIMIT ? FOR UPDATE", userId, settings.getMaxAttempts(),
                    Math.max(1, Math.min(3, settings.getBatchEvents())));
            if (rows.isEmpty()) return null;
            String lease = UUID.randomUUID().toString().replace("-", "");
            List<Long> versions = new ArrayList<>();
            Set<Long> sourceIds = new LinkedHashSet<>();
            int attempts = 0;
            for (var row : rows) {
                long version = number(row,"memory_version");
                jdbc.update("UPDATE tb_customer_profile_job SET status='RUNNING',lease_id=?,lease_until=DATE_ADD(NOW(),INTERVAL 180 SECOND),"
                        + "attempts=attempts+1,update_time=NOW() WHERE user_id=? AND memory_version=?",lease,userId,version);
                versions.add(version);
                for (JsonNode id : decode(row.get("source_message_ids"))) sourceIds.add(id.asLong());
                attempts = Math.max(attempts, ((Number) row.get("attempts")).intValue()+1);
            }
            return new Batch(userId,List.copyOf(versions),List.copyOf(sourceIds),lease,attempts);
        });
    }

    public void renew(Batch batch) {
        tx.executeWithoutResult(transaction -> {
            for (long version : batch.versions()) {
                if (jdbc.update("UPDATE tb_customer_profile_job SET lease_until=DATE_ADD(NOW(),INTERVAL 180 SECOND) "
                                + "WHERE user_id=? AND memory_version=? AND lease_id=? AND status='RUNNING' AND lease_until>NOW()",
                        batch.userId(),version,batch.lease()) != 1) throw new LostLease();
            }
        });
    }

    public Snapshot snapshot(long userId) {
        jdbc.update("INSERT IGNORE INTO tb_customer_user_profile(user_id,scope_type,scope_id,profile_content,schema_version,version,update_time) "
                + "VALUES(?,'PLATFORM',0,?,1,0,NOW())",userId,merger.empty().toString());
        return read(userId);
    }

    private Snapshot read(long userId) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT version,profile_content FROM tb_customer_user_profile "
                + "WHERE user_id=? AND scope_type='PLATFORM' AND scope_id=0",userId);
        if(rows.isEmpty()) return new Snapshot(0,merger.empty());
        return new Snapshot(number(rows.get(0),"version"),decode(rows.get(0).get("profile_content")));
    }

    public JsonNode readForAgent(long userId) {
        if(!settings.isEnabled()) return merger.empty();
        try { return merger.context(read(userId).content(),LocalDateTime.now()); }
        catch(RuntimeException e) {
            log.warn("画像读取失败，本轮使用无画像模式: {}",e.getClass().getSimpleName());
            return merger.empty();
        }
    }

    public List<Map<String,Object>> messages(Batch batch) {
        if(batch.sourceIds().isEmpty() || batch.sourceIds().size()>150) throw new IllegalStateException("INVALID_PROFILE_SOURCES");
        String placeholders=String.join(",",Collections.nCopies(batch.sourceIds().size(),"?"));
        List<Object> args=new ArrayList<>(); args.add(batch.userId()); args.addAll(batch.sourceIds());
        List<Map<String,Object>> messages=jdbc.query("SELECT m.message_id,m.content,m.create_time FROM tb_customer_chat_message m "
                + "JOIN tb_customer_memory_receipt r ON r.message_id=m.message_id AND r.user_id=m.user_id "
                + "WHERE m.user_id=? AND m.sender_type='USER' AND m.message_id IN ("+placeholders+") ORDER BY m.create_time,m.message_id",
                (rs,index)-> {
                    Map<String,Object> message=new LinkedHashMap<>();
                    message.put("messageId",rs.getLong("message_id")); message.put("senderType","USER");
                    message.put("content",Objects.toString(rs.getString("content"),""));
                    message.put("createTime",rs.getTimestamp("create_time").toLocalDateTime().toString());
                    return message;
                },args.toArray());
        if(messages.size()!=batch.sourceIds().size()) throw new IllegalStateException("MISSING_PROFILE_SOURCE");
        return messages;
    }

    public JsonNode relatedTasks(long userId) {
        List<String> rows=jdbc.query("SELECT memory_content FROM tb_customer_task_memory WHERE user_id=? AND scope_type='PLATFORM' AND scope_id=0",
                (rs,index)->rs.getString(1),userId);
        if(rows.isEmpty()) return json.createArrayNode();
        var tasks=json.createArrayNode();
        for(JsonNode task:decode(rows.get(0)).path("tasks")) {
            if(tasks.size()==8) break;
            var copy=json.createObjectNode();
            for(String field:List.of("taskId","intent","domains","status","updatedAt"))
                if(task.has(field)) copy.set(field,task.get(field));
            tasks.add(copy);
        }
        return tasks;
    }

    public void complete(Batch batch, Snapshot base, JsonNode next) {
        tx.executeWithoutResult(transaction -> {
            for(long version:batch.versions()) {
                if(jdbc.queryForList("SELECT memory_version FROM tb_customer_profile_job WHERE user_id=? AND memory_version=? "
                                + "AND lease_id=? AND status='RUNNING' AND lease_until>NOW() FOR UPDATE",
                        batch.userId(),version,batch.lease()).isEmpty()) throw new LostLease();
            }
            if(jdbc.update("UPDATE tb_customer_user_profile SET profile_content=?,version=version+1,update_time=NOW() "
                            + "WHERE user_id=? AND scope_type='PLATFORM' AND scope_id=0 AND version=?",
                    next.toString(),batch.userId(),base.version())!=1) throw new VersionConflict();
            for(long version:batch.versions()) jdbc.update("UPDATE tb_customer_profile_job SET status='DONE',lease_id=NULL,lease_until=NULL,"
                    + "error_code=NULL,update_time=NOW() WHERE user_id=? AND memory_version=? AND lease_id=?",batch.userId(),version,batch.lease());
        });
    }

    public void fail(Batch batch, String error) {
        for(long version:batch.versions()) jdbc.update("UPDATE tb_customer_profile_job SET "
                + "status=IF(attempts>=?,'FAILED','PENDING'),next_attempt_at=DATE_ADD(NOW(),INTERVAL ? SECOND),"
                + "lease_id=NULL,lease_until=NULL,error_code=?,update_time=NOW() WHERE user_id=? AND memory_version=? AND lease_id=? AND status='RUNNING'",
                settings.getMaxAttempts(),Math.min(300,5*(1<<Math.min(batch.attempts(),5))),error,batch.userId(),version,batch.lease());
    }

    private JsonNode decode(Object value) {
        try{return json.readTree(value.toString());}
        catch(Exception e){throw new IllegalStateException("INVALID_STORED_PROFILE",e);}
    }
    private static long number(Map<String,Object> row,String key){return ((Number)row.get(key)).longValue();}
}
