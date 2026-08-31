package com.hmdp.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AgentClientException;
import com.hmdp.dto.agent.AgentRunRequestDTO;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class LangGraphCustomerAgentClientTest {

    @Test
    void shouldPreserveStructuredRetryableAgentErrorCode() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(once(), requestTo("http://agent/v1/customer-service/runs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError().contentType(MediaType.APPLICATION_JSON)
                        .body("{\"detail\":\"ROUTER_INVALID_RESPONSE\"}"));
        LangGraphCustomerAgentClient client = new LangGraphCustomerAgentClient(
                restTemplate, "http://agent", "service-key", new ObjectMapper());
        AgentRunRequestDTO request = new AgentRunRequestDTO();
        request.setRequestId("3001");

        AgentClientException error = assertThrows(AgentClientException.class,
                () -> client.invoke(request));

        assertEquals("ROUTER_INVALID_RESPONSE", error.getErrorCode());
        assertEquals(true, error.isRetryable());
        server.verify();
    }
}
