package com.hmdp.service;

import com.hmdp.entity.CustomerChat;
import com.hmdp.entity.CustomerImChat;

import java.time.LocalDateTime;

public interface IConversationMemoryService {

    /**
     * 结束短会话并持久化后台记忆更新任务，不等待模型。
     */
    void finalizeChat(CustomerImChat imChat, CustomerChat chat, LocalDateTime endTime);

    /**
     * 返回可安全注入客服Agent的长期记忆文本。
     */
    String getLongTermMemory(CustomerImChat imChat);
}
