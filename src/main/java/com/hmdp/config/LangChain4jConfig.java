package com.hmdp.config;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LangChain4jConfig {

    @Value("${dashscope.api-key}")
    private String apiKey;

    @Value("${dashscope.chat-model}")
    private String chatModelName;

    @Bean
    public ChatModel qwenChatModel() {
        return QwenChatModel.builder()
                .apiKey(apiKey)
                .modelName(chatModelName)
                .build();
    }

}
