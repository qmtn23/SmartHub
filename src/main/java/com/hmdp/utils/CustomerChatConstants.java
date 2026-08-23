package com.hmdp.utils;

public final class CustomerChatConstants {

    private CustomerChatConstants() {
    }

    public static final String DEFAULT_IM_CHAT_TITLE = "新客服会话";

    public static final String IM_CHAT_STATUS_BOT_ACTIVE = "BOT_ACTIVE";
    public static final String IM_CHAT_STATUS_CLOSED = "CLOSED";

    public static final String HANDLER_TYPE_BOT = "BOT";

    public static final String CHAT_STATUS_ACTIVE = "ACTIVE";
    public static final String CHAT_STATUS_ENDED = "ENDED";

    public static final String SENDER_USER = "USER";
    public static final String SENDER_ASSISTANT = "ASSISTANT";
    public static final String MESSAGE_TYPE_TEXT = "TEXT";

    public static final int CHAT_INACTIVE_MINUTES = 30;
    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;
}
