package com.hmdp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** IDs only. All product facts are loaded on the server. An empty object clears context. */
@Data
@JsonIgnoreProperties(ignoreUnknown = false)
public class ConsultationContextDTO {
    private Long shopId;
    private Long voucherId;
    private Long categoryId;
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("商品上下文仅允许shopId/voucherId/categoryId"); }
}
