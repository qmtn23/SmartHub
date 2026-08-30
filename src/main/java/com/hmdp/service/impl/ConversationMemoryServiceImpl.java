package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.entity.CustomerChat;
import com.hmdp.entity.CustomerChatMessage;
import com.hmdp.entity.CustomerImChat;
import com.hmdp.mapper.CustomerChatMapper;
import com.hmdp.mapper.CustomerChatMessageMapper;
import com.hmdp.mapper.CustomerImChatMapper;
import com.hmdp.service.ConversationSummarizer;
import com.hmdp.service.CustomerAgentClient;
import com.hmdp.service.IConversationMemoryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static com.hmdp.utils.CustomerChatConstants.*;

@Slf4j
@Service
public class ConversationMemoryServiceImpl implements IConversationMemoryService {

    private final CustomerChatMapper chatMapper;
    private final CustomerChatMessageMapper messageMapper;
    private final CustomerImChatMapper imChatMapper;
    private final ConversationSummarizer summarizer;
    private final CustomerAgentClient customerAgentClient;

    public ConversationMemoryServiceImpl(CustomerChatMapper chatMapper,
                                         CustomerChatMessageMapper messageMapper,
                                         CustomerImChatMapper imChatMapper,
                                         ConversationSummarizer summarizer,
                                         CustomerAgentClient customerAgentClient) {
        this.chatMapper = chatMapper;
        this.messageMapper = messageMapper;
        this.imChatMapper = imChatMapper;
        this.summarizer = summarizer;
        this.customerAgentClient = customerAgentClient;
    }

    @Override
    public void finalizeChat(CustomerImChat imChat, CustomerChat chat, LocalDateTime endTime) {
        if (chat == null || !CHAT_STATUS_ACTIVE.equals(chat.getStatus())) {
            return;
        }

        chat.setStatus(CHAT_STATUS_ENDED);
        chat.setEndTime(endTime);
        chat.setLastActiveTime(endTime);
        chatMapper.updateById(chat);

        try {
            List<CustomerChatMessage> messages = loadRecentMessages(chat);
            if (!messages.isEmpty()) {
                String sessionSummary = summarizeWithFallback(messages);
                String longTermSummary = mergeWithFallback(imChat.getSummary(), sessionSummary);

                chat.setSummary(sessionSummary);
                chatMapper.updateById(chat);

                imChat.setSummary(longTermSummary);
                imChat.setUpdateTime(endTime);
                imChatMapper.updateById(imChat);
            }
        } finally {
            try {
                customerAgentClient.deleteThread(chat.getChatId());
            } catch (RuntimeException e) {
                log.warn("清理Agent短会话状态失败，将由TTL兜底: chatId={}", chat.getChatId(), e);
            }
        }
    }

    @Override
    public String getLongTermMemory(CustomerImChat imChat) {
        if (imChat == null || imChat.getSummary() == null || imChat.getSummary().isBlank()) {
            return NO_LONG_TERM_MEMORY;
        }
        return abbreviate(imChat.getSummary().trim(), IM_CHAT_SUMMARY_MAX_LENGTH);
    }

    private List<CustomerChatMessage> loadRecentMessages(CustomerChat chat) {
        List<CustomerChatMessage> messages = messageMapper.selectList(
                new QueryWrapper<CustomerChatMessage>()
                        .eq("chat_id", chat.getChatId())
                        .eq("im_chat_id", chat.getImChatId())
                        .eq("user_id", chat.getUserId())
                        .orderByDesc("message_id")
                        .last("LIMIT " + SUMMARY_MESSAGE_LIMIT));
        Collections.reverse(messages);
        return messages;
    }

    private String summarizeWithFallback(List<CustomerChatMessage> messages) {
        try {
            return summarizer.summarizeSession(messages);
        } catch (RuntimeException e) {
            log.warn("短会话摘要生成失败，使用确定性摘要降级", e);
            return fallbackSessionSummary(messages);
        }
    }

    private String mergeWithFallback(String previousSummary, String sessionSummary) {
        try {
            return summarizer.mergeLongTerm(previousSummary, sessionSummary);
        } catch (RuntimeException e) {
            log.warn("长会话摘要合并失败，使用追加摘要降级", e);
            String previous = previousSummary == null ? "" : previousSummary.trim();
            String merged = previous.isBlank()
                    ? sessionSummary
                    : previous + "\n本轮补充：" + sessionSummary;
            return abbreviate(merged, IM_CHAT_SUMMARY_MAX_LENGTH);
        }
    }

    private String fallbackSessionSummary(List<CustomerChatMessage> messages) {
        StringBuilder summary = new StringBuilder("本轮对话摘录：");
        int start = Math.max(0, messages.size() - FALLBACK_SUMMARY_MESSAGE_COUNT);
        for (int i = start; i < messages.size(); i++) {
            CustomerChatMessage message = messages.get(i);
            if (message.getContent() == null || message.getContent().isBlank()) {
                continue;
            }
            summary.append('\n')
                    .append(SENDER_USER.equals(message.getSenderType()) ? "用户：" : "客服：")
                    .append(abbreviate(message.getContent().trim(), FALLBACK_MESSAGE_CONTENT_LIMIT));
        }
        return abbreviate(summary.toString(), CHAT_SUMMARY_MAX_LENGTH);
    }

    private String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
