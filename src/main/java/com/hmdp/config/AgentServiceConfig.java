package com.hmdp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration
public class AgentServiceConfig {

    @Bean("agentRestTemplate")
    public RestTemplate agentRestTemplate(RestTemplateBuilder builder,
                                           @Value("${agent-service.connect-timeout-ms:2000}") int connectTimeout,
                                           @Value("${agent-service.read-timeout-ms:45000}") int readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return builder.requestFactory(() -> factory).build();
    }
}
