package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ImChatDTO {
    private Long imChatId;
    private String title;
    private String primaryIntent;
    private String summary;
    private String handlerType;
    private String status;
    private LocalDateTime lastMessageTime;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private LocalDateTime closeTime;
}
