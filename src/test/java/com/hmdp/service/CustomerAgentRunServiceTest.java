package com.hmdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.agent.AgentRunResponseDTO;
import com.hmdp.dto.agent.AgentUsageDTO;
import com.hmdp.entity.CustomerAgentRun;
import com.hmdp.entity.CustomerChat;
import com.hmdp.entity.CustomerChatMessage;
import com.hmdp.mapper.CustomerAgentRunMapper;
import com.hmdp.mapper.CustomerChatMapper;
import com.hmdp.mapper.CustomerChatMessageMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CustomerAgentRunServiceTest {
    @Mock
    private CustomerAgentRunMapper runMapper;
    @Mock
    private CustomerChatMessageMapper messageMapper;
    @Mock
    private CustomerChatMapper chatMapper;

    private CustomerAgentRunService service() {
        return new CustomerAgentRunService(runMapper, messageMapper, chatMapper, new ObjectMapper());
    }

    @Test
    void shouldCreateAuditablePendingRun() {
        CustomerAgentRunService service = service();
        CustomerChatMessage message = new CustomerChatMessage()
                .setMessageId(3001L)
                .setImChatId(1001L)
                .setChatId(2001L)
                .setUserId(7L);

        CustomerAgentRun run = service.createPending(message);

        assertEquals("3001", run.getRequestId());
        assertEquals(CustomerAgentRunService.PENDING, run.getStatus());
        assertFalse(run.getRetryable());
        assertEquals("v2", run.getGraphVersion());
        verify(runMapper).insert(run);
    }

    @Test
    void shouldPersistReplyBeforeMarkingRunSucceeded() {
        CustomerAgentRunService service = service();
        CustomerChatMessage reply = new CustomerChatMessage().setMessageId(4001L);
        CustomerChat chat = new CustomerChat().setChatId(2001L).setActiveAgent("general_support_agent");
        AgentUsageDTO usage = new AgentUsageDTO();
        usage.setPromptTokens(20);
        usage.setCompletionTokens(8);
        AgentRunResponseDTO response = new AgentRunResponseDTO();
        response.setTraceId("trace-1");
        response.setGraphVersion("v2");
        response.setActiveAgent("general_support_agent");
        response.setRouteHistory(java.util.Collections.singletonList(
                java.util.Collections.<String, Object>singletonMap("agent", "transaction_agent")));
        response.setHandoffCount(1);
        response.setModelCallCount(3);
        response.setToolCallCount(1);
        response.setUsage(usage);

        service.completeSuccess("run-1", response, reply, chat);

        verify(messageMapper).insert(reply);
        verify(chatMapper).updateById(chat);
        ArgumentCaptor<CustomerAgentRun> captor = ArgumentCaptor.forClass(CustomerAgentRun.class);
        verify(runMapper).updateById(captor.capture());
        assertEquals(CustomerAgentRunService.SUCCEEDED, captor.getValue().getStatus());
        assertEquals("trace-1", captor.getValue().getTraceId());
        assertEquals("transaction_agent", captor.getValue().getEntryAgent());
        assertEquals("general_support_agent", captor.getValue().getFinalAgent());
        assertEquals(20, captor.getValue().getPromptTokens());
        assertTrue(captor.getValue().getRouteHistory().contains("transaction_agent"));
    }

    @Test
    void shouldPersistUserMessageAndPendingRunInOneServiceTransaction() {
        CustomerAgentRunService service = service();
        CustomerChatMessage message = new CustomerChatMessage()
                .setMessageId(3002L).setImChatId(1001L).setChatId(2001L).setUserId(7L);

        service.createPendingWithUserMessage(message);

        verify(messageMapper).insert(message);
        verify(runMapper).insert(any(CustomerAgentRun.class));
    }
}
