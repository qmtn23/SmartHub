package com.hmdp.dto.agent;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@lombok.ToString(onlyExplicitlyIncluded = true)
@NoArgsConstructor
public class AgentToolTokensDTO {
    private String faqKnowledgeToken;
    private String transactionAgentToken;
    private String discoveryAgentToken;
    private String shopAgentToken;
    private String voucherAgentToken;
    private String contentAgentToken;
    private String orderAgentToken;
    private String refundAgentToken;

    /** Rolling-deployment compatibility constructor for v2-v4 clients. */
    public AgentToolTokensDTO(String transactionAgentToken, String discoveryAgentToken) {
        this.transactionAgentToken = transactionAgentToken;
        this.discoveryAgentToken = discoveryAgentToken;
    }
}
