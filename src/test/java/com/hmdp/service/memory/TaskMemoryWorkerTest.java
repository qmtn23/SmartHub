package com.hmdp.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.TaskMemoryProperties;
import com.hmdp.service.CustomerAgentClient;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class TaskMemoryWorkerTest {
    final ObjectMapper json = new ObjectMapper();
    final TaskMemoryStore store = mock(TaskMemoryStore.class);
    final CustomerAgentClient agent = mock(CustomerAgentClient.class);
    final TaskMemoryProperties settings = new TaskMemoryProperties();
    final TaskMemoryMerger merger = new TaskMemoryMerger(json,settings);
    final TaskMemoryStore.Job job = new TaskMemoryStore.Job(10,7,100,"lease",1);
    final List<Map<String,Object>> messages = List.of(Map.of("messageId",1L,"senderType","USER","content","你好","createTime","2026-09-08T12:00"));
    @Test void versionConflictReextractsAgainstFreshMemoryBeforeCommit() {
        var first=new TaskMemoryStore.Snapshot(1,merger.empty());
        var second=new TaskMemoryStore.Snapshot(2,merger.empty());
        when(store.messages(job)).thenReturn(messages);
        when(store.snapshot(7)).thenReturn(first,second);
        when(agent.extractTaskMemory(any(),eq(messages))).thenReturn(json.valueToTree(Map.of("operations",List.of())));
        doThrow(new TaskMemoryStore.VersionConflict()).when(store).complete(eq(job),eq(first),any(),eq(messages));
        TaskMemoryWorker worker = new TaskMemoryWorker(store,merger,agent,settings);
        try { worker.process(job); } finally { worker.close(); }
        verify(agent,times(2)).extractTaskMemory(any(),eq(messages));
        verify(store).complete(eq(job),eq(second),any(),eq(messages));
        verify(store,never()).fail(any(),anyString());
    }
    @Test void modelFailureNeverAcknowledgesMessages() {
        when(store.messages(job)).thenReturn(messages);
        when(store.snapshot(7)).thenReturn(new TaskMemoryStore.Snapshot(1,merger.empty()));
        when(agent.extractTaskMemory(any(),eq(messages))).thenThrow(new IllegalStateException("model down"));
        TaskMemoryWorker worker = new TaskMemoryWorker(store,merger,agent,settings);
        try { worker.process(job); } finally { worker.close(); }
        verify(store,never()).complete(any(),any(),any(),anyList());
        verify(store).fail(job,"MEMORY_UPDATE_FAILED");
    }
    @Test void disabledFeatureNeverPollsDatabaseOrModel() {
        TaskMemoryWorker worker = new TaskMemoryWorker(store,merger,agent,settings);
        try { worker.poll(); } finally { worker.close(); }
        verifyNoInteractions(store,agent);
    }
}
