package com.hmdp.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.ChatReplyDTO;
import com.hmdp.dto.ChatRequest;
import com.hmdp.dto.agent.AgentRunResponseDTO;
import com.hmdp.entity.CustomerAgentRun;
import com.hmdp.entity.CustomerChat;
import com.hmdp.entity.CustomerChatMessage;
import com.hmdp.entity.CustomerImChat;
import com.hmdp.mapper.CustomerChatMapper;
import com.hmdp.mapper.CustomerChatMessageMapper;
import com.hmdp.mapper.CustomerImChatMapper;
import com.hmdp.security.AgentToolTokenService;
import com.hmdp.service.CustomerAgentClient;
import com.hmdp.service.CustomerAgentRunService;
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
import static com.hmdp.utils.CustomerChatConstants.IM_CHAT_STATUS_HUMAN_PENDING;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
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
    private CustomerAgentClient customerAgentClient;
    @Mock
    private CustomerAgentRunService agentRunService;
    @Mock
    private AgentToolTokenService tokenService;
    @Mock
    private IConversationMemoryService conversationMemoryService;
    @Mock
    private RedisIdWorker redisIdWorker;

    private CustomerChatServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new CustomerChatServiceImpl(
                imChatMapper, chatMapper, messageMapper,
                customerAgentClient, agentRunService, tokenService,
                conversationMemoryService, redisIdWorker, new ObjectMapper());
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
    void shouldUseChatIdAsAgentThreadAndPersistBothMessages() {
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
                .setActiveAgent("general_support_agent")
                .setStartTime(now)
                .setLastActiveTime(now);

        when(imChatMapper.selectOne(any())).thenReturn(imChat);
        when(chatMapper.selectOne(any())).thenReturn(chat);
        when(messageMapper.selectOne(any())).thenReturn(null);
        when(messageMapper.selectList(any())).thenReturn(java.util.Collections.singletonList(
                new CustomerChatMessage().setMessageId(3001L).setSenderType("USER").setContent("查询订单")));
        when(redisIdWorker.nextId("chat_message")).thenReturn(3001L, 3002L);
        when(conversationMemoryService.getLongTermMemory(imChat)).thenReturn("用户此前咨询过该订单");
        CustomerAgentRun run = new CustomerAgentRun().setRunId("run-1").setRequestId("3001");
        when(agentRunService.createPendingWithUserMessage(any())).thenReturn(run);
        when(agentRunService.claim("run-1")).thenReturn(true);
        when(tokenService.issue(any(), any())).thenReturn("tool-token");
        AgentRunResponseDTO agentResponse = new AgentRunResponseDTO();
        agentResponse.setReply("已为您查询");
        agentResponse.setIntent("GENERAL");
        agentResponse.setActiveAgent("transaction_agent");
        agentResponse.setGraphVersion("v2");
        agentResponse.setTraceId("trace-1");
        when(customerAgentClient.invoke(any())).thenReturn(agentResponse);

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
        verify(customerAgentClient).invoke(org.mockito.ArgumentMatchers.argThat(agentRequest ->
                String.valueOf(chatId).equals(agentRequest.getThreadId())
                        && "用户此前咨询过该订单".equals(agentRequest.getLongTermSummary())
                        && "general_support_agent".equals(agentRequest.getPreviousActiveAgent())
                        && "v2".equals(agentRequest.getGraphVersion())
                        && agentRequest.getToolAccessTokens() != null
                        && "tool-token".equals(agentRequest.getToolAccessTokens().getTransactionAgentToken())
                        && "tool-token".equals(agentRequest.getToolAccessTokens().getDiscoveryAgentToken())));
        verify(agentRunService).completeSuccess(org.mockito.Mockito.eq("run-1"),
                org.mockito.Mockito.eq(agentResponse), any(CustomerChatMessage.class),
                org.mockito.Mockito.argThat(updatedChat ->
                        "transaction_agent".equals(updatedChat.getActiveAgent())));
        verify(tokenService).issue(any(), org.mockito.Mockito.eq(com.hmdp.security.AgentToolScopes.transactionScopes()));
        verify(tokenService).issue(any(), org.mockito.Mockito.eq(com.hmdp.security.AgentToolScopes.discoveryScopes()));
        verify(tokenService).issue(any(), org.mockito.Mockito.eq(com.hmdp.security.AgentToolScopes.allReadScopes()));
        verify(agentRunService).createPendingWithUserMessage(any(CustomerChatMessage.class));
    }

    @Test
    void shouldPersistUserMessageWithoutCallingModelDuringHumanHandoff() {
        Long userId = 7L;
        Long imChatId = 1001L;
        Long chatId = 2002L;
        LocalDateTime now = LocalDateTime.now();
        CustomerImChat imChat = new CustomerImChat()
                .setImChatId(imChatId)
                .setUserId(userId)
                .setTitle("人工咨询")
                .setHandlerType("HUMAN")
                .setStatus(IM_CHAT_STATUS_HUMAN_PENDING)
                .setLastMessageTime(now);
        CustomerChat chat = new CustomerChat()
                .setChatId(chatId)
                .setImChatId(imChatId)
                .setUserId(userId)
                .setStatus(CHAT_STATUS_ACTIVE)
                .setStartTime(now.minusHours(1))
                .setLastActiveTime(now.minusHours(1));

        when(imChatMapper.selectOne(any())).thenReturn(imChat);
        when(chatMapper.selectOne(any())).thenReturn(chat);
        when(messageMapper.selectOne(any())).thenReturn(null);
        when(redisIdWorker.nextId("chat_message")).thenReturn(3003L);

        ChatRequest request = new ChatRequest();
        request.setImChatId(imChatId);
        request.setChatId(chatId);
        request.setClientMessageId("client-human-1");
        request.setMessage("我补充一张消费凭证");

        ChatReplyDTO result = service.sendMessage(userId, request);

        assertEquals(IM_CHAT_STATUS_HUMAN_PENDING, result.getConversationStatus());
        assertEquals("HUMAN", result.getHandlerType());
        assertEquals(null, result.getAssistantMessageId());
        verify(messageMapper).insert(any(CustomerChatMessage.class));
        verifyNoInteractions(customerAgentClient, agentRunService, tokenService);
    }
}
