package com.hmdp.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.config.AgentClientException;
import com.hmdp.dto.agent.AgentRunRequestDTO;
import com.hmdp.dto.agent.AgentRunResponseDTO;
import com.hmdp.dto.agent.AgentRunResumeRequestDTO;
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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class LangGraphCustomerAgentClientTest {

    @Test
    void shouldDeserializeV4SupervisorAndResolutionAuditFields() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(once(), requestTo("http://agent/v1/customer-service/runs"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"runId\":\"run-1\",\"reply\":\"已完成\","
                                + "\"intent\":\"ORDER_QUERY\",\"activeAgent\":\"transaction_agent\","
                                + "\"traceId\":\"trace-1\",\"graphVersion\":\"v4\","
                                + "\"runStatus\":\"COMPLETED\",\"resolutionType\":\"RESPONSE_ONLY\","
                                + "\"executionMode\":\"COMPLEX\",\"planId\":\"plan-1\","
                                + "\"supervisorIterations\":1,\"parallelTaskCount\":2,"
                                + "\"orchestrator\":\"supervisor\",\"businessRefs\":[],"
                                + "\"taskOutcomes\":[{\"taskId\":\"orders\","
                                + "\"targetAgent\":\"transaction_agent\",\"intent\":\"ORDER_QUERY\","
                                + "\"status\":\"SUCCEEDED\",\"result\":\"查询成功\"}]}",
                        MediaType.APPLICATION_JSON));
        LangGraphCustomerAgentClient client = new LangGraphCustomerAgentClient(
                restTemplate, "http://agent", "service-key", new ObjectMapper());
        AgentRunRequestDTO request = new AgentRunRequestDTO();
        request.setRequestId("3001");

        AgentRunResponseDTO response = client.invoke(request);

        assertEquals("COMPLEX", response.getExecutionMode());
        assertEquals("plan-1", response.getPlanId());
        assertEquals("orders", response.getTaskOutcomes().get(0).getTaskId());
        assertEquals("supervisor", response.getOrchestrator());
        assertEquals("RESPONSE_ONLY", response.getResolutionType());
        server.verify();
    }

    @Test
    void shouldResumeInterruptedAction() {
        RestTemplate restTemplate = new RestTemplate();
        MockRestServiceServer server = MockRestServiceServer.createServer(restTemplate);
        server.expect(once(), requestTo("http://agent/v1/customer-service/runs/agent-run/resume"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"runId\":\"agent-run\",\"reply\":\"订单已取消。\"," +
                                "\"intent\":\"ORDER_CANCEL\",\"activeAgent\":\"transaction_agent\"," +
                                "\"traceId\":\"trace-1\",\"graphVersion\":\"v4\"," +
                                "\"runStatus\":\"COMPLETED\",\"businessRefs\":[]}",
                        MediaType.APPLICATION_JSON));
        LangGraphCustomerAgentClient client = new LangGraphCustomerAgentClient(
                restTemplate, "http://agent", "service-key", new ObjectMapper());
        AgentRunResumeRequestDTO request = new AgentRunResumeRequestDTO();
        request.setActionEventId("event-1");

        AgentRunResponseDTO response = client.resume("agent-run", request);

        assertEquals("订单已取消。", response.getReply());
        assertEquals("COMPLETED", response.getRunStatus());
        server.verify();
    }

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
