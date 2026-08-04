package com.aliyun.autowonder.user;

import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.jwt.TokenPayload;
import com.aliyun.autowonder.auth.session.SessionService;
import com.aliyun.autowonder.common.error.BizException;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceTest {

    private UserService newService(SessionService sessionService, JwtService jwtService) {
        UserDao userDao = mock(UserDao.class);
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties jwtProperties = new JwtProperties(env);
        jwtProperties.setSecret("test-secret-key-that-is-long-enough-32bytes!");
        jwtProperties.setAccessTtlSeconds(3600);
        return new UserService(userDao, jwtService, sessionService, jwtProperties);
    }

    @Test
    void refreshWithValidTokenReturnsNewAccessToken() {
        SessionService session = mock(SessionService.class);
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties props = new JwtProperties(env);
        props.setSecret("test-secret-key-that-is-long-enough-32bytes!");
        props.setAccessTtlSeconds(3600);
        JwtService jwt = new JwtService(props);
        UserService svc = new UserService(mock(UserDao.class), jwt, session, props);

        when(session.getUserIdByRefresh("valid-rt")).thenReturn(42L);

        String newToken = svc.refreshAccessToken("valid-rt");
        assertNotNull(newToken);

        TokenPayload payload = jwt.parse(newToken);
        assertEquals(42L, payload.getUserId());
        assertNotNull(payload.getJti());
    }

    @Test
    void refreshWithNullTokenThrows() {
        SessionService session = mock(SessionService.class);
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties props = new JwtProperties(env);
        props.setSecret("test-secret-key-that-is-long-enough-32bytes!");
        JwtService jwt = new JwtService(props);
        UserService svc = new UserService(mock(UserDao.class), jwt, session, props);

        BizException ex = assertThrows(BizException.class, () -> svc.refreshAccessToken(null));
        assertEquals("10001", ex.getCode());
    }

    @Test
    void refreshWithBlankTokenThrows() {
        SessionService session = mock(SessionService.class);
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties props = new JwtProperties(env);
        props.setSecret("test-secret-key-that-is-long-enough-32bytes!");
        JwtService jwt = new JwtService(props);
        UserService svc = new UserService(mock(UserDao.class), jwt, session, props);

        BizException ex = assertThrows(BizException.class, () -> svc.refreshAccessToken("  "));
        assertEquals("10001", ex.getCode());
    }

    @Test
    void refreshWithExpiredTokenThrowsUnauthorized() {
        SessionService session = mock(SessionService.class);
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties props = new JwtProperties(env);
        props.setSecret("test-secret-key-that-is-long-enough-32bytes!");
        JwtService jwt = new JwtService(props);
        UserService svc = new UserService(mock(UserDao.class), jwt, session, props);

        when(session.getUserIdByRefresh("expired-rt")).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> svc.refreshAccessToken("expired-rt"));
        assertEquals("10401", ex.getCode());
    }
}
