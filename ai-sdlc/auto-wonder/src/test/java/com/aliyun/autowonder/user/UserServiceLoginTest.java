package com.aliyun.autowonder.user;

import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.jwt.TokenPayload;
import com.aliyun.autowonder.auth.session.SessionService;
import com.aliyun.autowonder.common.crypto.PasswordEncoderUtil;
import com.aliyun.autowonder.common.error.BizException;

import com.aliyun.autowonder.user.dto.LoginRequest;
import com.aliyun.autowonder.user.dto.LoginResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceLoginTest {

    private UserDao userDao;
    private SessionService sessionService;
    private JwtService jwtService;
    private UserService service;

    @BeforeEach
    void setUp() {
        userDao = mock(UserDao.class);
        sessionService = mock(SessionService.class);
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties props = new JwtProperties(env);
        props.setSecret("test-secret-key-that-is-long-enough-32bytes!");
        jwtService = new JwtService(props);
        service = new UserService(userDao, jwtService, sessionService, props);
    }

    @Test
    void login_returns_tokens_and_access_carries_userId() {
        UserDO u = new UserDO();
        u.setId(42L);
        u.setUsername("alice");
        u.setNickname("Alice Chen");
        u.setEmail("alice@example.com");
        u.setPasswordHash(PasswordEncoderUtil.encode("secret123"));
        u.setStatus(0);
        when(userDao.findByUsername("alice")).thenReturn(u);

        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword("secret123");

        LoginResponse resp = service.login(req);

        assertEquals(42L, resp.getUserId());
        assertNotNull(resp.getUser());
        assertEquals(42L, resp.getUser().getId());
        assertEquals("alice", resp.getUser().getUsername());
        assertEquals("Alice Chen", resp.getUser().getNickname());
        assertEquals("alice@example.com", resp.getUser().getEmail());
        assertNotNull(resp.getAccessToken());
        assertNotNull(resp.getRefreshToken());
        TokenPayload payload = jwtService.parse(resp.getAccessToken());
        assertEquals(42L, payload.getUserId());
        assertNull(payload.getCurrentWorkspaceId());
        verify(sessionService).storeRefresh(eq(resp.getRefreshToken()), eq(42L), anyInt());
    }

    @Test
    void login_wrong_password_throws_unauthorized() {
        UserDO u = new UserDO();
        u.setId(42L);
        u.setUsername("alice");
        u.setPasswordHash(PasswordEncoderUtil.encode("secret123"));
        u.setStatus(0);
        when(userDao.findByUsername("alice")).thenReturn(u);

        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword("wrong");

        BizException ex = assertThrows(BizException.class, () -> service.login(req));
        assertEquals("10401", ex.getCode());
    }

    @Test
    void login_unknown_user_throws_unauthorized() {
        when(userDao.findByUsername("ghost")).thenReturn(null);
        LoginRequest req = new LoginRequest();
        req.setUsername("ghost");
        req.setPassword("x");
        BizException ex = assertThrows(BizException.class, () -> service.login(req));
        assertEquals("10401", ex.getCode());
    }
}
