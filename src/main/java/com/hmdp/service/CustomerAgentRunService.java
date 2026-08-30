package com.hmdp.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.hmdp.entity.CustomerAgentRun;
import com.hmdp.entity.CustomerChatMessage;
import com.hmdp.mapper.CustomerAgentRunMapper;
import com.hmdp.mapper.CustomerChatMessageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class CustomerAgentRunService {
    public static final String PENDING = "PENDING";
    public static final String RUNNING = "RUNNING";
    public static final String SUCCEEDED = "SUCCEEDED";
    public static final String FAILED_RETRYABLE = "FAILED_RETRYABLE";
    public static final String FAILED_FINAL = "FAILED_FINAL";

    private final CustomerAgentRunMapper runMapper;
    private final CustomerChatMessageMapper messageMapper;

    public CustomerAgentRunService(CustomerAgentRunMapper runMapper,
                                   CustomerChatMessageMapper messageMapper) {
        this.runMapper = runMapper;
        this.messageMapper = messageMapper;
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

    @Transactional
    public void completeSuccess(String runId, String traceId, CustomerChatMessage assistantMessage) {
        messageMapper.insert(assistantMessage);
        CustomerAgentRun update = new CustomerAgentRun()
                .setRunId(runId)
                .setStatus(SUCCEEDED)
                .setRetryable(false)
                .setTraceId(traceId)
                .setFinishedTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        runMapper.updateById(update);
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
