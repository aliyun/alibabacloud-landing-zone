package com.aliyun.autowonder.user;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.auth.federation.PlatformAuthConfigService;
import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.jwt.TokenPayload;
import com.aliyun.autowonder.auth.session.SessionIssuanceService;
import com.aliyun.autowonder.auth.session.SessionService;
import com.aliyun.autowonder.common.crypto.PasswordEncoderUtil;
import com.aliyun.autowonder.common.error.BizException;

import com.aliyun.autowonder.user.dto.LoginRequest;
import com.aliyun.autowonder.user.dto.LoginResponse;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceLoginTest {

    private UserDao userDao;
    private SessionService sessionService;
    private JwtService jwtService;
    private WorkspaceCreationPermissionService permissions;
    private UserService service;

    @BeforeEach
    void setUp() {
        userDao = mock(UserDao.class);
        sessionService = mock(SessionService.class);
        permissions = mock(WorkspaceCreationPermissionService.class);
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties props = new JwtProperties(env);
        props.setSecret("test-secret-key-that-is-long-enough-32bytes!");
        jwtService = new JwtService(props);
        service = new UserService(userDao, jwtService, sessionService, props,
                mock(WorkspaceMemberDao.class), mock(SystemAdminService.class),
                new SessionIssuanceService(jwtService, sessionService, props),
                mock(com.aliyun.autowonder.feature.PlatformFeatureSwitchService.class),
                permissions,
                mock(org.springframework.context.ApplicationEventPublisher.class));
    }

    private static UserDO pendingAccount() {
        UserDO user = new UserDO();
        user.setId(42L);
        user.setUsername("alice");
        user.setPasswordHash(PasswordEncoderUtil.encode("secret123"));
        user.setStatus(UserAccountStates.STATUS_PENDING_REVIEW);
        return user;
    }

    @Test
    void pendingAccountWithCorrectCredentialsIsRejectedWithoutIssuingASession() {
        when(userDao.findByUsername("alice")).thenReturn(pendingAccount());
        when(permissions.availableAdministratorContacts())
                .thenReturn(java.util.List.of(new com.aliyun.autowonder.user.dto.AdministratorContactVO("周宁", "zhou@example.com")));
        LoginRequest req = new LoginRequest();
        req.setUsername("alice");
        req.setPassword("secret123");

        RegistrationReviewPendingException error = assertThrows(
                RegistrationReviewPendingException.class, () -> service.login(req));

        assertEquals("37012", error.getCode());
        assertEquals(1, error.getAdministratorContacts().size());
        verifyNoInteractions(sessionService);
    }

    @Test
    void refreshTokenOfAPendingAccountIsRejectedBeforeSigningANewAccessToken() {
        when(sessionService.getUserIdByRefresh("pending-rt")).thenReturn(42L);
        when(userDao.findById(42L)).thenReturn(pendingAccount());
        when(permissions.availableAdministratorContacts()).thenReturn(java.util.List.of());

        RegistrationReviewPendingException error = assertThrows(
                RegistrationReviewPendingException.class,
                () -> service.refreshAccessToken("pending-rt", null));

        assertEquals("37012", error.getCode());
        verify(sessionService, never()).extendRefreshSession(anyString(), anyInt());
    }

    @Test
    void refreshTokenOfAMissingOrDeactivatedAccountIsRejected() {
        when(sessionService.getUserIdByRefresh("gone-rt")).thenReturn(42L);
        when(userDao.findById(42L)).thenReturn(null);
        assertEquals("10401", assertThrows(BizException.class,
                () -> service.refreshAccessToken("gone-rt", null)).getCode());

        UserDO deactivated = pendingAccount();
        deactivated.setStatus(1);
        deactivated.setPasswordHash("DEACTIVATED");
        when(sessionService.getUserIdByRefresh("deactivated-rt")).thenReturn(42L);
        when(userDao.findById(42L)).thenReturn(deactivated);
        assertEquals("29007", assertThrows(BizException.class,
                () -> service.refreshAccessToken("deactivated-rt", null)).getCode());
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
        u.setIsAdmin(1);
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
        // The frontend renders the 平台配置 entry from this flag instead of re-deriving
        // "first user" semantics client-side.
        assertEquals(Boolean.TRUE, resp.getUser().getIsAdmin());
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"zh-CN", "en-US"})
    void wrongCredentialsKeepSpecificLogin401WithoutCreatingSession(String language) throws Exception {
        var request = new LoginRequest();
        request.setUsername("ghost");
        request.setPassword("raw-password");
        BizException error = assertThrows(BizException.class, () -> service.login(request));
        assertEquals(BizException.class, error.getClass());
        assertEquals("10401", error.getCode());
        assertEquals("用户名或密码错误", error.getMessage());
        assertNull(error.getCause());
        verifyNoInteractions(sessionService);
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(new AuthController(service, openRegistration()))
                .setControllerAdvice(new com.aliyun.autowonder.common.web.GlobalExceptionHandler())
                .setLocaleResolver(new com.aliyun.autowonder.auth.filter.AuthFilterConfig().localeResolver()).build();
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/auth/login")
                        .header("Accept-Language", language).contentType("application/json")
                        .content("{\"username\":\"ghost\",\"password\":\"raw-password\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value("10401"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.success").value(false))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.message").value(
                        language.equals("en-US") ? "Incorrect username or password." : "用户名或密码错误"));
        verifyNoInteractions(sessionService);
    }

    @Test
    void rawSameLookingCredentialDiagnosticHasNoTranslationIntent() {
        var raw = new BizException(com.aliyun.autowonder.common.error.ErrorCode.UNAUTHORIZED, "用户名或密码错误");
        assertNull(raw.getMessageKey());
        assertEquals("用户名或密码错误", com.aliyun.autowonder.common.i18n.SystemMessages.render(
                raw.getMessageKey(), raw.getMessageArgs(), raw.getMessage(), java.util.Locale.US));
    }

    private static PlatformAuthConfigService openRegistration() {
        PlatformAuthConfigService config = mock(PlatformAuthConfigService.class);
        when(config.registrationEnabled()).thenReturn(true);
        return config;
    }

}
