package com.hmdp.utils;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CustomerToolContext {
    private Long userId;
    private Long imChatId;
    private Long chatId;
    private Long messageId;
}
