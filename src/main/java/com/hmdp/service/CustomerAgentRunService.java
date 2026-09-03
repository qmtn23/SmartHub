package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.agent.AgentRunResponseDTO;
import com.hmdp.entity.CustomerAgentRun;
import com.hmdp.entity.CustomerChat;
import com.hmdp.entity.CustomerChatMessage;
import com.hmdp.mapper.CustomerAgentRunMapper;
import com.hmdp.mapper.CustomerChatMapper;
import com.hmdp.mapper.CustomerChatMessageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.Map;

@Service
public class CustomerAgentRunService {
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED_RETRYABLE = "FAILED_RETRYABLE";
    public static final String FAILED_FINAL = "FAILED_FINAL";
    public static final String AWAITING_CONFIRMATION = "AWAITING_CONFIRMATION";
    public static final String RESUMING = "RESUMING";

    private final CustomerAgentRunMapper runMapper;
    private final CustomerChatMessageMapper messageMapper;
    private final CustomerChatMapper chatMapper;
    private final ObjectMapper objectMapper;

    public CustomerAgentRunService(CustomerAgentRunMapper runMapper,
                                   CustomerChatMessageMapper messageMapper,
                                   CustomerChatMapper chatMapper,
                                   ObjectMapper objectMapper) {
        this.runMapper = runMapper;
        this.messageMapper = messageMapper;
        this.chatMapper = chatMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public CustomerAgentRun createPending(CustomerChatMessage userMessage) {
        return insertPending(userMessage);
    }

    @Transactional
    public CustomerAgentRun createPendingWithUserMessage(CustomerChatMessage userMessage) {
        messageMapper.insert(userMessage);
        return insertPending(userMessage);
    }

    private CustomerAgentRun insertPending(CustomerChatMessage userMessage) {
        LocalDateTime now = LocalDateTime.now();
        CustomerAgentRun run = new CustomerAgentRun()
                .setRunId(UUID.randomUUID().toString().replace("-", ""))
                .setRequestId(String.valueOf(userMessage.getMessageId()))
                .setUserMessageId(userMessage.getMessageId())
                .setImChatId(userMessage.getImChatId())
                .setChatId(userMessage.getChatId())
                .setUserId(userMessage.getUserId())
                .setStatus(PENDING)
                .setGraphVersion("v4")
                .setRetryable(false)
                .setAttemptCount(0)
                .setUpdateTime(now);
        runMapper.insert(run);
        return run;
    }

    public CustomerAgentRun findByUserMessageId(Long userMessageId) {
        return runMapper.selectOne(new LambdaQueryWrapper<CustomerAgentRun>()
                .eq(CustomerAgentRun::getUserMessageId, userMessageId)
                .last("LIMIT 1"));
    }

    public boolean claim(String runId) {
        return runMapper.claimForExecution(runId, LocalDateTime.now().minusSeconds(60)) == 1;
    }

    public CustomerAgentRun findById(String runId) {
        return runMapper.selectById(runId);
    }

    public boolean claimForResume(String runId) {
        return runMapper.claimForResume(runId) == 1;
    }

    @Transactional
    public void completeAwaiting(String runId, AgentRunResponseDTO response,
                                 CustomerChatMessage assistantMessage, CustomerChat chat,
                                 String actionRequestId, String actionType) {
        messageMapper.insert(assistantMessage);
        chatMapper.updateById(chat);
        CustomerAgentRun update = auditUpdate(runId, response)
                .setStatus(AWAITING_CONFIRMATION)
                .setRetryable(false)
                .setActionRequestId(actionRequestId)
                .setActionType(actionType)
                .setActionStatus(AWAITING_CONFIRMATION)
                .setInterruptReason("USER_CONFIRMATION_REQUIRED")
                .setFinishedTime(null)
                .setUpdateTime(LocalDateTime.now());
        runMapper.updateById(update);
    }

    @Transactional
    public void completeSuccess(String runId, AgentRunResponseDTO response,
                                CustomerChatMessage assistantMessage, CustomerChat chat) {
        messageMapper.insert(assistantMessage);
        chatMapper.updateById(chat);
        CustomerAgentRun update = auditUpdate(runId, response)
                .setStatus(SUCCEEDED)
                .setRetryable(false)
                .setFinishedTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        runMapper.updateById(update);
    }

    @Transactional
    public void completeResumed(String runId, AgentRunResponseDTO response,
                                CustomerChatMessage assistantMessage, CustomerChat chat,
                                String actionStatus) {
        messageMapper.insert(assistantMessage);
        chatMapper.updateById(chat);
        CustomerAgentRun update = auditUpdate(runId, response)
                .setStatus(SUCCEEDED).setRetryable(false).setActionStatus(actionStatus)
                .setFinishedTime(LocalDateTime.now()).setUpdateTime(LocalDateTime.now());
        runMapper.updateById(update);
    }

    private CustomerAgentRun auditUpdate(String runId, AgentRunResponseDTO response) {
        return new CustomerAgentRun()
                .setRunId(runId)
                .setTraceId(response.getTraceId())
                .setGraphVersion(defaultString(response.getGraphVersion(), "v4"))
                .setEntryAgent(entryAgent(response)).setFinalAgent(response.getActiveAgent())
                .setRouteHistory(toJson(response.getRouteHistory()))
                .setHandoffCount(defaultInt(response.getHandoffCount()))
                .setModelCallCount(defaultInt(response.getModelCallCount()))
                .setToolCallCount(defaultInt(response.getToolCallCount()))
                .setPromptTokens(response.getUsage() == null ? 0 : defaultInt(response.getUsage().getPromptTokens()))
                .setCompletionTokens(response.getUsage() == null ? 0 : defaultInt(response.getUsage().getCompletionTokens()))
                .setExecutionMode(defaultString(response.getExecutionMode(), "SIMPLE"))
                .setPlanId(response.getPlanId())
                .setSupervisorIterations(defaultInt(response.getSupervisorIterations()))
                .setParallelTaskCount(defaultInt(response.getParallelTaskCount()))
                .setTaskOutcomes(toJson(response.getTaskOutcomes()))
                .setOrchestrator(defaultString(response.getOrchestrator(), "router"))
                .setResolutionType(defaultString(response.getResolutionType(), "RESPONSE_ONLY"))
                .setHandoffReasonCode(response.getHandoffProposal() == null
                        ? null : response.getHandoffProposal().getReasonCode());
    }

    private String entryAgent(AgentRunResponseDTO response) {
        if (response.getRouteHistory() == null || response.getRouteHistory().isEmpty()) {
            return response.getActiveAgent();
        }
        return response.getRouteHistory().stream()
                .map(item -> item.get("agent"))
                .filter(value -> value != null && !String.valueOf(value).isBlank())
                .map(String::valueOf)
                .findFirst()
                .orElse(response.getActiveAgent());
    }

    private String toJson(Object value) {
        if (value == null) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("无法序列化Agent路由审计", e);
        }
    }

    private int defaultInt(Integer value) {
        return value == null ? 0 : value;
    }

    private String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public void completeFailure(String runId, String errorCode, boolean retryable) {
        CustomerAgentRun update = new CustomerAgentRun()
                .setRunId(runId)
                .setStatus(retryable ? FAILED_RETRYABLE : FAILED_FINAL)
                .setRetryable(retryable)
                .setErrorCode(errorCode)
                .setFinishedTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        runMapper.updateById(update);
    }
}
