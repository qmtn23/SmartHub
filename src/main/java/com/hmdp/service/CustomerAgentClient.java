package com.hmdp.service;

import com.hmdp.dto.agent.AgentRunRequestDTO;
import com.hmdp.dto.agent.AgentRunResponseDTO;
import com.hmdp.dto.agent.AgentRunResumeRequestDTO;

public interface CustomerAgentClient {
    AgentRunResponseDTO invoke(AgentRunRequestDTO request);

    AgentRunResponseDTO resume(String runId, AgentRunResumeRequestDTO request);

    void deleteThread(Long chatId);
}
