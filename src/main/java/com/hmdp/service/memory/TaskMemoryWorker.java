package com.hmdp.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.hmdp.config.TaskMemoryProperties;
import com.hmdp.service.CustomerAgentClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

@Slf4j
@Component
@EnableScheduling
public class TaskMemoryWorker {
    private final TaskMemoryStore store;
    private final TaskMemoryMerger merger;
    private final CustomerAgentClient agent;
    private final TaskMemoryProperties settings;
    private final Semaphore capacity = new Semaphore(2);
    private final ExecutorService workers = Executors.newFixedThreadPool(2, work -> {
        Thread thread = new Thread(work, "task-memory-worker");
        thread.setDaemon(true);
        return thread;
    });

    public TaskMemoryWorker(TaskMemoryStore store, TaskMemoryMerger merger,
                            CustomerAgentClient agent, TaskMemoryProperties settings) {
        this.store = store; this.merger = merger; this.agent = agent; this.settings = settings;
    }

    @Scheduled(fixedDelayString = "${customer-memory.poll-interval-ms:5000}")
    public void poll() {
        if (!settings.isEnabled()) return;
        try {
            store.discover();
            while (capacity.tryAcquire()) {
                boolean submitted = false;
                try {
                    TaskMemoryStore.Job job = store.claim();
                    if (job == null) return;
                    workers.submit(() -> {
                        try { process(job); }
                        finally { capacity.release(); }
                    });
                    submitted = true;
                } finally {
                    if (!submitted) capacity.release();
                }
            }
        } catch (RuntimeException e) {
            log.warn("记忆后台调度失败: {}", e.getClass().getSimpleName());
        }
    }

    public void process(TaskMemoryStore.Job job) {
        try {
            List<Map<String, Object>> messages = store.messages(job);
            // Version conflicts require re-extraction against fresh state, never replaying an old diff.
            for (int attempt = 0; attempt < 3; attempt++) {
                TaskMemoryStore.Snapshot base = store.snapshot(job.userId());
                JsonNode next = base.content();
                if (!messages.isEmpty()) {
                    JsonNode diff = agent.extractTaskMemory(merger.extractionView(base.content()), messages);
                    next = merger.merge(base.content(), diff, messages, LocalDateTime.now());
                }
                try {
                    store.complete(job, base, next, messages);
                    return;
                } catch (TaskMemoryStore.VersionConflict conflict) {
                    if (attempt == 2) throw conflict;
                }
            }
        } catch (TaskMemoryStore.LostLease ignored) {
            log.info("记忆任务租约已失效: chatId={}", job.chatId());
        } catch (RuntimeException e) {
            String error = e instanceof TaskMemoryStore.VersionConflict ? "VERSION_CONFLICT" : "MEMORY_UPDATE_FAILED";
            store.fail(job, error);
            log.warn("记忆更新失败，将按任务状态重试: chatId={}, error={}", job.chatId(), error);
        }
    }

    @PreDestroy
    public void close() { workers.shutdownNow(); }
}
