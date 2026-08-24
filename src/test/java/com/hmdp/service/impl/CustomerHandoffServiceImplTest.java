package com.hmdp.service.impl;

import com.hmdp.entity.CustomerChat;
import com.hmdp.entity.CustomerChatBizRef;
import com.hmdp.entity.CustomerHandoff;
import com.hmdp.entity.CustomerImChat;
import com.hmdp.mapper.CustomerChatBizRefMapper;
import com.hmdp.mapper.CustomerChatMapper;
import com.hmdp.mapper.CustomerChatMessageMapper;
import com.hmdp.mapper.CustomerHandoffMapper;
import com.hmdp.mapper.CustomerImChatMapper;
import com.hmdp.service.IConversationMemoryService;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;

import static com.hmdp.utils.CustomerChatConstants.CHAT_STATUS_ACTIVE;
import static com.hmdp.utils.CustomerChatConstants.HANDOFF_STATUS_ACCEPTED;
import static com.hmdp.utils.CustomerChatConstants.HANDOFF_STATUS_PENDING;
import static com.hmdp.utils.CustomerChatConstants.IM_CHAT_STATUS_BOT_ACTIVE;
import static com.hmdp.utils.CustomerChatConstants.IM_CHAT_STATUS_HUMAN_ACTIVE;
import static com.hmdp.utils.CustomerChatConstants.IM_CHAT_STATUS_HUMAN_PENDING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CustomerHandoffServiceImplTest {

    @Mock
    private CustomerHandoffMapper handoffMapper;
    @Mock
    private CustomerImChatMapper imChatMapper;
    @Mock
    private CustomerChatMapper chatMapper;
    @Mock
    private CustomerChatMessageMapper messageMapper;
    @Mock
    private CustomerChatBizRefMapper bizRefMapper;
    @Mock
    private IConversationMemoryService conversationMemoryService;
    @Mock
    private RedisIdWorker redisIdWorker;

    private CustomerHandoffServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CustomerHandoffServiceImpl(
                handoffMapper, imChatMapper, chatMapper, messageMapper,
                bizRefMapper, conversationMemoryService, redisIdWorker);
    }

    @Test
    void shouldFinalizeBotChatAndCreateHumanChatWithSummary() {
        LocalDateTime now = LocalDateTime.now();
        CustomerImChat imChat = new CustomerImChat()
                .setImChatId(1001L)
                .setUserId(7L)
                .setStatus(IM_CHAT_STATUS_BOT_ACTIVE)
                .setSummary("用户咨询订单9001退款");
        CustomerChat botChat = new CustomerChat()
                .setChatId(2001L)
                .setImChatId(1001L)
                .setUserId(7L)
                .setStatus(CHAT_STATUS_ACTIVE)
                .setStartTime(now)
                .setLastActiveTime(now);
        CustomerChatBizRef orderRef = new CustomerChatBizRef()
                .setBizType("VOUCHER_ORDER")
                .setBizId(9001L);

        when(imChatMapper.selectOne(any())).thenReturn(imChat);
        when(handoffMapper.selectOne(any())).thenReturn(null);
        when(chatMapper.selectOne(any())).thenReturn(botChat);
        when(bizRefMapper.selectList(any())).thenReturn(Collections.singletonList(orderRef));
        when(redisIdWorker.nextId("chat")).thenReturn(2002L);
        when(redisIdWorker.nextId("handoff")).thenReturn(4001L);

        CustomerHandoff result = service.requestHandoff(
                7L, 1001L, 2001L, "需要人工处理退款");

        assertEquals(4001L, result.getHandoffId());
        assertEquals(2002L, result.getHumanChatId());
        assertEquals(HANDOFF_STATUS_PENDING, result.getStatus());
        assertEquals("用户咨询订单9001退款", result.getSummary());
        assertEquals("VOUCHER_ORDER:9001", result.getBusinessRefs());
        assertEquals(IM_CHAT_STATUS_HUMAN_PENDING, imChat.getStatus());
        verify(conversationMemoryService).finalizeChat(any(), any(), any());
        verify(chatMapper).insert(any(CustomerChat.class));
        verify(handoffMapper).insert(result);
    }

    @Test
    void shouldAtomicallyAcceptPendingHandoffAndPauseBot() {
        CustomerHandoff handoff = new CustomerHandoff()
                .setHandoffId(4001L)
                .setImChatId(1001L)
                .setStatus(HANDOFF_STATUS_PENDING);
        CustomerImChat imChat = new CustomerImChat()
                .setImChatId(1001L)
                .setStatus(IM_CHAT_STATUS_HUMAN_PENDING);

        when(handoffMapper.selectById(4001L)).thenReturn(handoff);
        when(handoffMapper.update(any(), any())).thenReturn(1);
        when(imChatMapper.selectById(1001L)).thenReturn(imChat);

        CustomerHandoff result = service.accept(4001L, "operator-1");

        assertEquals(HANDOFF_STATUS_ACCEPTED, result.getStatus());
        assertEquals("operator-1", result.getOperatorId());
        assertEquals(IM_CHAT_STATUS_HUMAN_ACTIVE, imChat.getStatus());
        assertEquals("HUMAN", imChat.getHandlerType());
        verify(imChatMapper).updateById(imChat);
    }
}
