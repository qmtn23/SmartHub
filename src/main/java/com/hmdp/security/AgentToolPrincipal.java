package com.hmdp.security;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Set;

@Data
@AllArgsConstructor
public class AgentToolPrincipal {
    private Long userId;
    private Long imChatId;
    private Long chatId;
    private Long userMessageId;
    private Set<String> scopes;

    public boolean hasScope(String scope) {
        return scopes != null && scopes.contains(scope);
    }
}
