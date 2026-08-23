package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.config.ChatBusinessException;
import com.hmdp.dto.ChatReplyDTO;
import com.hmdp.dto.ChatRequest;
import com.hmdp.entity.CustomerChat;
import com.hmdp.entity.CustomerChatMessage;
import com.hmdp.entity.CustomerImChat;
import com.hmdp.mapper.CustomerChatMapper;
import com.hmdp.mapper.CustomerChatMessageMapper;
import com.hmdp.mapper.CustomerImChatMapper;
import com.hmdp.service.CustomerAssistant;
import com.hmdp.service.ICustomerChatService;
import com.hmdp.utils.RedisChatMemoryStore;
import com.hmdp.utils.RedisIdWorker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static com.hmdp.utils.CustomerChatConstants.*;

@Service
public class CustomerChatServiceImpl implements ICustomerChatService {

    private final CustomerImChatMapper imChatMapper;
    private final CustomerChatMapper chatMapper;
    private final CustomerChatMessageMapper messageMapper;
    private final CustomerAssistant customerAssistant;
    private final RedisChatMemoryStore chatMemoryStore;
    private final RedisIdWorker redisIdWorker;

    public CustomerChatServiceImpl(CustomerImChatMapper imChatMapper,
                                   CustomerChatMapper chatMapper,
                                   CustomerChatMessageMapper messageMapper,
                                   CustomerAssistant customerAssistant,
                                   RedisChatMemoryStore chatMemoryStore,
                                   RedisIdWorker redisIdWorker) {
        this.imChatMapper = imChatMapper;
        this.chatMapper = chatMapper;
        this.messageMapper = messageMapper;
        this.customerAssistant = customerAssistant;
        this.chatMemoryStore = chatMemoryStore;
        this.redisIdWorker = redisIdWorker;
    }

    @Override
    public CustomerImChat createImChat(Long userId, String title) {
        LocalDateTime now = LocalDateTime.now();
        CustomerImChat imChat = new CustomerImChat()
                .setImChatId(redisIdWorker.nextId("im_chat"))
                .setUserId(userId)
                .setTitle(normalizeTitle(title))
                .setHandlerType(HANDLER_TYPE_BOT)
                .setStatus(IM_CHAT_STATUS_BOT_ACTIVE)
                .setLastMessageTime(now)
                .setCreateTime(now)
                .setUpdateTime(now);
        imChatMapper.insert(imChat);
        return imChat;
    }

    @Override
    public IPage<CustomerImChat> listImChats(Long userId, int pageNo, int pageSize) {
        Page<CustomerImChat> page = new Page<>(normalizePageNo(pageNo), normalizePageSize(pageSize));
        return imChatMapper.selectPage(page, new LambdaQueryWrapper<CustomerImChat>()
                .eq(CustomerImChat::getUserId, userId)
                .orderByDesc(CustomerImChat::getLastMessageTime)
                .orderByDesc(CustomerImChat::getImChatId));
    }

    @Override
    public CustomerImChat getImChat(Long userId, Long imChatId) {
        return requireOwnedImChat(userId, imChatId);
    }

    @Override
    @Transactional
    public CustomerChat createOrResumeChat(Long userId, Long imChatId) {
        CustomerImChat imChat = requireOwnedImChat(userId, imChatId);
        if (IM_CHAT_STATUS_CLOSED.equals(imChat.getStatus())) {
            throw new ChatBusinessException("长会话已关闭，请新建会话");
        }

        CustomerChat activeChat = chatMapper.selectOne(new LambdaQueryWrapper<CustomerChat>()
                .eq(CustomerChat::getImChatId, imChatId)
                .eq(CustomerChat::getUserId, userId)
                .eq(CustomerChat::getStatus, CHAT_STATUS_ACTIVE)
                .orderByDesc(CustomerChat::getStartTime)
                .last("LIMIT 1"));

        LocalDateTime now = LocalDateTime.now();
        if (activeChat != null && !isExpired(activeChat, now)) {
            return activeChat;
        }
        if (activeChat != null) {
            endChat(activeChat, now);
        }

        CustomerChat chat = new CustomerChat()
                .setChatId(redisIdWorker.nextId("chat"))
                .setImChatId(imChatId)
                .setUserId(userId)
                .setStatus(CHAT_STATUS_ACTIVE)
                .setStartTime(now)
                .setLastActiveTime(now);
        chatMapper.insert(chat);
        return chat;
    }

