package com.hmdp.controller;

import com.hmdp.service.MerchantFaqService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Private service-to-service endpoints. Never mounted under the public merchant API. */
@RestController
@RequestMapping("/internal/faq-index")
public class FaqIndexController {
    private final MerchantFaqService service;
    private final String key;
    public FaqIndexController(MerchantFaqService service,@Value("${agent-service.faq-index-key:}") String key){this.service=service;this.key=key;}
    private void auth(String supplied){
        if(key.length()<32 || supplied==null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid index service key");
    }
    @PostMapping("/claim")
    public Object claim(@RequestHeader(value="X-Faq-Index-Key",required=false) String key){auth(key);return Map.of("jobs",service.claim());}
    @PostMapping("/complete")
    public Object complete(@RequestHeader(value="X-Faq-Index-Key",required=false) String key,@RequestBody Completion body){
        auth(key);service.complete(body.eventId,body.leaseId,body.success);return Map.of("accepted",true);
    }
    @GetMapping("/versions")
    public Object versions(@RequestHeader(value="X-Faq-Index-Key",required=false) String key,@RequestParam(defaultValue="") String afterId){
        auth(key);return Map.of("entries",service.liveVersions(afterId));
    }
    public static class Completion {public String eventId;public String leaseId;public boolean success;}
}
