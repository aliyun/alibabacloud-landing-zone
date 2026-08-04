package com.aliyun.autowonder.auth.jwt;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JwtServiceTest {
    private JwtService newService() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties props = new JwtProperties(env);
        props.setSecret("test-secret-key-that-is-long-enough-32bytes!");
        props.setAccessTtlSeconds(3600);
        props.setRefreshTtlSeconds(7200);
        return new JwtService(props);
    }

    @Test
    void signAndParseRoundTrip() {
        JwtService svc = newService();
        TokenPayload in = new TokenPayload(123456789012345L, 987654321098765L, "jti-1");
        String token = svc.signAccess(in);
        assertNotNull(token);
        TokenPayload out = svc.parse(token);
        assertEquals(123456789012345L, out.getUserId());
        assertEquals(987654321098765L, out.getCurrentOrgId());
        assertEquals("jti-1", out.getJti());
    }

    @Test
    void parsesNullOrgWhenAbsent() {
        JwtService svc = newService();
        String token = svc.signAccess(new TokenPayload(42L, null, "jti-2"));
        TokenPayload out = svc.parse(token);
        assertEquals(42L, out.getUserId());
        assertNull(out.getCurrentOrgId());
    }
}
