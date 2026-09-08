package com.hmdp.service.memory;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.TaskMemoryProperties;
import com.hmdp.config.WorkingMemoryProperties;
import com.hmdp.entity.CustomerChatMessage;
import com.hmdp.mapper.CustomerChatMessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.annotation.Resource;
import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/** Redis-backed working-memory window; MySQL remains the durable source of truth. */
@Slf4j
@Service
public class WorkingMemoryService {
    private static final String KEY_PREFIX = "customer:working-memory:v1:";
    private static final String READY_SUFFIX = ":ready";
    private static final DefaultRedisScript<Long> APPEND_AND_ROLL = new DefaultRedisScript<>(
            "redis.call('ZADD',KEYS[1],ARGV[1],ARGV[2]); "
                    + "redis.call('EXPIRE',KEYS[1],ARGV[3]); "
                    + "if redis.call('EXISTS',KEYS[2])==1 then redis.call('EXPIRE',KEYS[2],ARGV[3]); end; "
                    + "local n=redis.call('ZCARD',KEYS[1]); "
                    + "if n>tonumber(ARGV[4]) then redis.call('ZREMRANGEBYRANK',KEYS[1],0,n-tonumber(ARGV[5])-1); end; "
                    + "return n;", Long.class);

    private final StringRedisTemplate redis;
    private final CustomerChatMessageMapper messages;
    private final ObjectMapper json;
    private final WorkingMemoryProperties settings;
    private final TaskMemoryProperties taskSettings;
    @Resource
    private TaskMemoryStore taskMemories;
    private final ThreadPoolExecutor writer = new ThreadPoolExecutor(
            2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(500), work -> {
        Thread thread = new Thread(work, "working-memory-writer");
        thread.setDaemon(true);
        return thread;
    }, new ThreadPoolExecutor.AbortPolicy());

    public WorkingMemoryService(StringRedisTemplate redis, CustomerChatMessageMapper messages,
                                ObjectMapper json, WorkingMemoryProperties settings,
                                TaskMemoryProperties taskSettings) {
        this.redis = redis;
        this.messages = messages;
        this.json = json;
        this.settings = settings;
        this.taskSettings = taskSettings;
    }

