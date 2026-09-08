package com.hmdp.controller;

import com.hmdp.dto.tool.ToolResult;
import com.hmdp.security.AgentToolAuthFilter;
import com.hmdp.security.AgentToolPrincipal;
import com.hmdp.security.AgentToolScopes;
import com.hmdp.service.CustomerToolGateway;
import com.hmdp.service.ProductConsultationService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

/** Context-bound read tools: the model never supplies a shop or voucher identifier. */
@RestController
@RequestMapping("/internal/agent-tools/context")
public class CurrentProductToolController {
    private final CustomerToolGateway gateway;
    private final ProductConsultationService consultations;

    public CurrentProductToolController(CustomerToolGateway gateway,
                                        ProductConsultationService consultations) {
        this.gateway = gateway;
        this.consultations = consultations;
    }

    @PostMapping("/current-shop")
    public ToolResult<?> currentShop(HttpServletRequest request) {
        AgentToolPrincipal principal = requireScope(request, AgentToolScopes.SHOP_READ);
        Map<String,Object> context = consultations.forAgentTool(principal);
        return gateway.queryShopById(ProductConsultationService.number(context.get("shopId")));
    }

    @PostMapping("/current-voucher")
    public ToolResult<?> currentVoucher(HttpServletRequest request) {
        AgentToolPrincipal principal = requireScope(request, AgentToolScopes.VOUCHER_READ);
        Map<String,Object> context = consultations.forAgentTool(principal);
        return gateway.queryVoucherByContext(
                ProductConsultationService.number(context.get("voucherId")),
                ProductConsultationService.number(context.get("shopId")));
    }

    private AgentToolPrincipal requireScope(HttpServletRequest request, String scope) {
        AgentToolPrincipal principal = (AgentToolPrincipal) request.getAttribute(
                AgentToolAuthFilter.PRINCIPAL_ATTRIBUTE);
        if (principal == null || !principal.hasScope(scope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Agent工具权限不足");
        }
        return principal;
    }
}
