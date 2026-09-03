package com.hmdp.dto.agent;

import lombok.Data;

@Data
public class AgentRunResumeRequestDTO {
    private String requestId;
    private String threadId;
    private String actionRequestId;
    private String actionEventId;
    private String resumeType;
    private AgentActionOutcomeDTO actionOutcome;
}
