package com.hmdp.service;

import com.hmdp.config.ChatBusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class OperatorAuthService {

    private final String configuredKey;

    public OperatorAuthService(@Value("${customer-service.operator-key:}") String configuredKey) {
        this.configuredKey = configuredKey == null ? "" : configuredKey.trim();
    }

    public void verify(String providedKey) {
        if (configuredKey.isBlank()) {
            throw new ChatBusinessException("人工客服接口未启用");
        }
        if (providedKey == null || !MessageDigest.isEqual(
                configuredKey.getBytes(StandardCharsets.UTF_8),
                providedKey.getBytes(StandardCharsets.UTF_8))) {
            throw new ChatBusinessException("人工客服接口认证失败");
        }
    }
}
