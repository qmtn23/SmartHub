package com.hmdp.service;

import com.hmdp.entity.CustomerChat;
import com.hmdp.entity.CustomerImChat;

import java.time.LocalDateTime;

public interface IConversationMemoryService {

    /**
     * 结束短会话、生成本轮摘要并更新所属长会话记忆。
     */
    void finalizeChat(CustomerImChat imChat, CustomerChat chat, LocalDateTime endTime);

    /**
     * 返回可安全注入客服Agent的长期记忆文本。
     */
    String getLongTermMemory(CustomerImChat imChat);
}
