package com.aliyun.autowonder.auth.filter;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.jwt.TokenPayload;
import com.aliyun.autowonder.auth.session.SessionService;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.log.BizLog;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.user.UserDO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthFilterTest {

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    private JwtService newJwtService() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties props = new JwtProperties(env);
        props.setSecret("test-secret-key-that-is-long-enough-32bytes!");
        return new JwtService(props);
    }

    // AuthFilter reads org state on every workspace-scoped request, so the default stub has to
    // report "usable": a bare mock returns 0 and would reject every membership test on 11005
    // before it ever reached the member lookup.
    private WorkspaceDao usableWorkspaceDao() {
        WorkspaceDao workspaceDao = mock(WorkspaceDao.class);
        when(workspaceDao.countUsable(anyLong())).thenReturn(1L);
        return workspaceDao;
    }

    @Test
    void whitelisted_path_passes_without_token() throws Exception {
        JwtService jwtService = newJwtService();
        SessionService sessionService = mock(SessionService.class);
        WorkspaceMemberDao workspaceMemberDao = mock(WorkspaceMemberDao.class);
        AuthFilter filter = new AuthFilter(jwtService, sessionService, workspaceMemberDao, usableWorkspaceDao(), mock(UserDao.class));

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus());
        assertNotNull(chain.getRequest()); // 链继续
        verifyNoInteractions(workspaceMemberDao);
    }

    @Test
    void mcp_root_path_passes_without_platform_jwt() throws Exception {
        JwtService jwtService = newJwtService();
        SessionService sessionService = mock(SessionService.class);
        AuthFilter filter = new AuthFilter(jwtService, sessionService, mock(WorkspaceMemberDao.class), usableWorkspaceDao(), mock(UserDao.class));

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/mcp");
        req.setQueryString("token=awmcp_secret");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus());
        assertNotNull(chain.getRequest());

        MockHttpServletRequest postReq = new MockHttpServletRequest("POST",
                "/api/integrations/capabilities");
        MockHttpServletResponse postResp = new MockHttpServletResponse();
        MockFilterChain postChain = new MockFilterChain();

        filter.doFilter(postReq, postResp, postChain);

        assertEquals(401, postResp.getStatus());
        assertNull(postChain.getRequest());
    }

    @Test
    void mcp_token_management_path_still_requires_platform_jwt() throws Exception {
        JwtService jwtService = newJwtService();
        SessionService sessionService = mock(SessionService.class);
        AuthFilter filter = new AuthFilter(jwtService, sessionService, mock(WorkspaceMemberDao.class), usableWorkspaceDao(), mock(UserDao.class));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/mcp/tokens");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void mcp_path_token_urls_pass_without_platform_jwt() throws Exception {
        AuthFilter filter = new AuthFilter(newJwtService(), mock(SessionService.class),
                mock(WorkspaceMemberDao.class), usableWorkspaceDao(), mock(UserDao.class));
        String token = "awmcp_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8S9t0UvW";

        for (String[] probe : new String[][]{
                {"GET", "/api/mcp/" + token},
                {"GET", "/api/mcp/" + token + "/"},
                {"POST", "/api/mcp/" + token},
                {"POST", "/api/mcp/" + token + "/"},
                {"POST", "/api/mcp/" + token + "/rpc"},
                {"POST", "/api/mcp/" + token + "/rpc/"}}) {
            MockHttpServletRequest req = new MockHttpServletRequest(probe[0], probe[1]);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, resp, chain);

            assertEquals(200, resp.getStatus(), probe[0] + " " + probe[1]);
            assertNotNull(chain.getRequest(), probe[0] + " " + probe[1]);
        }
    }

    @Test
    void mcp_urls_without_valid_personal_token_shape_still_require_platform_jwt() throws Exception {
        AuthFilter filter = new AuthFilter(newJwtService(), mock(SessionService.class),
                mock(WorkspaceMemberDao.class), usableWorkspaceDao(), mock(UserDao.class));
        String token = "awmcp_A1b2C3d4E5f6G7h8I9j0K1l2M3n4O5p6Q7r8S9t0UvW";

        for (String path : new String[]{
                "/api/mcp/awmcp_tooshort",
                "/api/mcp/not-a-token",
                "/api/mcp/" + token + "/unknown",
                "/api/mcp/" + token + "/rpc/extra"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, resp, chain);

            assertEquals(401, resp.getStatus(), path);
            assertNull(chain.getRequest(), path);
        }
    }

    @Test
    void feishuOnlyExactPostCallbackBypassesPlatformJwt() throws Exception {
        AuthFilter filter = new AuthFilter(newJwtService(), mock(SessionService.class),
                mock(WorkspaceMemberDao.class), usableWorkspaceDao(), mock(UserDao.class));
        String[][] routes = {{"POST", "/api/integrations/feishu/callback", "200"},
                {"GET", "/api/integrations/feishu/callback", "401"},
                {"POST", "/api/integrations/feishu/callback/extra", "401"},
                {"POST", "/api/integrations/feishu/bindings", "401"},
                {"GET", "/api/integrations/feishu/bindings", "401"}};
        for (String[] route : routes) {
            var request = new MockHttpServletRequest(route[0], route[1]);
            var response = new MockHttpServletResponse();
            filter.doFilter(request, response, new MockFilterChain());
            assertEquals(Integer.parseInt(route[2]), response.getStatus(), route[0] + " " + route[1]);
        }
    }

    @Test
    void dingtalk_callback_passes_without_token() throws Exception {
        JwtService jwtService = newJwtService();
        SessionService sessionService = mock(SessionService.class);
        AuthFilter filter = new AuthFilter(jwtService, sessionService, mock(WorkspaceMemberDao.class), usableWorkspaceDao(), mock(UserDao.class));

        MockHttpServletRequest req = new MockHttpServletRequest("POST",
                "/api/integrations/dingtalk/callback");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void dingtalk_non_callback_integration_still_requires_token() throws Exception {
        JwtService jwtService = newJwtService();
        SessionService sessionService = mock(SessionService.class);
        AuthFilter filter = new AuthFilter(jwtService, sessionService, mock(WorkspaceMemberDao.class), usableWorkspaceDao(), mock(UserDao.class));

        MockHttpServletRequest req = new MockHttpServletRequest("GET",
                "/api/integrations/dingtalk/bindings");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertNull(chain.getRequest());
    }

    @Test
    void integration_capabilities_are_public_read_only_metadata() throws Exception {
        AuthFilter filter = new AuthFilter(newJwtService(), mock(SessionService.class),
                mock(WorkspaceMemberDao.class), usableWorkspaceDao(), mock(UserDao.class));

        MockHttpServletRequest req = new MockHttpServletRequest("GET",
                "/api/integrations/capabilities");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void branding_public_reads_are_whitelisted_but_logo_upload_requires_token() throws Exception {
        JwtService jwtService = newJwtService();
        SessionService sessionService = mock(SessionService.class);
        AuthFilter filter = new AuthFilter(jwtService, sessionService, mock(WorkspaceMemberDao.class), usableWorkspaceDao(), mock(UserDao.class));

        MockHttpServletRequest publicReq = new MockHttpServletRequest("GET", "/api/platform/branding/public");
        MockHttpServletResponse publicResp = new MockHttpServletResponse();
        MockFilterChain publicChain = new MockFilterChain();
        filter.doFilter(publicReq, publicResp, publicChain);
        assertEquals(200, publicResp.getStatus());
        assertNotNull(publicChain.getRequest());

        MockHttpServletRequest logoGetReq = new MockHttpServletRequest("GET", "/api/platform/branding/logo");
        MockHttpServletResponse logoGetResp = new MockHttpServletResponse();
        MockFilterChain logoGetChain = new MockFilterChain();
        filter.doFilter(logoGetReq, logoGetResp, logoGetChain);
        assertEquals(200, logoGetResp.getStatus());
        assertNotNull(logoGetChain.getRequest());

        MockHttpServletRequest logoPostReq = new MockHttpServletRequest("POST", "/api/platform/branding/logo");
        MockHttpServletResponse logoPostResp = new MockHttpServletResponse();
        MockFilterChain logoPostChain = new MockFilterChain();
        filter.doFilter(logoPostReq, logoPostResp, logoPostChain);
        assertEquals(401, logoPostResp.getStatus());
        assertNull(logoPostChain.getRequest());
    }

    @Test
    void cli_workitem_upload_post_passes_without_session_token() throws Exception {
        AuthFilter filter = new AuthFilter(newJwtService(), mock(SessionService.class),
                mock(WorkspaceMemberDao.class), usableWorkspaceDao(), mock(UserDao.class));

        MockHttpServletRequest req = new MockHttpServletRequest("POST",
                "/api/cli/workitems/50063/requirement-documents");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void cli_workitem_upload_non_post_methods_still_require_session_token() throws Exception {
        AuthFilter filter = new AuthFilter(newJwtService(), mock(SessionService.class),
                mock(WorkspaceMemberDao.class), usableWorkspaceDao(), mock(UserDao.class));

        for (String method : new String[]{"GET", "PUT", "DELETE"}) {
            MockHttpServletRequest req = new MockHttpServletRequest(method,
                    "/api/cli/workitems/50063/requirement-documents");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, resp, chain);

            assertEquals(401, resp.getStatus(), method);
            assertNull(chain.getRequest(), method);
        }
    }

    @Test
    void cli_scheduled_task_upload_post_passes_without_session_token() throws Exception {
        AuthFilter filter = new AuthFilter(newJwtService(), mock(SessionService.class),
                mock(WorkspaceMemberDao.class), usableWorkspaceDao(), mock(UserDao.class));

        MockHttpServletRequest req = new MockHttpServletRequest("POST",
                "/api/cli/scheduled-tasks/321/documents");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus());
        assertNotNull(chain.getRequest());
    }

    @Test
    void cli_scheduled_task_upload_non_post_methods_still_require_session_token() throws Exception {
        AuthFilter filter = new AuthFilter(newJwtService(), mock(SessionService.class),
                mock(WorkspaceMemberDao.class), usableWorkspaceDao(), mock(UserDao.class));

        for (String method : new String[]{"GET", "PUT", "DELETE"}) {
            MockHttpServletRequest req = new MockHttpServletRequest(method,
                    "/api/cli/scheduled-tasks/321/documents");
            MockHttpServletResponse resp = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, resp, chain);

            assertEquals(401, resp.getStatus(), method);
            assertNull(chain.getRequest(), method);
        }
    }

    @Test
    void cli_workitem_download_get_passes_without_session_token() throws Exception {
        AuthFilter filter = new AuthFilter(newJwtService(), mock(SessionService.class),
                mock(WorkspaceMemberDao.class), usableWorkspaceDao(), mock(UserDao.class));

        for (String path : new String[]{
                "/api/cli/workitems/50063/requirement-documents/index",
                "/api/cli/workitems/50063/requirement-documents/77/content"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, resp, chain);

            assertEquals(200, resp.getStatus(), path);
            assertNotNull(chain.getRequest(), path);
        }
    }

    @Test
    void cli_workitem_download_non_get_methods_still_require_session_token() throws Exception {
        AuthFilter filter = new AuthFilter(newJwtService(), mock(SessionService.class),
                mock(WorkspaceMemberDao.class), usableWorkspaceDao(), mock(UserDao.class));

        for (String method : new String[]{"POST", "PUT", "DELETE"}) {
            for (String path : new String[]{
                    "/api/cli/workitems/50063/requirement-documents/index",
                    "/api/cli/workitems/50063/requirement-documents/77/content"}) {
                MockHttpServletRequest req = new MockHttpServletRequest(method, path);
                MockHttpServletResponse resp = new MockHttpServletResponse();
                MockFilterChain chain = new MockFilterChain();

                filter.doFilter(req, resp, chain);

                assertEquals(401, resp.getStatus(), method + " " + path);
                assertNull(chain.getRequest(), method + " " + path);
            }
        }
    }

    @Test
    void malformed_cli_download_paths_are_not_whitelisted() throws Exception {
        AuthFilter filter = new AuthFilter(newJwtService(), mock(SessionService.class),
                mock(WorkspaceMemberDao.class), usableWorkspaceDao(), mock(UserDao.class));

        for (String path : new String[]{
                "/api/cli/workitems/abc/requirement-documents/index",
                "/api/cli/workitems/50063/requirement-documents/abc/content",
                "/api/cli/workitems/50063/requirement-documents/index/extra",
                "/api/cli/workitems/50063/requirement-documents/77/content/extra",
                "/api/cli/workitems/50063/requirement-documents/77"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", path);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, resp, chain);

            assertEquals(401, resp.getStatus(), path);
            assertNull(chain.getRequest(), path);
        }
    }

    @Test
    void unrelated_cli_routes_are_not_whitelisted() throws Exception {
        AuthFilter filter = new AuthFilter(newJwtService(), mock(SessionService.class),
                mock(WorkspaceMemberDao.class), usableWorkspaceDao(), mock(UserDao.class));

        for (String path : new String[]{
                "/api/cli/workitems/50063",
                "/api/cli/workitems/50063/requirement-documents/9",
                "/api/cli/workitems/abc/requirement-documents",
                "/api/cli/scheduled-tasks/abc/documents",
                "/api/cli/scheduled-tasks/321",
                "/api/cli/scheduled-tasks/321/documents/9",
                "/api/cli/other"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
            MockHttpServletResponse resp = new MockHttpServletResponse();
            MockFilterChain chain = new MockFilterChain();

            filter.doFilter(req, resp, chain);

            assertEquals(401, resp.getStatus(), path);
            assertNull(chain.getRequest(), path);
        }
    }

    @Test
    void valid_token_sets_context_and_continues() throws Exception {
        JwtService jwtService = newJwtService();
        SessionService sessionService = mock(SessionService.class);
        WorkspaceMemberDao workspaceMemberDao = mock(WorkspaceMemberDao.class);
        when(sessionService.isBlacklisted(anyString())).thenReturn(false);
        WorkspaceMemberDO activeMember =
                member(0, 0, WorkspaceAccessLevel.READ_WRITE.name());
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 42L))
                .thenReturn(activeMember);
        AuthFilter filter = new AuthFilter(jwtService, sessionService, workspaceMemberDao, usableWorkspaceDao(), mock(UserDao.class));

        String token = jwtService.signAccess(new TokenPayload(42L, 100L, "jti-a"));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/workspaces/current");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        AtomicReference<Long> seenUser = new AtomicReference<>();
        AtomicReference<Long> seenWorkspace = new AtomicReference<>();
        AtomicReference<WorkspaceAccessLevel> seenLevel = new AtomicReference<>();
        AtomicReference<WorkspaceMemberDO> seenMember = new AtomicReference<>();
        AtomicReference<String> seenTraceId = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(new javax.servlet.http.HttpServlet() {
            @Override
            protected void service(javax.servlet.http.HttpServletRequest r,
                                   javax.servlet.http.HttpServletResponse s) {
                seenUser.set(AutoWonderContext.get().getUserId());
                seenWorkspace.set(AutoWonderContext.get().getCurrentWorkspaceId());
                seenLevel.set(AutoWonderContext.get().getWorkspaceAccessLevel());
                seenMember.set(AutoWonderContext.get().getWorkspaceMember());
                seenTraceId.set(AutoWonderContext.get().getTraceId());
            }
        });

        filter.doFilter(req, resp, chain);

        assertEquals(42L, seenUser.get());
        assertEquals(100L, seenWorkspace.get());
        assertEquals(WorkspaceAccessLevel.READ_WRITE, seenLevel.get());
        assertNotNull(seenMember.get());
        assertSame(activeMember, seenMember.get());
        assertNotNull(seenTraceId.get());
        verify(workspaceMemberDao, times(1)).findByWorkspaceAndUser(100L, 42L);
        // 过滤器结束后上下文已清理
        assertNull(AutoWonderContext.get().getUserId());
        assertNull(AutoWonderContext.get().getCurrentWorkspaceId());
        assertNull(AutoWonderContext.get().getWorkspaceAccessLevel());
        assertNull(AutoWonderContext.get().getTraceId());
    }

    @Test
    void validTokenSnapshotsOperatorAndWorkspaceIntoBusinessLog() throws Exception {
        JwtService jwt = newJwtService();
        SessionService sessions = mock(SessionService.class);
        WorkspaceMemberDao members = mock(WorkspaceMemberDao.class);
        when(sessions.isBlacklisted(anyString())).thenReturn(false);
        when(members.findByWorkspaceAndUser(100L, 42L))
                .thenReturn(member(0, 0, WorkspaceAccessLevel.READ_WRITE.name()));
        BizLog log = new BizLog();
        AutoWonderContext.get().setBizLog(log);
        String token = jwt.signAccess(new TokenPayload(42L, 100L, "jti-log"));

        new AuthFilter(jwt, sessions, members, usableWorkspaceDao(), mock(UserDao.class)).doFilter(authenticatedRequest(token),
                new MockHttpServletResponse(), new MockFilterChain());

        assertEquals(42L, log.getUserId());
        assertEquals(100L, log.getWorkspaceId());
    }

    @Test
    void valid_token_without_current_workspace_sets_user_and_does_not_query_membership() throws Exception {
        JwtService jwtService = newJwtService();
        SessionService sessionService = mock(SessionService.class);
        WorkspaceMemberDao workspaceMemberDao = mock(WorkspaceMemberDao.class);
        AuthFilter filter = new AuthFilter(jwtService, sessionService, workspaceMemberDao, usableWorkspaceDao(), mock(UserDao.class));

        String token = jwtService.signAccess(new TokenPayload(42L, null, "jti-no-workspace"));
        MockHttpServletRequest req = authenticatedRequest(token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicReference<Long> seenUser = new AtomicReference<>();
        AtomicReference<Long> seenWorkspace = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(new javax.servlet.http.HttpServlet() {
            @Override
            protected void service(javax.servlet.http.HttpServletRequest r,
                                   javax.servlet.http.HttpServletResponse s) {
                seenUser.set(AutoWonderContext.get().getUserId());
                seenWorkspace.set(AutoWonderContext.get().getCurrentWorkspaceId());
            }
        });

        filter.doFilter(req, resp, chain);

        assertEquals(42L, seenUser.get());
        assertNull(seenWorkspace.get());
        verifyNoInteractions(workspaceMemberDao);
        assertNull(AutoWonderContext.get().getUserId());
    }

    @ParameterizedTest
    @MethodSource("workspaceRecoveryRoutes")
    void workspace_recovery_routes_validate_jwt_but_skip_stale_membership(
            String method, String path) throws Exception {
        JwtService jwtService = newJwtService();
        SessionService sessionService = mock(SessionService.class);
        WorkspaceMemberDao workspaceMemberDao = mock(WorkspaceMemberDao.class);
        AuthFilter filter = new AuthFilter(jwtService, sessionService, workspaceMemberDao, usableWorkspaceDao(), mock(UserDao.class));
        String token = jwtService.signAccess(new TokenPayload(42L, 999L, "jti-recovery"));
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        AtomicReference<Long> seenUser = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(new javax.servlet.http.HttpServlet() {
            @Override
            protected void service(javax.servlet.http.HttpServletRequest r,
                                   javax.servlet.http.HttpServletResponse s) {
                seenUser.set(AutoWonderContext.get().getUserId());
            }
        });

        filter.doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus());
        assertEquals(42L, seenUser.get());
        assertNotNull(chain.getRequest());
        verifyNoInteractions(workspaceMemberDao);
    }

    @ParameterizedTest
    @MethodSource("workspaceRecoveryRoutes")
    void workspace_recovery_routes_still_require_jwt(String method, String path)
            throws Exception {
        JwtService jwtService = newJwtService();
        SessionService sessionService = mock(SessionService.class);
        WorkspaceMemberDao workspaceMemberDao = mock(WorkspaceMemberDao.class);
        AuthFilter filter = new AuthFilter(jwtService, sessionService, workspaceMemberDao, usableWorkspaceDao(), mock(UserDao.class));
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertNull(chain.getRequest());
        verifyNoInteractions(workspaceMemberDao);
    }

    @Test
    void member_management_route_does_not_skip_current_membership_lookup() throws Exception {
        JwtService jwtService = newJwtService();
        SessionService sessionService = mock(SessionService.class);
        WorkspaceMemberDao workspaceMemberDao = mock(WorkspaceMemberDao.class);
        AuthFilter filter = new AuthFilter(jwtService, sessionService, workspaceMemberDao, usableWorkspaceDao(), mock(UserDao.class));
        String token = jwtService.signAccess(new TokenPayload(42L, 999L, "jti-members"));
        MockHttpServletRequest req = new MockHttpServletRequest(
                "GET", "/api/workspaces/current/members");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(403, resp.getStatus());
        assertNull(chain.getRequest());
        verify(workspaceMemberDao, times(1)).findByWorkspaceAndUser(999L, 42L);
    }

    @Test
    void missing_membership_returns_403_with_ids_and_cleans_context() throws Exception {
        JwtService jwtService = newJwtService();
        SessionService sessionService = mock(SessionService.class);
        WorkspaceMemberDao workspaceMemberDao = mock(WorkspaceMemberDao.class);
        AuthFilter filter = new AuthFilter(jwtService, sessionService, workspaceMemberDao, usableWorkspaceDao(), mock(UserDao.class));
        String token = jwtService.signAccess(new TokenPayload(42L, 100L, "jti-missing-member"));
        MockHttpServletRequest req = authenticatedRequest(token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        AutoWonderContext.get().setRequestId("rid-member-fail");

        filter.doFilter(req, resp, chain);

        assertEquals(403, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("\"code\":\"11001\""));
        assertTrue(resp.getContentAsString().contains("\"request_id\":\"rid-member-fail\""));
        assertTrue(resp.getContentAsString().matches(".*\"traceId\":\"[^\"]+\".*"));
        assertNull(chain.getRequest());
        verify(workspaceMemberDao, times(1)).findByWorkspaceAndUser(100L, 42L);
        assertNull(AutoWonderContext.get().getUserId());
        assertNull(AutoWonderContext.get().getCurrentWorkspaceId());
        assertNull(AutoWonderContext.get().getWorkspaceAccessLevel());
    }

    @Test
    void deleted_membership_returns_403() throws Exception {
        assertMembershipRejected(member(0, 1, WorkspaceAccessLevel.ADMIN.name()));
    }

    @Test
    void inactive_membership_returns_403() throws Exception {
        assertMembershipRejected(member(1, 0, WorkspaceAccessLevel.ADMIN.name()));
    }

    @Test
    void aPlatformAdminWhoIsNotAMemberEntersAnyWorkspaceWithAdminAccess() throws Exception {
        JwtService jwtService = newJwtService();
        WorkspaceMemberDao workspaceMemberDao = mock(WorkspaceMemberDao.class);
        UserDao userDao = mock(UserDao.class);
        when(userDao.findById(42L)).thenReturn(user(42L, 1));
        AuthFilter filter = new AuthFilter(jwtService, mock(SessionService.class),
                workspaceMemberDao, usableWorkspaceDao(), userDao);
        String token = jwtService.signAccess(new TokenPayload(42L, 100L, "jti-sys-admin"));

        AtomicReference<WorkspaceAccessLevel> seenLevel = new AtomicReference<>();
        AtomicReference<WorkspaceMemberDO> seenMember = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(new javax.servlet.http.HttpServlet() {
            @Override
            protected void service(javax.servlet.http.HttpServletRequest r,
                                   javax.servlet.http.HttpServletResponse s) {
                seenLevel.set(AutoWonderContext.get().getWorkspaceAccessLevel());
                seenMember.set(AutoWonderContext.get().getWorkspaceMember());
            }
        });

        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(authenticatedRequest(token), resp, chain);

        assertEquals(200, resp.getStatus());
        assertNotNull(chain.getRequest());
        // ADMIN is the highest WorkspaceAccessLevel, so every @RequireWorkspaceAccess guard passes
        // through this branch; no member row is ever synthesized.
        assertEquals(WorkspaceAccessLevel.ADMIN, seenLevel.get());
        assertNull(seenMember.get());
        verify(workspaceMemberDao).findByWorkspaceAndUser(100L, 42L);
    }

    @Test
    void aPlatformAdminWithAStaleMembershipRowStillGetsAdminAccess() throws Exception {
        JwtService jwtService = newJwtService();
        WorkspaceMemberDao workspaceMemberDao = mock(WorkspaceMemberDao.class);
        // Removed from a workspace they once joined: the row survives the removal, so the member
        // branch cannot apply — the is_admin flag has to carry the request on its own.
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 42L))
                .thenReturn(member(0, 1, WorkspaceAccessLevel.READ_ONLY.name()));
        UserDao userDao = mock(UserDao.class);
        when(userDao.findById(42L)).thenReturn(user(42L, 1));
        AuthFilter filter = new AuthFilter(jwtService, mock(SessionService.class),
                workspaceMemberDao, usableWorkspaceDao(), userDao);
        String token = jwtService.signAccess(new TokenPayload(42L, 100L, "jti-stale-member"));

        AtomicReference<WorkspaceAccessLevel> seenLevel = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain(new javax.servlet.http.HttpServlet() {
            @Override
            protected void service(javax.servlet.http.HttpServletRequest r,
                                   javax.servlet.http.HttpServletResponse s) {
                seenLevel.set(AutoWonderContext.get().getWorkspaceAccessLevel());
            }
        });

        MockHttpServletResponse resp = new MockHttpServletResponse();
        filter.doFilter(authenticatedRequest(token), resp, chain);

        assertEquals(200, resp.getStatus());
        assertEquals(WorkspaceAccessLevel.ADMIN, seenLevel.get());
    }

    @Test
    void aRevokedPlatformAdminWhoIsNotAMemberIsRejectedImmediately() throws Exception {
        JwtService jwtService = newJwtService();
        WorkspaceMemberDao workspaceMemberDao = mock(WorkspaceMemberDao.class);
        UserDao userDao = mock(UserDao.class);
        when(userDao.findById(42L)).thenReturn(user(42L, 0));
        AuthFilter filter = new AuthFilter(jwtService, mock(SessionService.class),
                workspaceMemberDao, usableWorkspaceDao(), userDao);
        String token = jwtService.signAccess(new TokenPayload(42L, 100L, "jti-revoked-admin"));

        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(authenticatedRequest(token), resp, chain);

        // The flag is read from the database on every request, so a token issued while the user
        // was an admin stops working the moment the flag is revoked.
        assertEquals(403, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("\"code\":\"11001\""));
        assertNull(chain.getRequest());
        assertNull(AutoWonderContext.get().getUserId());
        assertNull(AutoWonderContext.get().getWorkspaceAccessLevel());
    }

    @Test
    void null_stored_access_level_returns_explicit_internal_data_error() throws Exception {
        assertInvalidAccessLevel(member(0, 0, null));
    }

    @Test
    void invalid_stored_access_level_returns_explicit_internal_data_error() throws Exception {
        assertInvalidAccessLevel(member(0, 0, "read_write"));
    }

    @Test
    void missing_token_returns_401_json() throws Exception {
        JwtService jwtService = newJwtService();
        SessionService sessionService = mock(SessionService.class);
        AuthFilter filter = new AuthFilter(jwtService, sessionService, mock(WorkspaceMemberDao.class), usableWorkspaceDao(), mock(UserDao.class));

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/workspaces/current");
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        AutoWonderContext.get().setRequestId("rid-auth-fail");

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("10401"));
        assertTrue(resp.getContentAsString().contains("\"request_id\":\"rid-auth-fail\""));
        assertNull(chain.getRequest()); // 链未继续
    }

    @Test
    void blacklisted_token_returns_401() throws Exception {
        JwtService jwtService = newJwtService();
        SessionService sessionService = mock(SessionService.class);
        WorkspaceMemberDao workspaceMemberDao = mock(WorkspaceMemberDao.class);
        when(sessionService.isBlacklisted("jti-b")).thenReturn(true);
        AuthFilter filter = new AuthFilter(jwtService, sessionService, workspaceMemberDao, usableWorkspaceDao(), mock(UserDao.class));

        String token = jwtService.signAccess(new TokenPayload(42L, 100L, "jti-b"));
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/workspaces/current");
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(401, resp.getStatus());
        assertNull(chain.getRequest());
        verifyNoInteractions(workspaceMemberDao);
    }

    @Test
    void deleted_or_disabled_workspace_rejects_a_previously_valid_token() throws Exception {
        JwtService jwtService = newJwtService();
        WorkspaceMemberDao workspaceMemberDao = mock(WorkspaceMemberDao.class);
        WorkspaceDao workspaceDao = mock(WorkspaceDao.class);
        // F6.1/F6.2: org_member rows are deliberately preserved on delete, so the membership
        // lookup would still say "member". Only the org read can tell the filter that this
        // workspace stopped being usable after the token was issued.
        when(workspaceDao.countUsable(100L)).thenReturn(0L);
        AuthFilter filter = new AuthFilter(jwtService, mock(SessionService.class),
                workspaceMemberDao, workspaceDao, mock(UserDao.class));
        String token = jwtService.signAccess(new TokenPayload(42L, 100L, "jti-deleted-org"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(authenticatedRequest(token), resp, chain);

        assertEquals(403, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("\"code\":\"11005\""));
        assertNull(chain.getRequest());
        verify(workspaceDao, times(1)).countUsable(100L);
        verifyNoInteractions(workspaceMemberDao);
        assertNull(AutoWonderContext.get().getUserId());
        assertNull(AutoWonderContext.get().getCurrentWorkspaceId());
    }

    @ParameterizedTest
    @MethodSource("workspaceLifecycleRoutes")
    void workspace_lifecycle_routes_skip_the_unusable_workspace_check(String method, String path)
            throws Exception {
        JwtService jwtService = newJwtService();
        WorkspaceMemberDao workspaceMemberDao = mock(WorkspaceMemberDao.class);
        WorkspaceDao workspaceDao = mock(WorkspaceDao.class);
        when(workspaceDao.countUsable(anyLong())).thenReturn(0L);
        AuthFilter filter = new AuthFilter(jwtService, mock(SessionService.class),
                workspaceMemberDao, workspaceDao, mock(UserDao.class));
        String token = jwtService.signAccess(new TokenPayload(42L, 100L, "jti-lifecycle"));
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(200, resp.getStatus(), method + " " + path);
        assertNotNull(chain.getRequest(), method + " " + path);
        // Neither read may run: the caller's token workspace is exactly the row that is gone, so
        // consulting it would make the recycle bin and restore permanently unreachable (F5.2).
        verifyNoInteractions(workspaceDao);
        verifyNoInteractions(workspaceMemberDao);
    }

    @ParameterizedTest
    @MethodSource("neighbouringWorkspaceRoutes")
    void neighbouring_workspace_routes_still_go_through_the_unusable_workspace_check(
            String method, String path) throws Exception {
        JwtService jwtService = newJwtService();
        WorkspaceDao workspaceDao = mock(WorkspaceDao.class);
        when(workspaceDao.countUsable(100L)).thenReturn(0L);
        AuthFilter filter = new AuthFilter(jwtService, mock(SessionService.class),
                mock(WorkspaceMemberDao.class), workspaceDao, mock(UserDao.class));
        String token = jwtService.signAccess(new TokenPayload(42L, 100L, "jti-neighbour"));
        MockHttpServletRequest req = new MockHttpServletRequest(method, path);
        req.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(req, resp, chain);

        assertEquals(403, resp.getStatus(), method + " " + path);
        assertTrue(resp.getContentAsString().contains("\"code\":\"11005\""), method + " " + path);
        assertNull(chain.getRequest(), method + " " + path);
    }

    private void assertMembershipRejected(WorkspaceMemberDO member) throws Exception {
        JwtService jwtService = newJwtService();
        SessionService sessionService = mock(SessionService.class);
        WorkspaceMemberDao workspaceMemberDao = mock(WorkspaceMemberDao.class);
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 42L)).thenReturn(member);
        AuthFilter filter = new AuthFilter(jwtService, sessionService, workspaceMemberDao, usableWorkspaceDao(), mock(UserDao.class));
        String token = jwtService.signAccess(new TokenPayload(42L, 100L, "jti-rejected"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(authenticatedRequest(token), resp, chain);

        assertEquals(403, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("\"code\":\"11001\""));
        assertNull(chain.getRequest());
        verify(workspaceMemberDao, times(1)).findByWorkspaceAndUser(100L, 42L);
        assertNull(AutoWonderContext.get().getUserId());
    }

    private void assertInvalidAccessLevel(WorkspaceMemberDO member) throws Exception {
        JwtService jwtService = newJwtService();
        SessionService sessionService = mock(SessionService.class);
        WorkspaceMemberDao workspaceMemberDao = mock(WorkspaceMemberDao.class);
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 42L)).thenReturn(member);
        AuthFilter filter = new AuthFilter(jwtService, sessionService, workspaceMemberDao, usableWorkspaceDao(), mock(UserDao.class));
        String token = jwtService.signAccess(new TokenPayload(42L, 100L, "jti-invalid-level"));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(authenticatedRequest(token), resp, chain);

        assertEquals(500, resp.getStatus());
        assertTrue(resp.getContentAsString().contains("\"code\":\"12007\""));
        assertNull(chain.getRequest());
        verify(workspaceMemberDao, times(1)).findByWorkspaceAndUser(100L, 42L);
        assertNull(AutoWonderContext.get().getWorkspaceAccessLevel());
    }

    private MockHttpServletRequest authenticatedRequest(String token) {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/workspaces/current");
        req.addHeader("Authorization", "Bearer " + token);
        return req;
    }

    private WorkspaceMemberDO member(int status, int isDeleted, String accessLevel) {
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setStatus(status);
        member.setIsDeleted(isDeleted);
        member.setAccessLevel(accessLevel);
        return member;
    }

    private UserDO user(long id, int isAdmin) {
        UserDO user = new UserDO();
        user.setId(id);
        user.setStatus(0);
        user.setIsAdmin(isAdmin);
        return user;
    }

    private static Stream<Arguments> workspaceRecoveryRoutes() {
        return Stream.of(
                Arguments.of("POST", "/api/workspaces"),
                Arguments.of("POST", "/api/workspaces/"),
                Arguments.of("GET", "/api/workspaces/mine"),
                Arguments.of("GET", "/api/workspaces/mine/"),
                Arguments.of("POST", "/api/workspaces/123/switch"),
                Arguments.of("POST", "/api/workspaces/123/switch/"),
                Arguments.of("GET", "/api/mcp/tokens"),
                Arguments.of("POST", "/api/mcp/tokens"),
                Arguments.of("DELETE", "/api/mcp/tokens/1"),
                Arguments.of("GET", "/api/mcp/tokens/tools"),
                Arguments.of("GET", "/api/mcp/tokens/platform-skills"),
                Arguments.of("GET", "/api/users/me/im-identities"),
                Arguments.of("PUT", "/api/users/me/im-identities/dingtalk"),
                Arguments.of("POST", "/api/users/me/im-identities/dingtalk/test"));
    }

    /** The four endpoints F8 adds, each of which must work when the token's workspace is gone. */
    private static Stream<Arguments> workspaceLifecycleRoutes() {
        return Stream.of(
                Arguments.of("PUT", "/api/workspaces/123"),
                Arguments.of("PUT", "/api/workspaces/123/"),
                Arguments.of("DELETE", "/api/workspaces/123"),
                Arguments.of("POST", "/api/workspaces/123/restore"),
                Arguments.of("POST", "/api/workspaces/123/restore/"),
                Arguments.of("GET", "/api/workspaces/recycle-bin"));
    }

    /**
     * Same shapes as above with one detail changed. The exemption has to be keyed on the method
     * as well as the path, otherwise any of these would become a way past the org check.
     */
    private static Stream<Arguments> neighbouringWorkspaceRoutes() {
        return Stream.of(
                Arguments.of("GET", "/api/workspaces/123"),
                Arguments.of("POST", "/api/workspaces/123"),
                Arguments.of("PUT", "/api/workspaces/123/restore"),
                Arguments.of("GET", "/api/workspaces/123/restore"),
                Arguments.of("POST", "/api/workspaces/123/restore/extra"),
                Arguments.of("POST", "/api/workspaces/recycle-bin"),
                Arguments.of("DELETE", "/api/workspaces/recycle-bin"),
                Arguments.of("GET", "/api/workspaces/current"));
    }
}