    @Override
    public ChatReplyDTO sendMessage(Long userId, ChatRequest request) {
        validateSendRequest(request);
        CustomerImChat imChat = requireOwnedImChat(userId, request.getImChatId());
        if (!IM_CHAT_STATUS_BOT_ACTIVE.equals(imChat.getStatus())) {
            throw new ChatBusinessException("当前长会话不处于机器人接待状态");
        }
        CustomerChat chat = requireActiveChat(userId, request.getImChatId(), request.getChatId());
        if (isExpired(chat, LocalDateTime.now())) {
            endChat(chat, LocalDateTime.now());
            throw new ChatBusinessException("短会话已超时，请重新进入该长会话");
        }

        CustomerChatMessage existingUserMessage = findUserMessage(userId, request.getClientMessageId());
        if (existingUserMessage != null) {
            return existingReply(existingUserMessage);
        }

        LocalDateTime now = LocalDateTime.now();
        CustomerChatMessage userMessage = new CustomerChatMessage()
                .setMessageId(redisIdWorker.nextId("chat_message"))
                .setImChatId(request.getImChatId())
                .setChatId(request.getChatId())
                .setUserId(userId)
                .setSenderType(SENDER_USER)
                .setMessageType(MESSAGE_TYPE_TEXT)
                .setContent(request.getMessage().trim())
                .setClientMessageId(request.getClientMessageId().trim())
                .setCreateTime(now);
        messageMapper.insert(userMessage);

        updateConversationActivity(imChat, chat, userMessage.getContent(), now);

        String reply = customerAssistant.chat(request.getChatId(), userMessage.getContent());
        LocalDateTime replyTime = LocalDateTime.now();
        CustomerChatMessage assistantMessage = new CustomerChatMessage()
                .setMessageId(redisIdWorker.nextId("chat_message"))
                .setImChatId(request.getImChatId())
                .setChatId(request.getChatId())
                .setUserId(userId)
                .setSenderType(SENDER_ASSISTANT)
                .setMessageType(MESSAGE_TYPE_TEXT)
                .setContent(reply)
                .setReplyToMessageId(userMessage.getMessageId())
                .setCreateTime(replyTime);
        messageMapper.insert(assistantMessage);
        updateActivityOnly(imChat, chat, replyTime);

        return toReply(userMessage, assistantMessage);
    }

    @Override
    public IPage<CustomerChatMessage> listMessages(Long userId, Long imChatId, int pageNo, int pageSize) {
        requireOwnedImChat(userId, imChatId);
        Page<CustomerChatMessage> page = new Page<>(normalizePageNo(pageNo), normalizePageSize(pageSize));
        return messageMapper.selectPage(page, new LambdaQueryWrapper<CustomerChatMessage>()
                .eq(CustomerChatMessage::getUserId, userId)
                .eq(CustomerChatMessage::getImChatId, imChatId)
                .orderByDesc(CustomerChatMessage::getMessageId));
    }

    @Override
    @Transactional
    public void closeImChat(Long userId, Long imChatId) {
        CustomerImChat imChat = requireOwnedImChat(userId, imChatId);
        if (IM_CHAT_STATUS_CLOSED.equals(imChat.getStatus())) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        List<CustomerChat> activeChats = chatMapper.selectList(new LambdaQueryWrapper<CustomerChat>()
                .eq(CustomerChat::getImChatId, imChatId)
                .eq(CustomerChat::getUserId, userId)
                .eq(CustomerChat::getStatus, CHAT_STATUS_ACTIVE));
        for (CustomerChat chat : activeChats) {
            endChat(chat, now);
            chatMemoryStore.deleteMessages(chat.getChatId());
        }
        imChat.setStatus(IM_CHAT_STATUS_CLOSED);
        imChat.setCloseTime(now);
        imChat.setUpdateTime(now);
        imChatMapper.updateById(imChat);
    }

    private CustomerImChat requireOwnedImChat(Long userId, Long imChatId) {
        if (imChatId == null) {
            throw new ChatBusinessException("imChatId不能为空");
        }
        CustomerImChat imChat = imChatMapper.selectOne(new LambdaQueryWrapper<CustomerImChat>()
                .eq(CustomerImChat::getImChatId, imChatId)
                .eq(CustomerImChat::getUserId, userId));
        if (imChat == null) {
            throw new ChatBusinessException("长会话不存在或无权访问");
        }
        return imChat;
    }

