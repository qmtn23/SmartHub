package com.hmdp.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ChatMessageDTO {
    private Long messageId;
    private Long imChatId;
    private Long chatId;
    private String senderType;
    private String messageType;
    private String content;
    private String structuredContent;
    private Long replyToMessageId;
    private LocalDateTime createTime;
}
