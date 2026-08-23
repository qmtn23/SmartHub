package com.hmdp.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hmdp.dto.ChatReplyDTO;
import com.hmdp.dto.ChatRequest;
import com.hmdp.entity.CustomerChat;
import com.hmdp.entity.CustomerChatMessage;
import com.hmdp.entity.CustomerImChat;

public interface ICustomerChatService {

    CustomerImChat createImChat(Long userId, String title);

    IPage<CustomerImChat> listImChats(Long userId, int pageNo, int pageSize);

    CustomerImChat getImChat(Long userId, Long imChatId);

    CustomerChat createOrResumeChat(Long userId, Long imChatId);

    ChatReplyDTO sendMessage(Long userId, ChatRequest request);

    IPage<CustomerChatMessage> listMessages(Long userId, Long imChatId, int pageNo, int pageSize);

    void closeImChat(Long userId, Long imChatId);
}
