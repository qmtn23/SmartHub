package com.hmdp.security;

import com.hmdp.utils.CustomerToolContext;
import com.hmdp.utils.CustomerToolContextHolder;
import io.jsonwebtoken.JwtException;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

@Component
public class AgentToolAuthFilter extends OncePerRequestFilter {
    public static final String PRINCIPAL_ATTRIBUTE = AgentToolPrincipal.class.getName();
    private static final String PATH_PREFIX = "/internal/agent-tools/";

    private final AgentToolTokenService tokenService;
    private final CustomerToolContextHolder contextHolder;

    public AgentToolAuthFilter(AgentToolTokenService tokenService,
                               CustomerToolContextHolder contextHolder) {
        this.tokenService = tokenService;
        this.contextHolder = contextHolder;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "缺少Agent工具令牌");
            return;
        }
        try {
            AgentToolPrincipal principal = tokenService.parse(authorization.substring(7));
            request.setAttribute(PRINCIPAL_ATTRIBUTE, principal);
            contextHolder.set(new CustomerToolContext(principal.getUserId(), principal.getImChatId(),
                    principal.getChatId(), principal.getUserMessageId()));
            filterChain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException | IllegalStateException e) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Agent工具令牌无效或已过期");
        } finally {
            contextHolder.clear();
        }
    }
}
