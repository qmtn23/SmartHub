package com.hmdp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.ConsultationContextDTO;
import com.hmdp.entity.Shop;
import com.hmdp.entity.Voucher;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.mapper.VoucherMapper;
import com.hmdp.security.AgentToolPrincipal;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;

@Service
public class ProductConsultationService {
    private final ShopMapper shops;
    private final VoucherMapper vouchers;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public ProductConsultationService(ShopMapper shops, VoucherMapper vouchers, JdbcTemplate jdbc, ObjectMapper json) {
        this.shops = shops; this.vouchers = vouchers; this.jdbc = jdbc; this.json = json;
    }

    public Map<String,Object> resolve(ConsultationContextDTO ids) {
        Map<String,Object> result = new LinkedHashMap<>();
        if (ids == null) return result;
        Long shopId = ids.getShopId();
        Voucher voucher = null;
        if (ids.getVoucherId() != null) {
            voucher = vouchers.selectById(ids.getVoucherId());
            if (voucher == null || (shopId != null && !shopId.equals(voucher.getShopId())))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商品不存在或不属于当前店铺");
            shopId = voucher.getShopId();
        }
        if (shopId == null) {
            if (ids.getCategoryId() != null) result.put("categoryId", ids.getCategoryId());
            return result;
        }
        Shop shop = shops.selectById(shopId);
        if (shop == null || (ids.getCategoryId() != null && !ids.getCategoryId().equals(shop.getTypeId())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "店铺不存在或品类不匹配");
        result.put("shopId", shopId); result.put("categoryId", shop.getTypeId());
        result.put("shopName", shop.getName());
        if (voucher != null) {
            result.put("voucherId", voucher.getId()); result.put("title", voucher.getTitle());
            result.put("rules", voucher.getRules()); result.put("payValue", voucher.getPayValue());
            result.put("status", voucher.getStatus());
        }
        result.put("observedAt", java.time.Instant.now().toString());
        // Fingerprint changes on publication or disable; canonical validation still runs at read time.
        result.put("knowledgeVersion", jdbc.queryForList(
                "SELECT faq_id,active_revision,enabled FROM tb_merchant_faq WHERE shop_id=? ORDER BY faq_id", shopId));
        return result;
    }

    public Map<String,Object> forMessage(Long userId, Long chatId, ConsultationContextDTO explicit) {
        if (explicit != null) return resolve(explicit);
        List<String> rows = jdbc.query("SELECT consultation_context FROM tb_customer_chat_message " +
                        "WHERE user_id=? AND chat_id=? AND sender_type='USER' ORDER BY message_id DESC LIMIT 1",
                (rs,n) -> rs.getString(1), userId, chatId);
        Map<String,Object> previous = rows.isEmpty() ? Collections.emptyMap() : decode(rows.get(0));
        ConsultationContextDTO ids = new ConsultationContextDTO();
        ids.setShopId(number(previous.get("shopId"))); ids.setVoucherId(number(previous.get("voucherId")));
        ids.setCategoryId(number(previous.get("categoryId")));
        return resolve(ids);
    }

    /** Re-resolve the exact user-message context carried by a short-lived Agent tool token. */
    public Map<String,Object> forAgentTool(AgentToolPrincipal principal) {
        if (principal == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "缺少Agent工具身份");
        }
        List<String> rows = jdbc.query("SELECT consultation_context FROM tb_customer_chat_message " +
                        "WHERE user_id=? AND im_chat_id=? AND chat_id=? AND message_id=? AND sender_type='USER'",
                (rs,n) -> rs.getString(1), principal.getUserId(), principal.getImChatId(),
                principal.getChatId(), principal.getUserMessageId());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "未找到当前消息的商品咨询上下文");
        }
        Map<String,Object> stored = decode(rows.get(0));
        ConsultationContextDTO ids = new ConsultationContextDTO();
        ids.setShopId(number(stored.get("shopId")));
        ids.setVoucherId(number(stored.get("voucherId")));
        ids.setCategoryId(number(stored.get("categoryId")));
        return resolve(ids);
    }

    public Map<String,Object> decode(String value) {
        if (value == null || value.isBlank()) return Collections.emptyMap();
        try { return json.readValue(value, new TypeReference<Map<String,Object>>() {}); }
        catch (Exception e) { throw new IllegalStateException("商品上下文损坏", e); }
    }
    public static Long number(Object value) { return value == null ? null : Long.valueOf(value.toString()); }
}
