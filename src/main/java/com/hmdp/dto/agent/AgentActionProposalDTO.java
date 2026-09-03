package com.hmdp.dto.agent;

import lombok.Data;

@Data
public class AgentActionProposalDTO {
    private String actionType;
    private Long orderId;
    private String targetBizType;
    private String displayTitle;
    private String consequences;
    private String confirmationPrompt;
    private Integer expiresInSeconds;
}
