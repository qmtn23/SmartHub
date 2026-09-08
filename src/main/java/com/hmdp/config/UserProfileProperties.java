package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "customer-profile")
public class UserProfileProperties {
    private boolean enabled = false;
    private int confirmedTtlDays = 90;
    private int batchEvents = 3;
    private int coalesceSeconds = 60;
    private int maxAttempts = 5;
}
