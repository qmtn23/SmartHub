package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ChatSessionDTO {
    private Long chatId;
    private Long imChatId;
    private String intent;
    private String summary;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime lastActiveTime;
    private LocalDateTime endTime;
}
