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
        assertEquals(987654321098765L, out.getCurrentWorkspaceId());
        assertEquals("jti-1", out.getJti());
    }

    @Test
    void parsesNullWorkspaceWhenAbsent() {
        JwtService svc = newService();
        String token = svc.signAccess(new TokenPayload(42L, null, "jti-2"));
        TokenPayload out = svc.parse(token);
        assertEquals(42L, out.getUserId());
        assertNull(out.getCurrentWorkspaceId());
    }

    @Test
    void userPurposeRoundTripCarriesNoOrgOrWorkitemClaims() {
        JwtService svc = newService();
        String token = svc.signUserPurpose(42L, "workitem-requirement-upload", 1800);
        var claims = svc.parseUserPurpose(token);
        assertEquals(42L, claims.get("uid"));
        assertEquals("workitem-requirement-upload", claims.get("purpose"));
        assertFalse(claims.containsKey("workspace"));
        assertFalse(claims.containsKey("subjectId"));
        assertTrue(((Number) claims.get("exp")).longValue() > System.currentTimeMillis() / 1000L);
    }

    @Test
    void userPurposeTokenHasNoWorkspaceClaimUnderAccessParsing() {
        JwtService svc = newService();
        String token = svc.signUserPurpose(42L, "workitem-requirement-upload", 1800);
        assertNull(svc.parse(token).getCurrentWorkspaceId());
    }

    @Test
    void userPurposeExpiredTokenIsRejected() {
        JwtService svc = newService();
        String token = svc.signUserPurpose(42L, "workitem-requirement-upload", -1);
        assertThrows(io.jsonwebtoken.JwtException.class, () -> svc.parseUserPurpose(token));
    }

    @Test
    void userPurposeTamperedTokenIsRejected() {
        JwtService svc = newService();
        String token = svc.signUserPurpose(42L, "workitem-requirement-upload", 1800);
        String tampered = token.substring(0, token.length() - 4) + "AAAA";
        assertThrows(io.jsonwebtoken.JwtException.class, () -> svc.parseUserPurpose(tampered));
    }
}
