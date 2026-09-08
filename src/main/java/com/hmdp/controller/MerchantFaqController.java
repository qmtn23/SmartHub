package com.hmdp.controller;

import com.hmdp.dto.MerchantFaqDTO;
import com.hmdp.service.MerchantFaqService;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/merchant/shops/{shopId}/faqs")
public class MerchantFaqController {
    private final MerchantFaqService service;
    public MerchantFaqController(MerchantFaqService service){this.service=service;}
    private Long user(long shopId){
        Long id=UserHolder.getUser()==null?null:UserHolder.getUser().getId();
        service.requireManager(id,shopId); return id;
    }
    @GetMapping
    public Object list(@PathVariable long shopId,@RequestParam(defaultValue="1") int page){user(shopId);return service.list(shopId,page);}
    @GetMapping("/{faqId}")
    public Object detail(@PathVariable long shopId,@PathVariable String faqId){user(shopId);return service.detail(shopId,faqId);}
    @PostMapping
    public Object create(@PathVariable long shopId,@RequestBody MerchantFaqDTO body){return service.save(shopId,user(shopId),null,body);}
    @PutMapping("/{faqId}")
    public Object edit(@PathVariable long shopId,@PathVariable String faqId,@RequestBody MerchantFaqDTO body){return service.save(shopId,user(shopId),faqId,body);}
    @PostMapping("/{faqId}/publish")
    public Object publish(@PathVariable long shopId,@PathVariable String faqId,@RequestBody Revision body){
        service.publish(shopId,user(shopId),faqId,body.revision);return Map.of("accepted",true);
    }
    @PostMapping("/{faqId}/disable")
    public Object disable(@PathVariable long shopId,@PathVariable String faqId,@RequestBody Revision body){
        service.disable(shopId,user(shopId),faqId,body.revision);return Map.of("disabled",true);
    }
    public static class Revision { public int revision; }
}
