package com.hmdp.security;

import com.hmdp.config.ChatBusinessException;
import com.hmdp.utils.CustomerToolContext;
import com.hmdp.utils.CustomerToolContextHolder;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.FilterChain;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AgentToolAuthFilterTest {
    private static final String SECRET = "01234567890123456789012345678901";

    @Test
    void shouldInjectAuthenticatedContextAndAlwaysClearIt() throws Exception {
        CustomerToolContextHolder holder = new CustomerToolContextHolder();
        AgentToolTokenService tokenService = new AgentToolTokenService(SECRET);
        AgentToolAuthFilter filter = new AgentToolAuthFilter(tokenService, holder);
        String token = tokenService.issue(new CustomerToolContext(7L, 1001L, 2001L, 3001L),
                AgentToolScopes.allReadScopes());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/agent-tools/orders/current");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<CustomerToolContext> captured = new AtomicReference<>();
        FilterChain chain = (servletRequest, servletResponse) -> captured.set(holder.requireContext());

        filter.doFilter(request, response, chain);

        assertEquals(7L, captured.get().getUserId());
        assertEquals(3001L, captured.get().getMessageId());
        assertThrows(ChatBusinessException.class, holder::requireContext);
    }

    @Test
    void shouldRejectTamperedToken() throws Exception {
        CustomerToolContextHolder holder = new CustomerToolContextHolder();
        AgentToolTokenService tokenService = new AgentToolTokenService(SECRET);
        AgentToolAuthFilter filter = new AgentToolAuthFilter(tokenService, holder);
        String token = tokenService.issue(new CustomerToolContext(7L, 1001L, 2001L, 3001L),
                AgentToolScopes.allReadScopes());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/internal/agent-tools/orders/current");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token.substring(0, token.length() - 1) + "x");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> {
            throw new AssertionError("invalid token must not reach the tool");
        });

        assertEquals(401, response.getStatus());
        assertThrows(ChatBusinessException.class, holder::requireContext);
    }
}
