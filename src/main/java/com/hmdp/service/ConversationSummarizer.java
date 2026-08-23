package com.hmdp.service;

import com.hmdp.entity.CustomerChatMessage;

import java.util.List;

/**
 * 将短会话压缩为摘要，并合并为长会话记忆。
 */
public interface ConversationSummarizer {

    String summarizeSession(List<CustomerChatMessage> messages);

    String mergeLongTerm(String previousSummary, String sessionSummary);
}