    private CustomerChat requireActiveChat(Long userId, Long imChatId, Long chatId) {
        if (chatId == null) {
            throw new ChatBusinessException("chatId不能为空");
        }
        CustomerChat chat = chatMapper.selectOne(new LambdaQueryWrapper<CustomerChat>()
                .eq(CustomerChat::getChatId, chatId)
                .eq(CustomerChat::getImChatId, imChatId)
                .eq(CustomerChat::getUserId, userId)
                .eq(CustomerChat::getStatus, CHAT_STATUS_ACTIVE));
        if (chat == null) {
            throw new ChatBusinessException("短会话不存在、已结束或无权访问");
        }
        return chat;
    }

    private CustomerChatMessage findUserMessage(Long userId, String clientMessageId) {
        return messageMapper.selectOne(new LambdaQueryWrapper<CustomerChatMessage>()
                .eq(CustomerChatMessage::getUserId, userId)
                .eq(CustomerChatMessage::getClientMessageId, clientMessageId)
                .eq(CustomerChatMessage::getSenderType, SENDER_USER)
                .last("LIMIT 1"));
    }

    private ChatReplyDTO existingReply(CustomerChatMessage userMessage) {
        CustomerChatMessage assistantMessage = messageMapper.selectOne(
                new LambdaQueryWrapper<CustomerChatMessage>()
                        .eq(CustomerChatMessage::getReplyToMessageId, userMessage.getMessageId())
                        .eq(CustomerChatMessage::getSenderType, SENDER_ASSISTANT)
                        .last("LIMIT 1"));
        if (assistantMessage == null) {
            throw new ChatBusinessException("该消息正在处理中，请稍后重试");
        }
        return toReply(userMessage, assistantMessage);
    }

    private void updateConversationActivity(CustomerImChat imChat, CustomerChat chat,
                                            String firstMessageCandidate, LocalDateTime now) {
        if (DEFAULT_IM_CHAT_TITLE.equals(imChat.getTitle())) {
            imChat.setTitle(abbreviate(firstMessageCandidate, 30));
        }
        updateActivityOnly(imChat, chat, now);
    }

    private void updateActivityOnly(CustomerImChat imChat, CustomerChat chat, LocalDateTime now) {
        chat.setLastActiveTime(now);
        chatMapper.updateById(chat);
        imChat.setLastMessageTime(now);
        imChat.setUpdateTime(now);
        imChatMapper.updateById(imChat);
    }

    private void endChat(CustomerChat chat, LocalDateTime now) {
        chat.setStatus(CHAT_STATUS_ENDED);
        chat.setEndTime(now);
        chat.setLastActiveTime(now);
        chatMapper.updateById(chat);
    }

    private boolean isExpired(CustomerChat chat, LocalDateTime now) {
        LocalDateTime lastActiveTime = chat.getLastActiveTime() == null
                ? chat.getStartTime() : chat.getLastActiveTime();
        return lastActiveTime != null && lastActiveTime.plusMinutes(CHAT_INACTIVE_MINUTES).isBefore(now);
    }

    private void validateSendRequest(ChatRequest request) {
        if (request == null) {
            throw new ChatBusinessException("请求不能为空");
        }
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new ChatBusinessException("消息不能为空");
        }
        if (request.getClientMessageId() == null || request.getClientMessageId().isBlank()) {
            throw new ChatBusinessException("clientMessageId不能为空");
        }
    }

    private int normalizePageNo(int pageNo) {
        return Math.max(pageNo, 1);
    }

    private int normalizePageSize(int pageSize) {
        if (pageSize <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    private String normalizeTitle(String title) {
        if (title == null || title.isBlank()) {
            return DEFAULT_IM_CHAT_TITLE;
        }
        return abbreviate(title.trim(), 64);
    }

    private String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private ChatReplyDTO toReply(CustomerChatMessage userMessage, CustomerChatMessage assistantMessage) {
        ChatReplyDTO reply = new ChatReplyDTO();
        reply.setImChatId(userMessage.getImChatId());
        reply.setChatId(userMessage.getChatId());
        reply.setUserMessageId(userMessage.getMessageId());
        reply.setAssistantMessageId(assistantMessage.getMessageId());
        reply.setReply(assistantMessage.getContent());
        return reply;
    }
}
