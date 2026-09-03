package com.hmdp.dto.agent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AgentPendingActionDTO {
    private String actionRequestId;
    private String actionType;
    private String targetBizType;
    private Long targetBizId;
    private String expiresAt;
}
