package com.hmdp.dto;

import lombok.Data;

@Data
public class ChatReplyDTO {
    private Long imChatId;
    private Long chatId;
    private Long userMessageId;
    private Long assistantMessageId;
    private String reply;
    private String conversationStatus;
    private String handlerType;
}
