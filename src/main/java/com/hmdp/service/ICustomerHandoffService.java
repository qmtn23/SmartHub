package com.hmdp.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.hmdp.entity.CustomerChatMessage;
import com.hmdp.entity.CustomerHandoff;

public interface ICustomerHandoffService {

    CustomerHandoff requestHandoff(Long userId, Long imChatId, Long chatId, String reason);

    CustomerHandoff getCurrentHandoff(Long userId, Long imChatId);

    IPage<CustomerHandoff> listPending(int pageNo, int pageSize);

    IPage<CustomerChatMessage> listHumanMessages(Long handoffId, String operatorId,
                                                  int pageNo, int pageSize);

    CustomerHandoff accept(Long handoffId, String operatorId);

    CustomerChatMessage sendHumanMessage(Long handoffId, String operatorId, String content);

    CustomerHandoff complete(Long handoffId, String operatorId);
}
