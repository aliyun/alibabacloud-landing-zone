package com.aliyun.autowonder.auth.jwt;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;

@Service
public class JwtService {
    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
    }

    public String signAccess(TokenPayload payload) {
        long now = System.currentTimeMillis();
        JwtBuilder builder = Jwts.builder()
                .setId(payload.getJti())
                .setSubject(String.valueOf(payload.getUserId()))
                .claim("uid", payload.getUserId())
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + properties.getAccessTtlSeconds() * 1000L))
                .signWith(key);
        if (payload.getCurrentWorkspaceId() != null) {
            builder.claim("workspace", payload.getCurrentWorkspaceId());
        }
        return builder.compact();
    }

    public TokenPayload parse(String token) {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(key)
                .build()
                .parseClaimsJws(token)
                .getBody();
        TokenPayload p = new TokenPayload();
        p.setJti(claims.getId());
        Number uid = claims.get("uid", Number.class);
        p.setUserId(uid == null ? null : uid.longValue());
        Number workspace = claims.get("workspace", Number.class);
        p.setCurrentWorkspaceId(workspace == null ? null : workspace.longValue());
        return p;
    }

    public String signScoped(long userId, long tenantId, String purpose, long subjectId, long ttlSeconds) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("uid", userId)
                .claim("workspace", tenantId)
                .claim("purpose", purpose)
                .claim("subjectId", subjectId)
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + ttlSeconds * 1000L))
                .signWith(key)
                .compact();
    }

    public Map<String, Object> parseScoped(String token) {
        Claims claims = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
        return Map.of(
                "uid", claims.get("uid", Number.class).longValue(),
                "workspace", claims.get("workspace", Number.class).longValue(),
                "purpose", claims.get("purpose", String.class),
                "subjectId", claims.get("subjectId", Number.class).longValue());
    }

    public String signUserPurpose(long userId, String purpose, long ttlSeconds) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .setSubject(String.valueOf(userId))
                .claim("uid", userId)
                .claim("purpose", purpose)
                .setIssuedAt(new Date(now))
                .setExpiration(new Date(now + ttlSeconds * 1000L))
                .signWith(key)
                .compact();
    }

    public Map<String, Object> parseUserPurpose(String token) {
        Claims claims = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).getBody();
        return Map.of(
                "uid", claims.get("uid", Number.class).longValue(),
                "purpose", claims.get("purpose", String.class),
                "exp", claims.getExpiration().getTime() / 1000L);
    }
}
