package com.hmdp.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AgentClientException;
import com.hmdp.dto.agent.AgentRunRequestDTO;
import com.hmdp.dto.agent.AgentRunResponseDTO;
import com.hmdp.dto.agent.AgentRunResumeRequestDTO;
import com.hmdp.entity.CustomerChatMessage;
import com.hmdp.service.CustomerAgentClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LangGraphCustomerAgentClient implements CustomerAgentClient {
    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    public LangGraphCustomerAgentClient(@Qualifier("agentRestTemplate") RestTemplate restTemplate,
                                        @Value("${agent-service.base-url}") String baseUrl,
                                        @Value("${agent-service.api-key:}") String apiKey,
                                        ObjectMapper objectMapper) {
        this.restTemplate = restTemplate;
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentRunResponseDTO invoke(AgentRunRequestDTO request) {
        HttpHeaders headers = serviceHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", request.getRequestId());
        try {
            ResponseEntity<AgentRunResponseDTO> response = restTemplate.exchange(
                    baseUrl + "/v1/customer-service/runs",
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    AgentRunResponseDTO.class);
            AgentRunResponseDTO body = response.getBody();
            if (body == null || body.getReply() == null || body.getReply().isBlank()) {
                throw new AgentClientException("INVALID_AGENT_RESPONSE", "智能客服未返回有效回复", true);
            }
            return body;
        } catch (HttpStatusCodeException e) {
            boolean retryable = e.getStatusCode().value() == 409
                    || e.getStatusCode().value() == 429
                    || e.getStatusCode().is5xxServerError();
            throw new AgentClientException(errorCode(e),
                    "智能客服服务暂时不可用", retryable, e);
        } catch (ResourceAccessException e) {
            throw new AgentClientException("AGENT_UNAVAILABLE", "智能客服服务暂时不可用", true, e);
        }
    }

    @Override
    public void deleteThread(Long chatId) {
        if (chatId == null) {
            return;
        }
        restTemplate.exchange(baseUrl + "/v1/customer-service/threads/" + chatId,
                HttpMethod.DELETE, new HttpEntity<>(serviceHeaders()), Void.class);
    }

    @Override
    public String summarizeSession(List<CustomerChatMessage> messages) {
        List<Map<String, String>> payloadMessages = new ArrayList<>();
        if (messages != null) {
            for (CustomerChatMessage message : messages) {
                if (message == null || message.getContent() == null || message.getContent().isBlank()) {
                    continue;
                }
                Map<String, String> item = new LinkedHashMap<>();
                item.put("sender_type", message.getSenderType());
                item.put("content", abbreviate(message.getContent(), 1000));
                payloadMessages.add(item);
            }
        }
        return postMemory("/v1/customer-service/memory/summarize-session",
                Map.of("messages", payloadMessages));
    }

    @Override
    public String mergeLongTermMemory(String previousSummary, String sessionSummary) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("previous_summary", previousSummary == null ? "" : previousSummary);
        payload.put("session_summary", sessionSummary == null ? "" : sessionSummary);
        return postMemory("/v1/customer-service/memory/merge-summary", payload);
    }

    @Override
    public JsonNode extractTaskMemory(JsonNode memory, List<Map<String, Object>> messages) {
        return postMemoryDiff("/v1/customer-service/memory/task-diff", Map.of("memory", memory, "messages", messages));
    }

    @Override
    public JsonNode extractUserProfile(JsonNode profile, JsonNode tasks, List<Map<String, Object>> messages) {
        return postMemoryDiff("/v1/customer-service/memory/profile-diff",
                Map.of("profile", profile, "tasks", tasks, "messages", messages));
    }

    private JsonNode postMemoryDiff(String path, Object payload) {
        HttpHeaders headers = serviceHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    baseUrl + path, HttpMethod.POST,
                    new HttpEntity<>(payload, headers), JsonNode.class);
            JsonNode diff = response.getBody();
            if (diff == null || !diff.path("operations").isArray())
                throw new AgentClientException("INVALID_MEMORY_DIFF", "记忆增量格式无效", true);
            return diff;
        } catch (HttpStatusCodeException e) {
            throw new AgentClientException(errorCode(e), "记忆服务暂时不可用", e.getStatusCode().value() == 429 || e.getStatusCode().is5xxServerError());
        } catch (ResourceAccessException e) {
            throw new AgentClientException("MEMORY_UNAVAILABLE", "记忆服务暂时不可用", true, e);
        }
    }

    private String postMemory(String path, Object payload) {
        HttpHeaders headers = serviceHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        try {
            ResponseEntity<JsonNode> response = restTemplate.exchange(
                    baseUrl + path, HttpMethod.POST, new HttpEntity<>(payload, headers), JsonNode.class);
            String summary = response.getBody() == null ? "" : response.getBody().path("summary").asText("");
            if (summary.isBlank()) {
                throw new AgentClientException("INVALID_MEMORY_RESPONSE", "智能客服未返回有效会话摘要", true);
            }
            return summary;
        } catch (HttpStatusCodeException e) {
            boolean retryable = e.getStatusCode().value() == 429 || e.getStatusCode().is5xxServerError();
            throw new AgentClientException(errorCode(e), "会话摘要服务暂时不可用", retryable, e);
        } catch (ResourceAccessException e) {
            throw new AgentClientException("AGENT_UNAVAILABLE", "会话摘要服务暂时不可用", true, e);
        }
    }

    private String abbreviate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private HttpHeaders serviceHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Agent-Service-Key", apiKey);
        return headers;
    }

    @Override
    public AgentRunResponseDTO resume(String runId, AgentRunResumeRequestDTO request) {
        HttpHeaders headers = serviceHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", request.getActionEventId());
        try {
            ResponseEntity<AgentRunResponseDTO> response = restTemplate.exchange(
                    baseUrl + "/v1/customer-service/runs/" + runId + "/resume",
                    HttpMethod.POST,
                    new HttpEntity<>(request, headers),
                    AgentRunResponseDTO.class);
            AgentRunResponseDTO body = response.getBody();
            if (body == null || body.getReply() == null || body.getReply().isBlank()) {
                throw new AgentClientException("INVALID_AGENT_RESPONSE", "智能客服未返回有效动作结果", true);
            }
            return body;
        } catch (HttpStatusCodeException e) {
            boolean retryable = e.getStatusCode().value() == 409
                    || e.getStatusCode().value() == 429
                    || e.getStatusCode().is5xxServerError();
            throw new AgentClientException(errorCode(e), "智能客服恢复失败", retryable, e);
        } catch (ResourceAccessException e) {
            throw new AgentClientException("AGENT_UNAVAILABLE", "智能客服恢复失败", true, e);
        }
    }

    private String errorCode(HttpStatusCodeException exception) {
        try {
            JsonNode detail = objectMapper.readTree(exception.getResponseBodyAsString()).path("detail");
            if (detail.isTextual() && detail.asText().matches("[A-Z][A-Z0-9_]{2,63}")) {
                return detail.asText();
            }
        } catch (Exception ignored) {
            // Fall back to the stable HTTP-derived code for non-FastAPI or malformed responses.
        }
        return "AGENT_HTTP_" + exception.getRawStatusCode();
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("agent-service.base-url不能为空");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
