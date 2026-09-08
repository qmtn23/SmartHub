package com.hmdp.service;

import com.hmdp.dto.agent.AgentRunRequestDTO;
import com.hmdp.dto.agent.AgentRunResponseDTO;
import com.hmdp.dto.agent.AgentRunResumeRequestDTO;
import com.hmdp.entity.CustomerChatMessage;

import java.util.List;

public interface CustomerAgentClient {
    AgentRunResponseDTO invoke(AgentRunRequestDTO request);

    AgentRunResponseDTO resume(String runId, AgentRunResumeRequestDTO request);

    void deleteThread(Long chatId);

    String summarizeSession(List<CustomerChatMessage> messages);

    String mergeLongTermMemory(String previousSummary, String sessionSummary);

    com.fasterxml.jackson.databind.JsonNode extractUserProfile(com.fasterxml.jackson.databind.JsonNode profile,
            com.fasterxml.jackson.databind.JsonNode tasks, List<java.util.Map<String, Object>> messages);

    com.fasterxml.jackson.databind.JsonNode extractTaskMemory(com.fasterxml.jackson.databind.JsonNode memory,
            List<java.util.Map<String, Object>> messages);
}
