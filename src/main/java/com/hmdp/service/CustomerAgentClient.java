package com.hmdp.service;

import com.hmdp.dto.agent.AgentRunRequestDTO;
import com.hmdp.dto.agent.AgentRunResponseDTO;

public interface CustomerAgentClient {
    AgentRunResponseDTO invoke(AgentRunRequestDTO request);

    void deleteThread(Long chatId);
}
