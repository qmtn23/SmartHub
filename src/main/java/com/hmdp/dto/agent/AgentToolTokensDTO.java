package com.hmdp.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentToolTokensDTO {
    private String transactionAgentToken;
    private String discoveryAgentToken;
}
