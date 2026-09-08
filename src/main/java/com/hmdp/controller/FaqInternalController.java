package com.hmdp.controller;

import com.hmdp.security.AgentToolAuthFilter;
import com.hmdp.security.AgentToolPrincipal;
import com.hmdp.service.MerchantFaqService;
import org.springframework.web.bind.annotation.*;
import javax.servlet.http.HttpServletRequest;
import java.util.*;

@RestController
@RequestMapping("/internal/agent-tools/faq")
public class FaqInternalController {
    private final MerchantFaqService service;
    public FaqInternalController(MerchantFaqService service){this.service=service;}
    @PostMapping("/validate")
    public Object validate(HttpServletRequest request,@RequestBody Candidates body){
        AgentToolPrincipal principal=(AgentToolPrincipal)request.getAttribute(AgentToolAuthFilter.PRINCIPAL_ATTRIBUTE);
        Map<String,Object> context=service.tokenContext(principal);
        return Map.of("success",true,"entries",service.canonical(context,body.candidates),"context",context);
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = false)
    public static class Candidates {
        public List<Map<String,Object>> candidates;
        @com.fasterxml.jackson.annotation.JsonAnySetter
        public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("Unknown FAQ request field"); }
    }
}
