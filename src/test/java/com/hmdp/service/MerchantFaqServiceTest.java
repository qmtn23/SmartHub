package com.hmdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.MerchantFaqDTO;
import com.hmdp.security.AgentToolPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MerchantFaqServiceTest {
    private final JdbcTemplate jdbc=mock(JdbcTemplate.class);
    private final ProductConsultationService contexts=mock(ProductConsultationService.class);
    private final MerchantFaqService service=new MerchantFaqService(jdbc,new ObjectMapper().findAndRegisterModules(),contexts);
    private static Map<String,Object> entry(){return new HashMap<>(Map.of("shopId",1L,"voucherId",7L,"scope","PRODUCT","revision",1));}

    @Test void scopesNeverLeakAcrossShopsOrProducts(){
        assertTrue(MerchantFaqService.applicable(entry(),Map.of("shopId",1,"voucherId",7)));
        assertFalse(MerchantFaqService.applicable(entry(),Map.of("shopId",2,"voucherId",7)));
        assertFalse(MerchantFaqService.applicable(entry(),Map.of("shopId",1,"voucherId",8)));
        assertFalse(MerchantFaqService.applicable(entry(),Map.of("shopId",1)));
    }
    @Test void categoryAndShopScopesAreExplicit(){
        Map<String,Object> category=entry();category.put("scope","CATEGORY");category.put("categoryId",2);
        assertFalse(MerchantFaqService.applicable(category,Map.of("shopId",1,"categoryId",1)));
        assertTrue(MerchantFaqService.applicable(category,Map.of("shopId",1,"categoryId",2)));
        category.put("scope","SHOP");assertTrue(MerchantFaqService.applicable(category,Map.of("shopId",1)));
        category.put("scope","UNKNOWN");assertFalse(MerchantFaqService.applicable(category,Map.of("shopId",1)));
    }
    @Test void expiredAndFutureEvidenceAreNotReturned(){
        Map<String,Object> value=entry(); value.put("validUntil","2000-01-01T00:00:00");
        assertFalse(MerchantFaqService.applicable(value,Map.of("shopId",1,"voucherId",7)));
        value.remove("validUntil");value.put("validFrom","2999-01-01T00:00:00");
        assertFalse(MerchantFaqService.applicable(value,Map.of("shopId",1,"voucherId",7)));
    }
    @Test void unauthenticatedOrNonManagerCannotWrite(){
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,()->service.requireManager(null,1)).getStatus());
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(1L),eq(3L))).thenReturn(0);
        assertThrows(ResponseStatusException.class,()->service.requireManager(3L,1));
    }
    @Test void businessTokensCannotReadFaq(){
        AgentToolPrincipal principal=new AgentToolPrincipal(1L,2L,3L,4L,Set.of("shop:read","voucher:read"));
        assertEquals(HttpStatus.FORBIDDEN,assertThrows(ResponseStatusException.class,()->service.tokenContext(principal)).getStatus());
        verifyNoInteractions(jdbc);
    }
    @Test void staleCompletionCannotActivateRevision(){
        when(jdbc.queryForList(anyString(),eq("job"),eq("expired-lease"))).thenReturn(List.of());
        service.complete("job","expired-lease",true);
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void failedIndexDoesNotChangeActiveRevision(){
        when(jdbc.queryForList(anyString(),eq("job"),eq("lease"))).thenReturn(List.of(Map.of("attempts",1,"revision",2,"faq_id","f")));
        service.complete("job","lease",false);
        verify(jdbc,never()).update(startsWith("UPDATE tb_merchant_faq SET active_revision"),any(),any(),any(),any());
        verify(jdbc).update(startsWith("UPDATE tb_faq_index_outbox SET status="),eq("PENDING"),eq("INDEX_FAILED"),eq("job"));
    }
    @Test void activationUsesPendingAndLatestVersionCas(){
        when(jdbc.queryForList(anyString(),eq("job"),eq("lease"))).thenReturn(List.of(Map.of("attempts",1,"revision",2,"faq_id","f")));
        service.complete("job","lease",true);
        verify(jdbc).update(contains("WHERE faq_id=? AND pending_revision=? AND revision=?"),eq(2),eq("f"),eq(2),eq(2));
    }
    @Test void invalidProductFaqFailsBeforePersistence(){
        when(jdbc.queryForObject(anyString(),eq(Integer.class),eq(1L),eq(3L))).thenReturn(1);
        MerchantFaqDTO value=new MerchantFaqDTO();value.setScope("PRODUCT");value.setQuestion("需要预约吗");
        value.setAnswer("需要预约");value.setTopic("RESERVATION");
        assertThrows(ResponseStatusException.class,()->service.save(1,3L,null,value));
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
}