    /** Cache publication happens only after the surrounding database transaction commits. */
    public void appendAfterCommit(CustomerChatMessage message) {
        if (!settings.isEnabled() || message == null || message.getMessageId() == null
                || message.getImChatId() == null || message.getCreateTime() == null) return;
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { submitAppend(message); }
            });
        } else {
            submitAppend(message);
        }
    }

    public List<CustomerChatMessage> loadRecent(CustomerChatMessage currentMessage) {
        if (!settings.isEnabled()) return loadFromDatabase(currentMessage);
        try {
            Boolean ready = redis.hasKey(readyKey(currentMessage.getImChatId()));
            if (Boolean.TRUE.equals(ready)) {
                Set<String> values = redis.opsForZSet().range(key(currentMessage.getImChatId()), 0, -1);
                if (values != null && !values.isEmpty()) {
                    List<CustomerChatMessage> cached = decode(values);
                    cached.removeIf(item -> !before(item, currentMessage));
                    cached.sort(Comparator.comparing(CustomerChatMessage::getCreateTime)
                            .thenComparing(CustomerChatMessage::getMessageId));
                    int start = Math.max(0, cached.size() - windowSize());
                    return new ArrayList<>(cached.subList(start, cached.size()));
                }
            }
        } catch (RuntimeException error) {
            log.warn("Redis工作记忆读取失败，回源MySQL: imChatId={}, error={}",
                    currentMessage.getImChatId(), error.getClass().getSimpleName());
        }
        List<CustomerChatMessage> fallback = loadFromDatabase(currentMessage);
        submitWarm(currentMessage.getImChatId(), fallback);
        return fallback;
    }

    private void submitAppend(CustomerChatMessage message) {
        try {
            writer.execute(() -> append(message));
        } catch (RejectedExecutionException error) {
            // The durable MySQL history remains available for the next cache miss.
            log.warn("Redis工作记忆写入队列已满，保留MySQL回源: imChatId={}, messageId={}",
                    message.getImChatId(), message.getMessageId());
        }
    }

    private void submitWarm(Long imChatId, List<CustomerChatMessage> history) {
        try {
            writer.execute(() -> {
                for (CustomerChatMessage message : history) append(message);
                try {
                    redis.opsForValue().set(readyKey(imChatId), "1",
                            Math.max(1, settings.getTtlHours()), TimeUnit.HOURS);
                } catch (RuntimeException error) {
                    log.warn("Redis工作记忆预热标记失败: imChatId={}, error={}",
                            imChatId, error.getClass().getSimpleName());
                }
            });
        } catch (RejectedExecutionException error) {
            log.warn("Redis工作记忆预热队列已满，继续使用MySQL回源: imChatId={}", imChatId);
        }
    }

    private void append(CustomerChatMessage message) {
        try {
            String member = String.format("%020d|%s", message.getMessageId(), json.writeValueAsString(Map.of(
                    "messageId", message.getMessageId(),
                    "imChatId", message.getImChatId(),
                    "chatId", message.getChatId(),
                    "userId", message.getUserId(),
                    "senderType", Objects.toString(message.getSenderType(), "SYSTEM"),
                    "content", Objects.toString(message.getContent(), ""),
                    "createTime", message.getCreateTime().toString()
            )));
            double score = message.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            Long count = redis.execute(APPEND_AND_ROLL,
                    Arrays.asList(key(message.getImChatId()), readyKey(message.getImChatId())),
                    Double.toString(score), member,
                    Long.toString(TimeUnit.HOURS.toSeconds(Math.max(1, settings.getTtlHours()))),
                    Integer.toString(Math.max(windowSize(), settings.getRollingThreshold())),
                    Integer.toString(windowSize()));
            if (count != null && count > Math.max(windowSize(), settings.getRollingThreshold())
                    && taskSettings.isEnabled() && taskMemories != null) {
                taskMemories.enqueue(message.getUserId(), message.getImChatId(), message.getChatId());
            }
        } catch (Exception error) {
            // Working memory is a cache. A cache failure must not roll back a committed chat message.
            log.warn("Redis工作记忆写入失败: imChatId={}, messageId={}, error={}",
                    message.getImChatId(), message.getMessageId(), error.getClass().getSimpleName());
        }
    }

    private List<CustomerChatMessage> loadFromDatabase(CustomerChatMessage current) {
        QueryWrapper<CustomerChatMessage> query = new QueryWrapper<>();
        query.eq("user_id", current.getUserId())
                .eq("im_chat_id", current.getImChatId())
                .and(part -> part.lt("create_time", current.getCreateTime())
                        .or(nested -> nested.eq("create_time", current.getCreateTime())
                                .lt("message_id", current.getMessageId())))
                .orderByDesc("create_time").orderByDesc("message_id")
                .last("LIMIT " + windowSize());
        List<CustomerChatMessage> result = messages.selectList(query);
        Collections.reverse(result);
        return result;
    }

    private List<CustomerChatMessage> decode(Set<String> values) {
        List<CustomerChatMessage> result = new ArrayList<>();
        for (String value : values) {
            int separator = value.indexOf('|');
            if (separator < 0) continue;
            JsonNode item;
            try {
                item = json.readTree(value.substring(separator + 1));
            } catch (Exception error) {
                throw new IllegalStateException("INVALID_WORKING_MEMORY_ENTRY", error);
            }
            CustomerChatMessage message = new CustomerChatMessage()
                    .setMessageId(item.path("messageId").asLong())
                    .setImChatId(item.path("imChatId").asLong())
                    .setChatId(item.path("chatId").asLong())
                    .setUserId(item.path("userId").asLong())
                    .setSenderType(item.path("senderType").asText())
                    .setContent(item.path("content").asText())
                    .setCreateTime(LocalDateTime.parse(item.path("createTime").asText()));
            result.add(message);
        }
        return result;
    }

    private boolean before(CustomerChatMessage candidate, CustomerChatMessage current) {
        int time = candidate.getCreateTime().compareTo(current.getCreateTime());
        return time < 0 || (time == 0 && candidate.getMessageId() < current.getMessageId());
    }

    private int windowSize() { return Math.max(1, Math.min(100, settings.getWindowSize())); }
    private String key(Long imChatId) { return KEY_PREFIX + imChatId; }
    private String readyKey(Long imChatId) { return key(imChatId) + READY_SUFFIX; }

    @PreDestroy
    public void close() { writer.shutdownNow(); }
}
