package com.hmdp.utils;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String key = CHAT_HISTORY_KEY + memoryId;
        List<String> jsonList = stringRedisTemplate.opsForList().range(key, 0, -1);
        if (jsonList == null || jsonList.isEmpty()) {
            return new ArrayList<>();
        }
        List<ChatMessage> messages = new ArrayList<>(jsonList.size());
        for (String json : jsonList) {
            messages.add(ChatMessageDeserializer.messageFromJson(json));
        }
        return messages;
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        String key = CHAT_HISTORY_KEY + memoryId;
        stringRedisTemplate.delete(key);
        for (ChatMessage message : messages) {
            stringRedisTemplate.opsForList().rightPush(key, ChatMessageSerializer.messageToJson(message));
        }
        stringRedisTemplate.opsForList().trim(key, -CHAT_HISTORY_MAX_SIZE, -1);
        stringRedisTemplate.expire(key, CHAT_HISTORY_TTL, TimeUnit.MINUTES);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        stringRedisTemplate.delete(CHAT_HISTORY_KEY + memoryId);
    }
}
