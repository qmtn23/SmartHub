package com.hmdp.security;

import com.hmdp.utils.CustomerToolContext;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AgentToolTokenService {
    private static final String AUDIENCE = "smarthub-agent-tools";
    private static final long TOKEN_TTL_SECONDS = 120;

    private final String secret;

    public AgentToolTokenService(@Value("${agent-service.tool-jwt-secret:}") String secret) {
        this.secret = secret;
    }

    public String issue(CustomerToolContext context, Set<String> scopes) {
        Instant now = Instant.now();
        return Jwts.builder()
                .setId(UUID.randomUUID().toString())
                .setSubject(String.valueOf(context.getUserId()))
                .setAudience(AUDIENCE)
                .setIssuedAt(Date.from(now))
                .setExpiration(Date.from(now.plusSeconds(TOKEN_TTL_SECONDS)))
                .claim("imChatId", context.getImChatId())
                .claim("chatId", context.getChatId())
                .claim("userMessageId", context.getMessageId())
                .claim("scopes", scopes)
                .signWith(signingKey())
                .compact();
    }

    @SuppressWarnings("unchecked")
    public AgentToolPrincipal parse(String token) {
        requireCanonicalJwt(token);
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(signingKey())
                .requireAudience(AUDIENCE)
                .build()
                .parseClaimsJws(token)
                .getBody();
        Object rawScopes = claims.get("scopes");
        Set<String> scopes = rawScopes instanceof List
                ? new HashSet<>((List<String>) rawScopes)
                : new HashSet<>();
        return new AgentToolPrincipal(
                Long.valueOf(claims.getSubject()),
                numberClaim(claims, "imChatId"),
                numberClaim(claims, "chatId"),
                numberClaim(claims, "userMessageId"),
                scopes);
    }

    private void requireCanonicalJwt(String token) {
        if (token == null) {
            throw new IllegalArgumentException("工具令牌不能为空");
        }
        String[] parts = token.split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException("工具令牌格式非法");
        }
        Base64.Decoder decoder = Base64.getUrlDecoder();
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        for (String part : parts) {
            if (part.isEmpty() || !encoder.encodeToString(decoder.decode(part)).equals(part)) {
                throw new IllegalArgumentException("工具令牌不是规范Base64URL编码");
            }
        }
    }

    private Long numberClaim(Claims claims, String name) {
        Number value = claims.get(name, Number.class);
        if (value == null) {
            throw new IllegalArgumentException("工具令牌缺少" + name);
        }
        return value.longValue();
    }

    private SecretKey signingKey() {
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("AGENT_TOOL_JWT_SECRET至少需要32字节");
        }
        return Keys.hmacShaKeyFor(bytes);
    }
}
