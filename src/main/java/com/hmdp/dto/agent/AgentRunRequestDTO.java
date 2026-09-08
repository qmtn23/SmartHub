package com.hmdp.dto.agent;

import lombok.Data;

import java.util.List;

@Data
@lombok.ToString(onlyExplicitlyIncluded = true)
public class AgentRunRequestDTO {
    private String requestId;
    private String threadId;
    private Long imChatId;
    private Long userMessageId;
    private String message;
    private java.util.Map<String,Object> consultationContext;
    private String longTermSummary;
    private com.fasterxml.jackson.databind.JsonNode userProfile;
    private List<AgentMessageDTO> recentMessages;
    private String previousActiveAgent;
    private String previousActiveScene;
    private String previousActiveMaster;
    private String graphVersion;
    private AgentPendingActionDTO pendingAction;
    private AgentToolTokensDTO toolAccessTokens;
    /** @deprecated rolling-deployment compatibility with phase one only */
    @Deprecated
    private String toolAccessToken;
}
