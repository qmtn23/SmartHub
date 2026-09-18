package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "customer-semantic-memory")
public class SemanticMemoryProperties {
    private boolean enabled = false;
    private int retentionDays = 90;
    private int maxAttempts = 5;
}
