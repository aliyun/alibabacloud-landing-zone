package com.aliyun.autowonder.user;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.jwt.TokenPayload;
import com.aliyun.autowonder.auth.session.SessionIssuanceService;
import com.aliyun.autowonder.auth.session.SessionService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

// Regression for the silent bounce to /workspaces: the workspace id is a JWT claim that AuthFilter
// reads back into the request context, so a refresh that drops it detaches an otherwise valid
// session from its workspace and every workspace-scoped call starts failing WORKSPACE_NOT_MEMBER.
class UserServiceRefreshWorkspaceTest {

    private static final long USER_ID = 42L;
    private static final long WORKSPACE_ID = 10002L;
    private static final String REFRESH_TOKEN = "valid-rt";

    private SessionService sessionService;
    private WorkspaceMemberDao workspaceMemberDao;
    private JwtService jwtService;
    private UserService service;

    @BeforeEach
    void setUp() {
        sessionService = mock(SessionService.class);
        workspaceMemberDao = mock(WorkspaceMemberDao.class);
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties props = new JwtProperties(env);
        props.setSecret("test-secret-key-that-is-long-enough-32bytes!");
        props.setAccessTtlSeconds(3600);
        jwtService = new JwtService(props);
        UserDao userDao = mock(UserDao.class);
        UserDO active = new UserDO();
        active.setId(USER_ID);
        active.setStatus(0);
        when(userDao.findById(USER_ID)).thenReturn(active);
        service = new UserService(userDao, jwtService, sessionService, props,
                workspaceMemberDao, mock(SystemAdminService.class),
                new SessionIssuanceService(jwtService, sessionService, props),
                mock(com.aliyun.autowonder.feature.PlatformFeatureSwitchService.class),
                mock(WorkspaceCreationPermissionService.class),
                mock(org.springframework.context.ApplicationEventPublisher.class));
        when(sessionService.getUserIdByRefresh(REFRESH_TOKEN)).thenReturn(USER_ID);
    }

    private WorkspaceMemberDO member(Integer status, Integer isDeleted) {
        WorkspaceMemberDO row = new WorkspaceMemberDO();
        row.setTenantId(WORKSPACE_ID);
        row.setUserId(USER_ID);
        row.setStatus(status);
        row.setIsDeleted(isDeleted);
        row.setAccessLevel("READ_WRITE");
        return row;
    }

    private Long workspaceClaimOf(String token) {
        TokenPayload payload = jwtService.parse(token);
        assertEquals(USER_ID, payload.getUserId());
        assertNotNull(payload.getJti());
        return payload.getCurrentWorkspaceId();
    }

    @Test
    void refreshKeepsWorkspaceClaimForActiveMember() {
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, USER_ID))
                .thenReturn(member(0, 0));

        String token = service.refreshAccessToken(REFRESH_TOKEN, WORKSPACE_ID);

        assertNotNull(token);
        assertEquals(Long.valueOf(WORKSPACE_ID), workspaceClaimOf(token));
    }

    @Test
    void refreshWithoutDeclaredWorkspaceOmitsClaimAndSkipsMembershipLookup() {
        String token = service.refreshAccessToken(REFRESH_TOKEN, null);

        assertNull(workspaceClaimOf(token));
        verifyNoInteractions(workspaceMemberDao);
    }

    @Test
    void refreshOmitsClaimWhenMembershipRowIsAbsent() {
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, USER_ID)).thenReturn(null);

        String token = service.refreshAccessToken(REFRESH_TOKEN, WORKSPACE_ID);

        assertNotNull(token);
        assertNull(workspaceClaimOf(token));
    }

    @Test
    void refreshOmitsClaimWhenMembershipIsSoftDeleted() {
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, USER_ID))
                .thenReturn(member(0, 1));

        assertNull(workspaceClaimOf(service.refreshAccessToken(REFRESH_TOKEN, WORKSPACE_ID)));
    }

    @Test
    void refreshOmitsClaimWhenMembershipIsDisabled() {
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, USER_ID))
                .thenReturn(member(1, 0));

        assertNull(workspaceClaimOf(service.refreshAccessToken(REFRESH_TOKEN, WORKSPACE_ID)));
    }

    @Test
    void refreshOmitsClaimWhenMembershipFlagsAreNull() {
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, USER_ID))
                .thenReturn(member(null, null));

        assertNull(workspaceClaimOf(service.refreshAccessToken(REFRESH_TOKEN, WORKSPACE_ID)));
    }

    @Test
    void refreshChecksMembershipAgainstSessionOwnerNotCallerSuppliedIdentity() {
        // A caller may declare any workspaceId, but membership is always resolved against the user
        // the refresh session authenticates — otherwise the claim could be forged for another user.
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, USER_ID))
                .thenReturn(member(0, 0));

        service.refreshAccessToken(REFRESH_TOKEN, WORKSPACE_ID);

        verify(workspaceMemberDao).findByWorkspaceAndUser(WORKSPACE_ID, USER_ID);
        verifyNoMoreInteractions(workspaceMemberDao);
    }

    @Test
    void foregroundRefreshExtendsTheRollingSessionWithTheConfiguredTtl() {
        when(sessionService.extendRefreshSession(REFRESH_TOKEN, 31536000))
                .thenReturn(SessionService.RenewalOutcome.EXTENDED);

        service.refreshAccessToken(REFRESH_TOKEN, null, true);

        verify(sessionService).extendRefreshSession(REFRESH_TOKEN, 31536000);
    }

    @Test
    void backgroundRefreshOnlyIssuesATokenAndNeverExtendsTheSession() {
        service.refreshAccessToken(REFRESH_TOKEN, null);

        verify(sessionService, never()).extendRefreshSession(anyString(), anyInt());
    }

    @Test
    void foregroundRefreshRejectsASessionRevokedBeforeRenewal() {
        when(sessionService.extendRefreshSession(REFRESH_TOKEN, 31536000))
                .thenReturn(SessionService.RenewalOutcome.SESSION_MISSING);

        BizException ex = assertThrows(BizException.class,
                () -> service.refreshAccessToken(REFRESH_TOKEN, WORKSPACE_ID, true));

        assertEquals("10401", ex.getCode());
        verifyNoInteractions(workspaceMemberDao);
    }

    @Test
    void aThrottledForegroundRefreshStillIssuesAnAccessToken() {
        when(sessionService.extendRefreshSession(REFRESH_TOKEN, 31536000))
                .thenReturn(SessionService.RenewalOutcome.THROTTLED);

        String token = service.refreshAccessToken(REFRESH_TOKEN, null, true);

        assertNotNull(token);
        assertEquals(USER_ID, jwtService.parse(token).getUserId());
    }

    @Test
    void refreshRejectsInvalidTokenBeforeTouchingMembership() {
        when(sessionService.getUserIdByRefresh("expired-rt")).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.refreshAccessToken("expired-rt", WORKSPACE_ID));

        assertEquals("10401", ex.getCode());
        verifyNoInteractions(workspaceMemberDao);
    }
}
