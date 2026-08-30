package com.hmdp.dto.agent;

import lombok.Data;

import java.util.List;

@Data
public class AgentRunRequestDTO {
    private String requestId;
    private String threadId;
    private Long imChatId;
    private Long userMessageId;
    private String message;
    private String longTermSummary;
    private List<AgentMessageDTO> recentMessages;
    private String toolAccessToken;
}
