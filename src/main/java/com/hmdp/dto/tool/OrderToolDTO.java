package com.hmdp.dto.tool;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class OrderToolDTO {
    private Long orderId;
    private Long voucherId;
    private Integer payType;
    private Integer status;
    private String statusText;
    private LocalDateTime createTime;
    private LocalDateTime payTime;
    private LocalDateTime useTime;
    private LocalDateTime refundTime;
}
