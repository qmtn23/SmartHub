package com.hmdp.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.TaskMemoryProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;

/** MySQL is authoritative. No transaction or row lock spans a model call. */
@Service
public class TaskMemoryStore {
    public record Job(long chatId, long userId, long imChatId, String lease, int attempts) {}
    public record Snapshot(long version, JsonNode content) {}
    public static class VersionConflict extends RuntimeException {}
    public static class LostLease extends RuntimeException {}
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final TaskMemoryMerger merger;
    private final TaskMemoryProperties settings;
    @javax.annotation.Resource
    private UserProfileStore userProfiles;

    public TaskMemoryStore(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager manager,
                           ObjectMapper json, TaskMemoryMerger merger, TaskMemoryProperties settings) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        this.json = json;
        this.merger = merger;
        this.settings = settings;
    }

    /** Called in the same transaction as ending a chat; background discovery repairs other triggers. */
    public void endChat(long userId, long imChatId, long chatId, LocalDateTime endTime) {
        tx.executeWithoutResult(status -> {
            jdbc.update("UPDATE tb_customer_chat SET status='ENDED',end_time=?,last_active_time=? "
                            + "WHERE user_id=? AND im_chat_id=? AND chat_id=? AND status='ACTIVE'",
                    endTime, endTime, userId, imChatId, chatId);
            enqueue(userId, imChatId, chatId);
        });
    }

    public void enqueue(long userId, long imChatId, long chatId) {
        jdbc.update("INSERT INTO tb_customer_memory_job(chat_id,user_id,im_chat_id,status,next_attempt_at,update_time) "
                        + "VALUES(?,?,?,'PENDING',NOW(),NOW()) ON DUPLICATE KEY UPDATE "
                        + "status=IF(status='DONE','PENDING',status),next_attempt_at=IF(status='PENDING',NOW(),next_attempt_at)",
                chatId, userId, imChatId);
    }

    /** Durable source reconciliation also covers crashes between message commit and scheduling. */
    public void discover() {
        List<Map<String, Object>> candidates = jdbc.queryForList(
                "SELECT m.chat_id,m.user_id,m.im_chat_id,MAX(m.message_id) AS target_id "
                + "FROM tb_customer_chat_message m JOIN tb_customer_chat c ON c.chat_id=m.chat_id AND c.user_id=m.user_id "
                + "LEFT JOIN tb_customer_memory_receipt r ON r.message_id=m.message_id "
                + "LEFT JOIN tb_customer_memory_job j ON j.chat_id=m.chat_id "
                + "WHERE r.message_id IS NULL AND (j.chat_id IS NULL OR j.status='DONE') "
                + "GROUP BY m.chat_id,m.user_id,m.im_chat_id "
                + "HAVING COUNT(*)>=? OR MIN(m.create_time)<=DATE_SUB(NOW(),INTERVAL ? SECOND) "
                + "OR MAX(CASE WHEN c.status='ENDED' THEN 1 ELSE 0 END)=1 "
                + "ORDER BY MIN(m.create_time) LIMIT 100", settings.getTriggerMessages(), settings.getMaxDelaySeconds());
        for (Map<String, Object> row : candidates) {
            enqueue(number(row, "user_id"), number(row, "im_chat_id"), number(row, "chat_id"));
            jdbc.update("UPDATE tb_customer_memory_job SET target_msg_id=GREATEST(target_msg_id,?) WHERE chat_id=?",
                    number(row, "target_id"), number(row, "chat_id"));
        }
    }

    public Job claim() {
        return tx.execute(status -> {
            jdbc.update("UPDATE tb_customer_memory_job SET status='FAILED',error_code='LEASE_EXHAUSTED' "
                    + "WHERE status='RUNNING' AND lease_until<NOW() AND attempts>=?", settings.getMaxAttempts());
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM tb_customer_memory_job WHERE attempts<? AND "
                    + "((status='PENDING' AND next_attempt_at<=NOW()) OR (status='RUNNING' AND lease_until<NOW())) "
                    + "ORDER BY next_attempt_at,chat_id LIMIT 10", settings.getMaxAttempts());
            for (Map<String, Object> row : rows) {
                String lease = UUID.randomUUID().toString().replace("-", "");
                long chatId = number(row, "chat_id");
                int count = jdbc.update("UPDATE tb_customer_memory_job SET status='RUNNING',lease_id=?, "
                                + "lease_until=DATE_ADD(NOW(),INTERVAL 180 SECOND),attempts=attempts+1,update_time=NOW() "
                                + "WHERE chat_id=? AND attempts<? AND ((status='PENDING' AND next_attempt_at<=NOW()) "
                                + "OR (status='RUNNING' AND lease_until<NOW()))", lease, chatId, settings.getMaxAttempts());
                if (count == 1) return new Job(chatId, number(row, "user_id"), number(row, "im_chat_id"),
                        lease, ((Number) row.get("attempts")).intValue() + 1);
            }
            return null;
        });
    }

    public Snapshot snapshot(long userId) {
        jdbc.update("INSERT IGNORE INTO tb_customer_task_memory(user_id,scope_type,scope_id,memory_content,version,update_time) "
                + "VALUES(?,'PLATFORM',0,?,0,NOW())", userId, merger.empty().toString());
        return read(userId);
    }

    public Snapshot read(long userId) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT version,memory_content FROM tb_customer_task_memory "
                + "WHERE user_id=? AND scope_type='PLATFORM' AND scope_id=0", userId);
        if (rows.isEmpty()) return new Snapshot(0, merger.empty());
        Map<String, Object> row = rows.get(0);
        return new Snapshot(number(row, "version"), merger.prune(decode(row.get("memory_content")), LocalDateTime.now()));
    }

    public List<Map<String, Object>> messages(Job job) {
        // Do not filter solely by last_msg_id: ID allocation is not commit order.
        return jdbc.query("SELECT m.message_id,m.sender_type,m.content,m.create_time,m.consultation_context "
                + "FROM tb_customer_chat_message m LEFT JOIN tb_customer_memory_receipt r ON r.message_id=m.message_id "
                + "WHERE m.user_id=? AND m.chat_id=? AND m.im_chat_id=? AND r.message_id IS NULL "
                + "ORDER BY m.message_id LIMIT ?", (rs, index) -> {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("messageId", rs.getLong("message_id"));
            message.put("senderType", rs.getString("sender_type"));
            message.put("content", Objects.toString(rs.getString("content"), ""));
            message.put("createTime", rs.getTimestamp("create_time").toLocalDateTime().toString());
            JsonNode context = decodeNullable(rs.getString("consultation_context"));
            message.put("consultationContext", context.isObject() ? context : json.createObjectNode());
            return message;
        }, job.userId(), job.chatId(), job.imChatId(), Math.max(1, Math.min(50, settings.getBatchSize())));
    }

    public void complete(Job job, Snapshot base, JsonNode next, List<Map<String, Object>> messages) {
        tx.executeWithoutResult(status -> {
            List<Map<String, Object>> lease = jdbc.queryForList("SELECT chat_id FROM tb_customer_memory_job "
                            + "WHERE chat_id=? AND user_id=? AND lease_id=? AND status='RUNNING' AND lease_until>NOW() FOR UPDATE",
                    job.chatId(), job.userId(), job.lease());
            if (lease.isEmpty()) throw new LostLease();
            if (jdbc.update("UPDATE tb_customer_task_memory SET memory_content=?,version=version+1,update_time=NOW() "
                            + "WHERE user_id=? AND scope_type='PLATFORM' AND scope_id=0 AND version=?",
                    next.toString(), job.userId(), base.version()) != 1) throw new VersionConflict();
            long cursor = 0;
            for (Map<String, Object> message : messages) {
                long id = ((Number) message.get("messageId")).longValue();
                jdbc.update("INSERT INTO tb_customer_memory_receipt(message_id,user_id,chat_id,processed_at) VALUES(?,?,?,NOW())",
                        id, job.userId(), job.chatId());
                cursor = Math.max(cursor, id);
            }
            if (userProfiles != null) userProfiles.enqueue(job.userId(), base.version() + 1, messages);
            jdbc.update("UPDATE tb_customer_memory_job SET status='DONE',last_msg_id=GREATEST(last_msg_id,?), "
                            + "lease_id=NULL,lease_until=NULL,attempts=0,error_code=NULL,update_time=NOW() WHERE chat_id=? AND lease_id=?",
                    cursor, job.chatId(), job.lease());
        });
    }

    public void fail(Job job, String error) {
        jdbc.update("UPDATE tb_customer_memory_job SET status=?,next_attempt_at=DATE_ADD(NOW(),INTERVAL ? SECOND), "
                        + "lease_id=NULL,lease_until=NULL,error_code=?,update_time=NOW() WHERE chat_id=? AND lease_id=? AND status='RUNNING'",
                job.attempts() >= settings.getMaxAttempts() ? "FAILED" : "PENDING",
                Math.min(300, 5 * (1 << Math.min(job.attempts(), 5))), error, job.chatId(), job.lease());
    }

    private JsonNode decode(Object value) {
        try { return json.readTree(value.toString()); }
        catch (Exception e) { throw new IllegalStateException("INVALID_STORED_MEMORY", e); }
    }
    private JsonNode decodeNullable(String value) {
        return value == null || value.isBlank() ? json.createObjectNode() : decode(value);
    }
    private static long number(Map<String, Object> row, String key) { return ((Number) row.get(key)).longValue(); }
}
