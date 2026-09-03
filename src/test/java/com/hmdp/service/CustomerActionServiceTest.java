package com.hmdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.agent.AgentActionProposalDTO;
import com.hmdp.dto.agent.AgentRunResponseDTO;
import com.hmdp.dto.tool.BusinessReferenceDTO;
import com.hmdp.entity.CustomerActionRequest;
import com.hmdp.entity.CustomerAgentRun;
import com.hmdp.entity.CustomerChatMessage;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.CustomerActionEventMapper;
import com.hmdp.mapper.CustomerActionRequestMapper;
import com.hmdp.mapper.CustomerChatMessageMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerActionServiceTest {
    @Mock CustomerActionRequestMapper requestMapper;
    @Mock CustomerActionEventMapper eventMapper;
    @Mock CustomerChatMessageMapper messageMapper;
    @Mock VoucherOrderMapper orderMapper;
    CustomerActionService service;

    @BeforeEach
    void setUp() {
        service = new CustomerActionService(requestMapper, eventMapper, messageMapper,
                orderMapper, new ObjectMapper(), true, true, 600);
    }

    @Test
    void shouldUseExactContextualConfirmationPhrases() {
        assertEquals(CustomerActionService.ConfirmationDecision.CONFIRM, service.classify("确认。"));
        assertEquals(CustomerActionService.ConfirmationDecision.DECLINE, service.classify("算了"));
        assertEquals(CustomerActionService.ConfirmationDecision.NONE, service.classify("取消"));
        assertEquals(CustomerActionService.ConfirmationDecision.NONE, service.classify("确认后还要多久"));
    }

    @Test
    void shouldPrepareOnlyOwnedEligibleOrder() {
        VoucherOrder order = new VoucherOrder().setId(9001L).setUserId(7L).setStatus(1);
        when(requestMapper.selectOne(any())).thenReturn(null);
        when(orderMapper.selectOne(any())).thenReturn(order);
        AgentActionProposalDTO proposal = new AgentActionProposalDTO();
        proposal.setActionType(CustomerActionService.CANCEL_UNPAID_ORDER);
        proposal.setOrderId(9001L);
        AgentRunResponseDTO response = new AgentRunResponseDTO();
        response.setRunId("agent-execution");
        response.setActionProposal(proposal);
        response.setBusinessRefs(Collections.singletonList(new BusinessReferenceDTO("VOUCHER_ORDER", 9001L)));
        CustomerAgentRun run = new CustomerAgentRun().setRunId("run-1").setRequestId("3001");
        CustomerChatMessage message = message().setContent("请取消订单9001");

        CustomerActionService.PreparationResult result = service.prepare(run, message, response);

        assertTrue(result.isCreated());
        assertEquals(CustomerActionService.AWAITING_CONFIRMATION, result.getAction().getStatus());
        assertEquals(9001L, result.getAction().getTargetBizId());
        verify(requestMapper).insert(any(CustomerActionRequest.class));
        verify(eventMapper).insert(any());
    }

    @Test
    void shouldExecuteCancellationOnceAfterConfirmation() {
        CustomerActionRequest action = action();
        VoucherOrder order = new VoucherOrder().setId(9001L).setUserId(7L).setStatus(1);
        when(requestMapper.claimForExecution("action-1", 7L)).thenReturn(1);
        when(requestMapper.selectById("action-1")).thenReturn(action);
        when(orderMapper.selectOne(any())).thenReturn(order);
        when(orderMapper.cancelUnpaidForCustomer(9001L, 7L)).thenReturn(1);

        CustomerActionService.ExecutionResult result = service.executeMessage(
                message(), action, CustomerActionService.ConfirmationDecision.CONFIRM);

        assertEquals("EXECUTION_SUCCEEDED", result.getResumeType());
        assertEquals("ORDER_CANCELLED", result.getOutcome().getResultCode());
        verify(orderMapper, times(1)).cancelUnpaidForCustomer(9001L, 7L);
        verify(messageMapper).insert(any(CustomerChatMessage.class));
        verify(requestMapper).finish("action-1", CustomerActionService.SUCCEEDED,
                "ORDER_CANCELLED", "订单已取消。", null);
    }

    @Test
    void shouldReturnPersistedOutcomeWhenDeclineLosesConcurrentRace() {
        CustomerActionRequest pending = action();
        CustomerActionRequest completed = action().setStatus(CustomerActionService.SUCCEEDED)
                .setResultCode("ORDER_CANCELLED").setResultPayload("订单已取消。");
        when(requestMapper.decline("action-1", 7L)).thenReturn(0);
        when(requestMapper.selectById("action-1")).thenReturn(completed);

        CustomerActionService.ExecutionResult result = service.executeMessage(
                message(), pending, CustomerActionService.ConfirmationDecision.DECLINE);

        assertEquals("EXECUTION_SUCCEEDED", result.getResumeType());
        assertEquals("ORDER_CANCELLED", result.getOutcome().getResultCode());
        assertEquals("订单已取消。", result.getOutcome().getMessage());
        verify(eventMapper, never()).insert(any());
    }

    private CustomerChatMessage message() {
        return new CustomerChatMessage().setMessageId(3002L).setClientMessageId("client-confirm")
                .setUserId(7L).setImChatId(1001L).setChatId(2001L).setContent("确认");
    }

    private CustomerActionRequest action() {
        return new CustomerActionRequest().setActionRequestId("action-1")
                .setOriginalRunId("run-1").setAgentExecutionId("agent-execution")
                .setRequestId("3001").setUserId(7L).setImChatId(1001L).setChatId(2001L)
                .setActionType(CustomerActionService.CANCEL_UNPAID_ORDER)
                .setTargetBizType("VOUCHER_ORDER").setTargetBizId(9001L)
                .setStatus(CustomerActionService.AWAITING_CONFIRMATION).setActiveImChatId(1001L)
                .setExpiresTime(LocalDateTime.now().plusMinutes(5));
    }
}
