package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class HandoffDTO {
    private Long handoffId;
    private Long imChatId;
    private Long fromChatId;
    private Long humanChatId;
    private String reason;
    private String summary;
    private String businessRefs;
    private String status;
    private String operatorId;
    private LocalDateTime requestedTime;
    private LocalDateTime acceptedTime;
    private LocalDateTime completedTime;
    private LocalDateTime updateTime;
}
