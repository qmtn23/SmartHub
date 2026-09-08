package com.hmdp.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.ConsultationContextDTO;
import com.hmdp.dto.MerchantFaqDTO;
import com.hmdp.security.AgentToolPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/** MySQL owns publication state. Vector records are disposable, untrusted candidates. */
@Service
public class MerchantFaqService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final ProductConsultationService contexts;
    public MerchantFaqService(JdbcTemplate jdbc, ObjectMapper json, ProductConsultationService contexts) {
        this.jdbc = jdbc; this.json = json; this.contexts = contexts;
    }
    public void requireManager(Long userId, long shopId) {
        if (userId == null || jdbc.queryForObject("SELECT COUNT(*) FROM tb_shop_manager WHERE shop_id=? AND user_id=?",
                Integer.class, shopId, userId) == 0) fail(HttpStatus.FORBIDDEN, "无权管理该店铺FAQ");
    }
    private Map<String,Object> requireFaq(long shopId, String faqId) {
        List<Map<String,Object>> rows = jdbc.queryForList("SELECT * FROM tb_merchant_faq WHERE faq_id=? AND shop_id=?", faqId, shopId);
        if (rows.isEmpty()) fail(HttpStatus.NOT_FOUND, "FAQ不存在");
        return rows.get(0);
    }
    public List<Map<String,Object>> list(long shopId, int page) {
        return jdbc.queryForList("SELECT f.*,r.payload,o.status AS index_status,o.error_code FROM tb_merchant_faq f " +
                "JOIN tb_merchant_faq_revision r ON r.faq_id=f.faq_id AND r.revision=f.revision " +
                "LEFT JOIN tb_faq_index_outbox o ON o.faq_id=f.faq_id AND o.revision=f.revision " +
                "WHERE f.shop_id=? ORDER BY f.update_time DESC,f.faq_id LIMIT 50 OFFSET ?", shopId, Math.max(0,page-1)*50);
    }
    public Map<String,Object> detail(long shopId, String faqId) {
        Map<String,Object> row = requireFaq(shopId,faqId);
        row.put("draft", readRevision(faqId, ((Number)row.get("revision")).intValue()));
        row.put("indexJobs", jdbc.queryForList("SELECT revision,status,attempts,error_code FROM tb_faq_index_outbox WHERE faq_id=? ORDER BY revision DESC LIMIT 10", faqId));
        return row;
    }
    private void validate(long shopId, MerchantFaqDTO value) {
        if (value == null || !Set.of("PRODUCT","CATEGORY","SHOP").contains(Objects.toString(value.getScope(),"")))
            fail(HttpStatus.BAD_REQUEST,"FAQ适用范围非法");
        if (value.getQuestion() == null || value.getQuestion().isBlank() || value.getQuestion().length()>200
                || value.getAnswer()==null || value.getAnswer().isBlank() || value.getAnswer().length()>2000)
            fail(HttpStatus.BAD_REQUEST,"问题或答案长度非法");
        if (value.getAliases()==null || value.getAliases().size()>10 || value.getAliases().stream().anyMatch(s -> s==null || s.isBlank() || s.length()>200))
            fail(HttpStatus.BAD_REQUEST,"相似问法非法");
        if (!Set.of("USAGE_DATE","RESERVATION","SUITABILITY","CONTENTS","RESTRICTIONS","GENERAL").contains(Objects.toString(value.getTopic(),"")))
            fail(HttpStatus.BAD_REQUEST,"FAQ主题非法（不支持价格、库存事实）");
        if (value.getValidFrom()!=null && value.getValidUntil()!=null && !value.getValidUntil().isAfter(value.getValidFrom()))
            fail(HttpStatus.BAD_REQUEST,"FAQ生效时间非法");
        ConsultationContextDTO ids = new ConsultationContextDTO(); ids.setShopId(shopId);
        if ("PRODUCT".equals(value.getScope())) {
            if (value.getVoucherId()==null) fail(HttpStatus.BAD_REQUEST,"商品级FAQ必须绑定商品");
            ids.setVoucherId(value.getVoucherId());
        } else if (value.getVoucherId()!=null) fail(HttpStatus.BAD_REQUEST,"非商品级FAQ不能绑定商品");
        if ("CATEGORY".equals(value.getScope()) && value.getCategoryId()==null) fail(HttpStatus.BAD_REQUEST,"品类不能为空");
        if ("SHOP".equals(value.getScope()) && value.getCategoryId()!=null) fail(HttpStatus.BAD_REQUEST,"全店FAQ不能绑定品类");
        ids.setCategoryId(value.getCategoryId()); contexts.resolve(ids);
    }
    @Transactional
    public Map<String,Object> save(long shopId, Long userId, String faqId, MerchantFaqDTO value) {
        requireManager(userId,shopId); validate(shopId,value);
        int revision;
        if (faqId==null) {
            faqId = UUID.randomUUID().toString().replace("-",""); revision=1;
            jdbc.update("INSERT INTO tb_merchant_faq(faq_id,shop_id,revision,update_time) VALUES(?,?,1,NOW())",faqId,shopId);
        } else {
            requireFaq(shopId,faqId);
            if (value.getExpectedRevision()==null) fail(HttpStatus.BAD_REQUEST,"expectedRevision不能为空");
            revision=value.getExpectedRevision()+1;
            if (jdbc.update("UPDATE tb_merchant_faq SET revision=?,pending_revision=NULL,update_time=NOW() WHERE faq_id=? AND shop_id=? AND revision=?",
                    revision,faqId,shopId,value.getExpectedRevision())!=1) fail(HttpStatus.CONFLICT,"FAQ已被修改");
        }
        Map<String,Object> payload=json.convertValue(value,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() {});
        payload.remove("expectedRevision"); payload.put("faqId",faqId); payload.put("shopId",shopId); payload.put("revision",revision);
        payload.put("validFrom",value.getValidFrom()==null?null:value.getValidFrom().toString());
        payload.put("validUntil",value.getValidUntil()==null?null:value.getValidUntil().toString());
        String encoded=encode(payload);
        jdbc.update("INSERT INTO tb_merchant_faq_revision(faq_id,revision,payload,checksum,created_by,create_time) VALUES(?,?,?,?,?,NOW())",
                faqId,revision,encoded,checksum(encoded),userId);
        return Map.of("faqId",faqId,"revision",revision);
    }
    @Transactional
    public void publish(long shopId, Long userId, String faqId, int revision) {
        requireManager(userId,shopId); Map<String,Object> row=requireFaq(shopId,faqId);
        if (((Number)row.get("revision")).intValue()!=revision) fail(HttpStatus.CONFLICT,"FAQ版本已变化");
        // Conditional update serializes against edits and disable. Re-publish a disabled revision
        // requires a new draft, preventing a stale completion from re-enabling it.
        if (row.get("active_revision")!=null && ((Number)row.get("active_revision")).intValue()==revision) {
            if (Boolean.TRUE.equals(row.get("enabled")) || "1".equals(String.valueOf(row.get("enabled")))) return;
            fail(HttpStatus.CONFLICT,"停用后请创建新版本再发布");
        }
        int updated=jdbc.update("UPDATE tb_merchant_faq SET pending_revision=?,requested_by=?,update_time=NOW() WHERE faq_id=? AND revision=?",
                revision,userId,faqId,revision);
        if(updated!=1) fail(HttpStatus.CONFLICT,"FAQ版本已变化");
        jdbc.update("INSERT INTO tb_faq_index_outbox(event_id,faq_id,revision,status,create_time,update_time) VALUES(?,?,?,'PENDING',NOW(),NOW()) " +
                "ON DUPLICATE KEY UPDATE status=IF(status IN ('FAILED','DONE'),'PENDING',status),attempts=IF(status='PENDING',0,attempts),update_time=NOW()",
                UUID.randomUUID().toString().replace("-",""),faqId,revision);
    }
    @Transactional
    public void disable(long shopId, Long userId, String faqId, int revision) {
        requireManager(userId,shopId); requireFaq(shopId,faqId);
        if(jdbc.update("UPDATE tb_merchant_faq SET enabled=0,pending_revision=NULL,update_time=NOW() WHERE faq_id=? AND shop_id=? AND revision=?",
                faqId,shopId,revision)!=1) fail(HttpStatus.CONFLICT,"FAQ版本已变化");
        jdbc.update("UPDATE tb_faq_index_outbox SET status='DONE',lease_id=NULL WHERE faq_id=?",faqId);
    }
    public Map<String,Object> readRevision(String faqId,int revision) {
        String value=jdbc.queryForObject("SELECT payload FROM tb_merchant_faq_revision WHERE faq_id=? AND revision=?",String.class,faqId,revision);
        return contexts.decode(value);
    }
    @Transactional
    public List<Map<String,Object>> claim() {
        jdbc.update("UPDATE tb_faq_index_outbox SET status='FAILED',error_code='INDEX_LEASE_EXHAUSTED',update_time=NOW() " +
                "WHERE status='RUNNING' AND attempts>=10 AND lease_until<NOW()");
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM tb_faq_index_outbox WHERE attempts<10 AND " +
                "(status='PENDING' OR (status='RUNNING' AND lease_until<NOW())) ORDER BY create_time LIMIT 5");
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> row:rows) {
            String lease=UUID.randomUUID().toString().replace("-","");
            if(jdbc.update("UPDATE tb_faq_index_outbox SET status='RUNNING',lease_id=?,lease_until=DATE_ADD(NOW(),INTERVAL 120 SECOND),attempts=attempts+1,update_time=NOW() " +
                    "WHERE event_id=? AND attempts<10 AND (status='PENDING' OR (status='RUNNING' AND lease_until<NOW()))",lease,row.get("event_id"))!=1) continue;
            Map<String,Object> entry=readRevision(row.get("faq_id").toString(),((Number)row.get("revision")).intValue());
            result.add(Map.of("eventId",row.get("event_id"),"leaseId",lease,"entry",entry,
                    "checksum",jdbc.queryForObject("SELECT checksum FROM tb_merchant_faq_revision WHERE faq_id=? AND revision=?",String.class,row.get("faq_id"),row.get("revision"))));
        }
        return result;
    }
    @Transactional
    public void complete(String eventId,String lease,boolean success) {
        List<Map<String,Object>> rows=jdbc.queryForList("SELECT * FROM tb_faq_index_outbox WHERE event_id=? AND lease_id=? AND status='RUNNING' AND lease_until>NOW() FOR UPDATE",eventId,lease);
        if(rows.isEmpty()) return;
        Map<String,Object> row=rows.get(0);
        if(success) jdbc.update("UPDATE tb_merchant_faq SET active_revision=?,enabled=1,pending_revision=NULL,published_by=requested_by,published_time=NOW(),update_time=NOW() " +
                "WHERE faq_id=? AND pending_revision=? AND revision=?",row.get("revision"),row.get("faq_id"),row.get("revision"),row.get("revision"));
        jdbc.update("UPDATE tb_faq_index_outbox SET status=?,error_code=?,lease_id=NULL,update_time=NOW() WHERE event_id=?",
                success?"DONE":(((Number)row.get("attempts")).intValue()>=10?"FAILED":"PENDING"),success?null:"INDEX_FAILED",eventId);
    }
    public Map<String,Object> tokenContext(AgentToolPrincipal principal) {
        if(principal==null || !principal.hasScope("faq:read")) fail(HttpStatus.FORBIDDEN,"缺少FAQ读取权限");
        List<String> rows=jdbc.query("SELECT consultation_context FROM tb_customer_chat_message WHERE message_id=? AND user_id=? AND chat_id=? AND im_chat_id=?",
                (rs,n)->rs.getString(1),principal.getUserMessageId(),principal.getUserId(),principal.getChatId(),principal.getImChatId());
        if(rows.isEmpty()) fail(HttpStatus.FORBIDDEN,"商品咨询上下文不存在");
        return contexts.decode(rows.get(0));
    }
    public List<Map<String,Object>> canonical(Map<String,Object> context, List<Map<String,Object>> candidates) {
        if(candidates==null || candidates.size()>50) fail(HttpStatus.BAD_REQUEST,"候选数量非法");
        if(context.get("shopId")==null) return Collections.emptyList();
        List<Map<String,Object>> result=new ArrayList<>();
        for(Map<String,Object> candidate:candidates) {
            List<Map<String,Object>> rows=jdbc.queryForList("SELECT r.payload FROM tb_merchant_faq f JOIN tb_merchant_faq_revision r ON r.faq_id=f.faq_id AND r.revision=f.active_revision " +
                    "WHERE f.faq_id=? AND f.shop_id=? AND f.active_revision=? AND f.enabled=1",
                    candidate.get("faqId"),context.get("shopId"),candidate.get("revision"));
            if(rows.isEmpty()) continue;
            Map<String,Object> entry=contexts.decode(rows.get(0).get("payload").toString());
            if(!applicable(entry,context)) continue;
            result.add(entry);
        }
        return result;
    }
    public static boolean applicable(Map<String,Object> entry,Map<String,Object> context) {
        if(!Objects.equals(ProductConsultationService.number(entry.get("shopId")),ProductConsultationService.number(context.get("shopId")))) return false;
        String scope=Objects.toString(entry.get("scope"),"");
        if("PRODUCT".equals(scope) && (context.get("voucherId")==null || !Objects.equals(ProductConsultationService.number(entry.get("voucherId")),ProductConsultationService.number(context.get("voucherId"))))) return false;
        if("CATEGORY".equals(scope) && (context.get("categoryId")==null || !Objects.equals(ProductConsultationService.number(entry.get("categoryId")),ProductConsultationService.number(context.get("categoryId"))))) return false;
        if(!Set.of("PRODUCT","CATEGORY","SHOP").contains(scope)) return false;
        LocalDateTime now=LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
        return (entry.get("validFrom")==null || !LocalDateTime.parse(entry.get("validFrom").toString()).isAfter(now))
                && (entry.get("validUntil")==null || LocalDateTime.parse(entry.get("validUntil").toString()).isAfter(now));
    }
    /** Enumerate one bounded page for the worker; stale vectors can be removed without exposing drafts publicly. */
    public List<Map<String,Object>> liveVersions(String afterId) {
        return jdbc.queryForList("SELECT faq_id,active_revision,pending_revision,enabled FROM tb_merchant_faq WHERE faq_id>? ORDER BY faq_id LIMIT 200",afterId);
    }
    private String encode(Object value) { try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);} }
    private String checksum(String value) { try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);} }
    private static void fail(HttpStatus status,String message){throw new ResponseStatusException(status,message);}
}
