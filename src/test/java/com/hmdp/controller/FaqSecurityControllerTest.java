package com.hmdp.controller;

import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.security.*;
import com.hmdp.service.MerchantFaqService;
import com.hmdp.utils.CustomerToolContext;
import com.hmdp.utils.CustomerToolContextHolder;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class FaqSecurityControllerTest {
    private final MerchantFaqService service=mock(MerchantFaqService.class);
    private final AgentToolTokenService tokens=new AgentToolTokenService("01234567890123456789012345678901");
    private MockMvc faqMvc(){return MockMvcBuilders.standaloneSetup(new FaqInternalController(service))
            .setControllerAdvice(new WebExceptionAdvice())
            .addFilters(new AgentToolAuthFilter(tokens,new CustomerToolContextHolder())).build();}
    private String token(Set<String> scopes){return tokens.issue(new CustomerToolContext(7L,10L,20L,30L),scopes);}
    @Test void missingAndInvalidJwtReturn401() throws Exception {
        faqMvc().perform(post("/internal/agent-tools/faq/validate").contentType(MediaType.APPLICATION_JSON).content("{\"candidates\":[]}"))
                .andExpect(status().isUnauthorized());
        faqMvc().perform(post("/internal/agent-tools/faq/validate").header("Authorization","Bearer invalid").contentType(MediaType.APPLICATION_JSON).content("{\"candidates\":[]}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    @Test void wrongScopeReturns403Not200() throws Exception {
        when(service.tokenContext(any())).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN,"FAQ scope required"));
        faqMvc().perform(post("/internal/agent-tools/faq/validate").header("Authorization","Bearer "+token(Set.of("shop:read")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"candidates\":[]}"))
                .andExpect(status().isForbidden());
    }
    @Test void forgedUserIdIsRejectedDuringDeserialization() throws Exception {
        faqMvc().perform(post("/internal/agent-tools/faq/validate").header("Authorization","Bearer "+token(Set.of("faq:read")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"candidates\":[],\"userId\":999}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }
    @Test void authorizedRequestUsesSignedIdentity() throws Exception {
        when(service.tokenContext(any())).thenReturn(Map.of("shopId",1));
        when(service.canonical(anyMap(),anyList())).thenReturn(List.of());
        faqMvc().perform(post("/internal/agent-tools/faq/validate").header("Authorization","Bearer "+token(Set.of("faq:read")))
                .contentType(MediaType.APPLICATION_JSON).content("{\"candidates\":[]}"))
                .andExpect(status().isOk());
        verify(service).tokenContext(argThat(p->p.getUserId()==7L && p.getUserMessageId()==30L));
    }
    @Test void workerKeyIsSeparateAndFailsClosed() throws Exception {
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new FaqIndexController(service,"worker-key-01234567890123456789012"))
                .setControllerAdvice(new WebExceptionAdvice()).build();
        mvc.perform(post("/internal/faq-index/claim")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/faq-index/claim").header("X-Faq-Index-Key","wrong"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
}
