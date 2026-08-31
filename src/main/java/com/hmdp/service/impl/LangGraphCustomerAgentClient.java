package com.hmdp.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AgentClientException;
import com.hmdp.dto.agent.AgentRunRequestDTO;
import com.hmdp.dto.agent.AgentRunResponseDTO;
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

    private HttpHeaders serviceHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Agent-Service-Key", apiKey);
        return headers;
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
