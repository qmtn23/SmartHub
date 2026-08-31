package com.hmdp.dto.agent;

import lombok.Data;

@Data
public class AgentUsageDTO {
    private Integer promptTokens;
    private Integer completionTokens;
}
