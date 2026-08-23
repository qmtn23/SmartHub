package com.hmdp.service.impl;

import com.hmdp.dto.ChatReplyDTO;
import com.hmdp.dto.ChatRequest;
import com.hmdp.entity.CustomerChat;
import com.hmdp.entity.CustomerChatMessage;
import com.hmdp.entity.CustomerImChat;
import com.hmdp.mapper.CustomerChatMapper;
import com.hmdp.mapper.CustomerChatMessageMapper;
import com.hmdp.mapper.CustomerImChatMapper;
import com.hmdp.service.CustomerAssistant;
import com.hmdp.service.IConversationMemoryService;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static com.hmdp.utils.CustomerChatConstants.CHAT_STATUS_ACTIVE;
import static com.hmdp.utils.CustomerChatConstants.IM_CHAT_STATUS_BOT_ACTIVE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerChatServiceImplTest {

    @Mock
    private CustomerImChatMapper imChatMapper;
    @Mock
    private CustomerChatMapper chatMapper;
    @Mock
    private CustomerChatMessageMapper messageMapper;
    @Mock
    private CustomerAssistant customerAssistant;
    @Mock
    private IConversationMemoryService conversationMemoryService;
    @Mock
    private RedisIdWorker redisIdWorker;

    private CustomerChatServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CustomerChatServiceImpl(
                imChatMapper, chatMapper, messageMapper,
                customerAssistant, conversationMemoryService, redisIdWorker);
    }

    @Test
    void shouldCreateLongConversationForCurrentUser() {
        when(redisIdWorker.nextId("im_chat")).thenReturn(101L);

        CustomerImChat result = service.createImChat(7L, "退款咨询");

        assertEquals(101L, result.getImChatId());
        assertEquals(7L, result.getUserId());
        assertEquals("退款咨询", result.getTitle());
        assertEquals(IM_CHAT_STATUS_BOT_ACTIVE, result.getStatus());
        assertNotNull(result.getCreateTime());
        verify(imChatMapper).insert(result);
    }

    @Test
    void shouldUseChatIdAsModelMemoryIdAndPersistBothMessages() {
        Long userId = 7L;
        Long imChatId = 1001L;
        Long chatId = 2001L;
        LocalDateTime now = LocalDateTime.now();

        CustomerImChat imChat = new CustomerImChat()
                .setImChatId(imChatId)
                .setUserId(userId)
                .setTitle("订单问题")
                .setStatus(IM_CHAT_STATUS_BOT_ACTIVE)
                .setLastMessageTime(now)
                .setCreateTime(now)
                .setUpdateTime(now);
        CustomerChat chat = new CustomerChat()
                .setChatId(chatId)
                .setImChatId(imChatId)
                .setUserId(userId)
                .setStatus(CHAT_STATUS_ACTIVE)
                .setStartTime(now)
                .setLastActiveTime(now);

        when(imChatMapper.selectOne(any())).thenReturn(imChat);
        when(chatMapper.selectOne(any())).thenReturn(chat);
        when(messageMapper.selectOne(any())).thenReturn(null);
        when(redisIdWorker.nextId("chat_message")).thenReturn(3001L, 3002L);
        when(conversationMemoryService.getLongTermMemory(imChat)).thenReturn("用户此前咨询过该订单");
        when(customerAssistant.chat(chatId, "用户此前咨询过该订单", "查询订单")).thenReturn("已为您查询");

        ChatRequest request = new ChatRequest();
        request.setImChatId(imChatId);
        request.setChatId(chatId);
        request.setClientMessageId("client-1");
        request.setMessage("查询订单");

        ChatReplyDTO result = service.sendMessage(userId, request);

        assertEquals(chatId, result.getChatId());
        assertEquals(3001L, result.getUserMessageId());
        assertEquals(3002L, result.getAssistantMessageId());
        assertEquals("已为您查询", result.getReply());
        verify(customerAssistant).chat(chatId, "用户此前咨询过该订单", "查询订单");
        verify(messageMapper, org.mockito.Mockito.times(2)).insert(any(CustomerChatMessage.class));
    }
}
