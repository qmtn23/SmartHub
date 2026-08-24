package com.hmdp.dto;

import lombok.Data;

@Data
public class TransferHumanRequest {
    private Long chatId;
    private String reason;
}
