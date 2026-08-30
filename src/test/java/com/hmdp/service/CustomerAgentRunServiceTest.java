package com.hmdp.service;

import com.hmdp.entity.CustomerAgentRun;
import com.hmdp.entity.CustomerChatMessage;
import com.hmdp.mapper.CustomerAgentRunMapper;
import com.hmdp.mapper.CustomerChatMessageMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CustomerAgentRunServiceTest {
    @Mock
    private CustomerAgentRunMapper runMapper;
    @Mock
    private CustomerChatMessageMapper messageMapper;

    @Test
    void shouldCreateAuditablePendingRun() {
        CustomerAgentRunService service = new CustomerAgentRunService(runMapper, messageMapper);
        CustomerChatMessage message = new CustomerChatMessage()
                .setMessageId(3001L)
                .setImChatId(1001L)
                .setChatId(2001L)
                .setUserId(7L);

        CustomerAgentRun run = service.createPending(message);

        assertEquals("3001", run.getRequestId());
        assertEquals(CustomerAgentRunService.PENDING, run.getStatus());
        assertFalse(run.getRetryable());
        verify(runMapper).insert(run);
    }

    @Test
    void shouldPersistReplyBeforeMarkingRunSucceeded() {
        CustomerAgentRunService service = new CustomerAgentRunService(runMapper, messageMapper);
        CustomerChatMessage reply = new CustomerChatMessage().setMessageId(4001L);

        service.completeSuccess("run-1", "trace-1", reply);

        verify(messageMapper).insert(reply);
        ArgumentCaptor<CustomerAgentRun> captor = ArgumentCaptor.forClass(CustomerAgentRun.class);
        verify(runMapper).updateById(captor.capture());
        assertEquals(CustomerAgentRunService.SUCCEEDED, captor.getValue().getStatus());
        assertEquals("trace-1", captor.getValue().getTraceId());
    }

    @Test
    void shouldPersistUserMessageAndPendingRunInOneServiceTransaction() {
        CustomerAgentRunService service = new CustomerAgentRunService(runMapper, messageMapper);
        CustomerChatMessage message = new CustomerChatMessage()
                .setMessageId(3002L).setImChatId(1001L).setChatId(2001L).setUserId(7L);

        service.createPendingWithUserMessage(message);

        verify(messageMapper).insert(message);
        verify(runMapper).insert(any(CustomerAgentRun.class));
    }
}
