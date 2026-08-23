package com.hmdp.service.impl;

import com.hmdp.entity.CustomerChat;
import com.hmdp.entity.CustomerChatMessage;
import com.hmdp.entity.CustomerImChat;
import com.hmdp.mapper.CustomerChatMapper;
import com.hmdp.mapper.CustomerChatMessageMapper;
import com.hmdp.mapper.CustomerImChatMapper;
import com.hmdp.service.ConversationSummarizer;
import com.hmdp.utils.RedisChatMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Arrays;

import static com.hmdp.utils.CustomerChatConstants.CHAT_STATUS_ACTIVE;
import static com.hmdp.utils.CustomerChatConstants.CHAT_STATUS_ENDED;
import static com.hmdp.utils.CustomerChatConstants.SENDER_ASSISTANT;
import static com.hmdp.utils.CustomerChatConstants.SENDER_USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConversationMemoryServiceImplTest {

    @Mock
    private CustomerChatMapper chatMapper;
    @Mock
    private CustomerChatMessageMapper messageMapper;
    @Mock
    private CustomerImChatMapper imChatMapper;
    @Mock
    private ConversationSummarizer summarizer;
    @Mock
    private RedisChatMemoryStore chatMemoryStore;

    private ConversationMemoryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new ConversationMemoryServiceImpl(
                chatMapper, messageMapper, imChatMapper, summarizer, chatMemoryStore);
    }

    @Test
    void shouldSummarizeShortChatAndMergeLongTermMemory() {
        LocalDateTime now = LocalDateTime.now();
        CustomerImChat imChat = new CustomerImChat()
                .setImChatId(1001L)
                .setUserId(7L)
                .setSummary("用户正在咨询订单");
        CustomerChat chat = new CustomerChat()
                .setChatId(2001L)
                .setImChatId(1001L)
                .setUserId(7L)
                .setStatus(CHAT_STATUS_ACTIVE)
                .setStartTime(now.minusMinutes(10))
                .setLastActiveTime(now.minusMinutes(1));
        CustomerChatMessage userMessage = new CustomerChatMessage()
                .setMessageId(3001L)
                .setSenderType(SENDER_USER)
                .setContent("订单123可以退款吗");
        CustomerChatMessage assistantMessage = new CustomerChatMessage()
                .setMessageId(3002L)
                .setSenderType(SENDER_ASSISTANT)
                .setContent("订单符合退款条件");

        when(messageMapper.selectList(any())).thenReturn(Arrays.asList(assistantMessage, userMessage));
        when(summarizer.summarizeSession(anyList())).thenReturn("用户询问订单123退款，已确认符合条件");
        when(summarizer.mergeLongTerm("用户正在咨询订单", "用户询问订单123退款，已确认符合条件"))
                .thenReturn("用户咨询订单123退款，已确认符合条件，尚未申请");

        service.finalizeChat(imChat, chat, now);

        assertEquals(CHAT_STATUS_ENDED, chat.getStatus());
        assertEquals("用户询问订单123退款，已确认符合条件", chat.getSummary());
        assertEquals("用户咨询订单123退款，已确认符合条件，尚未申请", imChat.getSummary());
        verify(chatMapper, org.mockito.Mockito.times(2)).updateById(chat);
        verify(imChatMapper).updateById(imChat);
        verify(chatMemoryStore).deleteMessages(2001L);
    }

    @Test
    void shouldReturnPlaceholderWhenLongTermMemoryIsEmpty() {
        assertEquals("暂无长期会话记忆", service.getLongTermMemory(new CustomerImChat()));
    }
}
