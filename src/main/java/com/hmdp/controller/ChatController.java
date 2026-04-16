package com.hmdp.controller;

import com.hmdp.dto.ChatRequest;
import com.hmdp.dto.Result;
import com.hmdp.service.CustomerAssistant;
import com.hmdp.utils.RedisChatMemoryStore;
import com.hmdp.utils.UserHolder;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/chat")
public class ChatController {

    @Resource
    private CustomerAssistant customerAssistant;

    @Resource
    private RedisChatMemoryStore chatMemoryStore;

    @PostMapping("/send")
    public Result sendMessage(@RequestBody ChatRequest request) {
        Long userId = UserHolder.getUser().getId();
        String message = request.getMessage();
        if (message == null || message.isBlank()) {
            return Result.fail("消息不能为空");
        }
        log.debug("用户{}发送消息: {}", userId, message);
        String reply = customerAssistant.chat(userId, message);
        return Result.ok(reply);
    }

    @GetMapping("/history")
    public Result getHistory() {
        Long userId = UserHolder.getUser().getId();
        List<ChatMessage> messages = chatMemoryStore.getMessages(userId);
        List<Map<String, String>> history = messages.stream()
                .filter(msg -> msg.type() == ChatMessageType.USER || msg.type() == ChatMessageType.AI)
                .map(msg -> {
                    Map<String, String> item = new HashMap<>();
                    if (msg instanceof UserMessage) {
                        item.put("role", "user");
                        item.put("content", ((UserMessage) msg).singleText());
                    } else if (msg instanceof AiMessage) {
                        item.put("role", "assistant");
                        item.put("content", ((AiMessage) msg).text());
                    }
                    return item;
                })
                .collect(Collectors.toList());
        return Result.ok(history);
    }

    @DeleteMapping("/history")
    public Result clearHistory() {
        Long userId = UserHolder.getUser().getId();
        chatMemoryStore.deleteMessages(userId);
        return Result.ok();
    }
}
