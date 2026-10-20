package com.aliyun.autowonder.user;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.jwt.TokenPayload;
import com.aliyun.autowonder.auth.session.SessionIssuanceService;
import com.aliyun.autowonder.auth.session.SessionService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
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
        return new UserService(userDao, jwtService, sessionService, jwtProperties,
                mock(WorkspaceMemberDao.class), mock(SystemAdminService.class),
                new SessionIssuanceService(jwtService, sessionService, jwtProperties));
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
        UserService svc = new UserService(mock(UserDao.class), jwt, session, props,
                mock(WorkspaceMemberDao.class), mock(SystemAdminService.class),
                new SessionIssuanceService(jwt, session, props));

        when(session.getUserIdByRefresh("valid-rt")).thenReturn(42L);

        String newToken = svc.refreshAccessToken("valid-rt", null);
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
        UserService svc = new UserService(mock(UserDao.class), jwt, session, props,
                mock(WorkspaceMemberDao.class), mock(SystemAdminService.class),
                new SessionIssuanceService(jwt, session, props));

        BizException ex = assertThrows(BizException.class, () -> svc.refreshAccessToken(null, null));
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
        UserService svc = new UserService(mock(UserDao.class), jwt, session, props,
                mock(WorkspaceMemberDao.class), mock(SystemAdminService.class),
                new SessionIssuanceService(jwt, session, props));

        BizException ex = assertThrows(BizException.class, () -> svc.refreshAccessToken("  ", null));
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
        UserService svc = new UserService(mock(UserDao.class), jwt, session, props,
                mock(WorkspaceMemberDao.class), mock(SystemAdminService.class),
                new SessionIssuanceService(jwt, session, props));

        when(session.getUserIdByRefresh("expired-rt")).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> svc.refreshAccessToken("expired-rt", null));
        assertEquals("10401", ex.getCode());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void refreshFailureRendersEnglishWithoutTokenOrMembershipWrites(boolean expired) {
        SessionService session = mock(SessionService.class);
        JwtService jwt = mock(JwtService.class);
        var svc = newService(session, jwt);
        if (expired) when(session.getUserIdByRefresh("expired-rt")).thenReturn(null);
        BizException error = assertThrows(BizException.class,
                () -> svc.refreshAccessToken(expired ? "expired-rt" : " ", 99L));
        String diagnostic = expired ? "刷新令牌无效或已过期" : "refreshToken 不能为空";
        assertEquals(BizException.class, error.getClass());
        assertEquals(expired ? "10401" : "10001", error.getCode());
        assertEquals(diagnostic, error.getMessage());
        assertNull(error.getCause());
        verifyNoInteractions(jwt);
        if (expired) verify(session).getUserIdByRefresh("expired-rt");
        else verifyNoInteractions(session);
        assertEquals(expired ? 401 : 400,
                new com.aliyun.autowonder.common.web.GlobalExceptionHandler().handleBiz(error).getStatusCodeValue());
        assertEquals(diagnostic, com.aliyun.autowonder.common.i18n.SystemMessages.render(
                error.getMessageKey(), error.getMessageArgs(), error.getMessage(), java.util.Locale.CHINA));
        assertEquals(expired ? "Refresh token is invalid or expired." : "refreshToken is required.",
                com.aliyun.autowonder.common.i18n.SystemMessages.render(
                        error.getMessageKey(), error.getMessageArgs(), error.getMessage(), java.util.Locale.US));
    }

}
