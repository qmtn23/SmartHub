package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "customer-working-memory")
public class WorkingMemoryProperties {
    private boolean enabled = true;
    private int ttlHours = 24;
    private int rollingThreshold = 30;
    private int windowSize = 20;
}
