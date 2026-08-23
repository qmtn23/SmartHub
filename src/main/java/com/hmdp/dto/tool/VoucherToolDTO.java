package com.hmdp.dto.tool;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class VoucherToolDTO {
    private Long voucherId;
    private Long shopId;
    private String title;
    private String subTitle;
    private String rules;
    private Long payValueCent;
    private Long actualValueCent;
    private Integer type;
    private Integer stock;
    private LocalDateTime beginTime;
    private LocalDateTime endTime;
}
