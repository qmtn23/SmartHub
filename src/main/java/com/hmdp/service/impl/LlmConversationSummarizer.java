package com.hmdp.service.impl;

import com.hmdp.entity.CustomerChatMessage;
import com.hmdp.service.CustomerAgentClient;
import com.hmdp.service.ConversationSummarizer;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class LlmConversationSummarizer implements ConversationSummarizer {

    private final CustomerAgentClient customerAgentClient;

    public LlmConversationSummarizer(CustomerAgentClient customerAgentClient) {
        this.customerAgentClient = customerAgentClient;
    }

    @Override
    public String summarizeSession(List<CustomerChatMessage> messages) {
        return customerAgentClient.summarizeSession(messages);
    }

    @Override
    public String mergeLongTerm(String previousSummary, String sessionSummary) {
        return customerAgentClient.mergeLongTermMemory(previousSummary, sessionSummary);
    }
}
