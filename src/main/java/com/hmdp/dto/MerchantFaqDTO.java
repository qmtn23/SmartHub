package com.hmdp.dto;

import lombok.Data;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
public class MerchantFaqDTO {
    private Integer expectedRevision;
    private String scope;
    private Long voucherId;
    private Long categoryId;
    private String question;
    private List<String> aliases = new ArrayList<>();
    private String answer;
    private String topic;
    private LocalDateTime validFrom;
    private LocalDateTime validUntil;
    @com.fasterxml.jackson.annotation.JsonAnySetter
    public void rejectUnknown(String key, Object value) { throw new IllegalArgumentException("Unknown FAQ field"); }
}
