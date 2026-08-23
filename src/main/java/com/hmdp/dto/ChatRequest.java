package com.hmdp.dto;

import lombok.Data;

@Data
public class ChatRequest {
    /**
     * 长会话ID。
     */
    private Long imChatId;

    /**
     * 当前短会话ID。
     */
    private Long chatId;

    /**
     * 客户端消息ID，用于避免重复发送。
     */
    private String clientMessageId;

    private String message;
}
