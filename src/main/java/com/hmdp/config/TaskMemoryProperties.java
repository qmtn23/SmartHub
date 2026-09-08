package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "customer-memory")
public class TaskMemoryProperties {
    private boolean enabled = false;
    private int triggerMessages = 12;
    private int maxDelaySeconds = 30;
    private int batchSize = 30;
    private int activeTtlDays = 30;
    private int endedTtlDays = 7;
    private int maxTasks = 8;
    private int maxActiveTasks = 5;
    private int maxAttempts = 5;
}
