package com.hmdp.controller;

import com.hmdp.security.AgentToolAuthFilter;
import com.hmdp.security.AgentToolPrincipal;
import com.hmdp.security.AgentToolScopes;
import com.hmdp.service.CustomerToolGateway;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class InternalAgentToolControllerTest {

    @Test
    void transactionTokenCannotCallDiscoveryBlogTool() {
        CustomerToolGateway gateway = mock(CustomerToolGateway.class);
        InternalAgentToolController controller = new InternalAgentToolController(gateway);
        MockHttpServletRequest request = authenticated(AgentToolScopes.transactionScopes());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.hotBlogs(request));

        assertEquals(403, error.getRawStatusCode());
    }

    @Test
    void discoveryTokenCannotCallTransactionOrderTool() {
        CustomerToolGateway gateway = mock(CustomerToolGateway.class);
        InternalAgentToolController controller = new InternalAgentToolController(gateway);
        MockHttpServletRequest request = authenticated(AgentToolScopes.discoveryScopes());

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> controller.currentOrders(request));

        assertEquals(403, error.getRawStatusCode());
    }

    @Test
    void transactionTokenCanCallCurrentUserOrders() {
        CustomerToolGateway gateway = mock(CustomerToolGateway.class);
        InternalAgentToolController controller = new InternalAgentToolController(gateway);

        controller.currentOrders(authenticated(AgentToolScopes.transactionScopes()));

        verify(gateway).queryCurrentUserOrders();
    }

    private MockHttpServletRequest authenticated(java.util.Set<String> scopes) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AgentToolAuthFilter.PRINCIPAL_ATTRIBUTE,
                new AgentToolPrincipal(7L, 1001L, 2001L, 3001L, scopes));
        return request;
    }
}
