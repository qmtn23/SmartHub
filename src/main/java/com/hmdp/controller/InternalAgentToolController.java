package com.hmdp.controller;

import com.hmdp.dto.tool.ToolResult;
import com.hmdp.security.AgentToolAuthFilter;
import com.hmdp.security.AgentToolPrincipal;
import com.hmdp.security.AgentToolScopes;
import com.hmdp.service.CustomerToolGateway;
import lombok.Data;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;

@RestController
@RequestMapping("/internal/agent-tools")
public class InternalAgentToolController {
    private final CustomerToolGateway gateway;

    public InternalAgentToolController(CustomerToolGateway gateway) {
        this.gateway = gateway;
    }

    @PostMapping("/shops/get")
    public ToolResult<?> getShop(@RequestBody IdRequest request, HttpServletRequest servletRequest) {
        requireScope(servletRequest, AgentToolScopes.SHOP_READ);
        return gateway.queryShopById(request.getId());
    }

    @PostMapping("/shops/search")
    public ToolResult<?> searchShops(@RequestBody KeywordRequest request, HttpServletRequest servletRequest) {
        requireScope(servletRequest, AgentToolScopes.SHOP_READ);
        return gateway.searchShopsByName(request.getKeyword());
    }

    @PostMapping("/vouchers/by-shop")
    public ToolResult<?> vouchersByShop(@RequestBody IdRequest request, HttpServletRequest servletRequest) {
        requireScope(servletRequest, AgentToolScopes.VOUCHER_READ);
        return gateway.queryVouchersByShopId(request.getId());
    }

    @PostMapping("/orders/current")
    public ToolResult<?> currentOrders(HttpServletRequest servletRequest) {
        requireScope(servletRequest, AgentToolScopes.ORDER_SELF_READ);
        return gateway.queryCurrentUserOrders();
    }

    @PostMapping("/shops/recommend")
    public ToolResult<?> recommendShops(@RequestBody TypeRequest request, HttpServletRequest servletRequest) {
        requireScope(servletRequest, AgentToolScopes.SHOP_READ);
        return gateway.recommendShopsByType(request.getTypeId());
    }

    @PostMapping("/blogs/hot")
    public ToolResult<?> hotBlogs(HttpServletRequest servletRequest) {
        requireScope(servletRequest, AgentToolScopes.BLOG_READ);
        return gateway.queryHotBlogs();
    }

    private AgentToolPrincipal requireScope(HttpServletRequest request, String scope) {
        AgentToolPrincipal principal = (AgentToolPrincipal) request.getAttribute(
                AgentToolAuthFilter.PRINCIPAL_ATTRIBUTE);
        if (principal == null || !principal.hasScope(scope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent工具权限不足");
        }
        return principal;
    }

    @Data
    public static class IdRequest {
        private Long id;
    }

    @Data
    public static class KeywordRequest {
        private String keyword;
    }

    @Data
    public static class TypeRequest {
        private Integer typeId;
    }
}
