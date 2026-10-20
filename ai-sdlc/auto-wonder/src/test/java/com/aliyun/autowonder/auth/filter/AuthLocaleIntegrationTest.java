package com.aliyun.autowonder.auth.filter;

import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.jwt.TokenPayload;
import com.aliyun.autowonder.auth.session.SessionService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.common.web.GlobalExceptionHandler;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.filter.BizLoggerFilter;
import com.aliyun.autowonder.log.BizLog;
import com.aliyun.autowonder.log.BizLogProducer;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.codahale.metrics.MetricRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.i18n.SimpleLocaleContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.LocaleResolver;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class AuthLocaleIntegrationTest {
    private final JwtService jwt = mock(JwtService.class);
    private final SessionService sessions = mock(SessionService.class);
    private final WorkspaceMemberDao members = mock(WorkspaceMemberDao.class);
    private final WorkspaceDao workspaces = mock(WorkspaceDao.class);
    private final UserDao users = mock(UserDao.class);

    @AfterEach void cleanup() { LocaleContextHolder.resetLocaleContext(); AutoWonderContext.destroy(); }

    static Stream<Arguments> headers() {
        return Stream.of(
            Arguments.of(null, "未登录或登录已失效"), Arguments.of("", "未登录或登录已失效"),
            Arguments.of("  ", "未登录或登录已失效"), Arguments.of("zh-CN", "未登录或登录已失效"),
            Arguments.of("zh-TW", "未登录或登录已失效"), Arguments.of("zh-Hans", "未登录或登录已失效"),
            Arguments.of("en-GB", "Not signed in or session expired"),
            Arguments.of("zh-CN;q=.2,en-US;q=.8", "Not signed in or session expired"),
            Arguments.of("fr-FR;q=1,en;q=.8", "Not signed in or session expired"),
            Arguments.of("en;q=.8,zh;q=.8", "Not signed in or session expired"),
            Arguments.of("zh;q=.8,en;q=.8", "未登录或登录已失效"),
            Arguments.of("en;q=0,zh;q=.2", "未登录或登录已失效"),
            Arguments.of("zh;q=0,en;q=.2", "Not signed in or session expired"),
            Arguments.of("*;q=1,en;q=.2", "Not signed in or session expired"),
            Arguments.of("*", "未登录或登录已失效"), Arguments.of("fr", "未登录或登录已失效"),
            Arguments.of("en;q=0", "未登录或登录已失效"), Arguments.of("zh;q=0,*", "未登录或登录已失效"),
            Arguments.of("en;q=bad", "未登录或登录已失效"), Arguments.of("en;q=1.1", "未登录或登录已失效"),
            Arguments.of("en_US", "未登录或登录已失效"));
    }

    @ParameterizedTest @MethodSource("headers")
    void weightedHeadersRenderBeforeMvcAndRestoreThread(String header, String expected) throws Exception {
        var previous = new SimpleLocaleContext(Locale.FRANCE);
        LocaleContextHolder.setLocaleContext(previous);
        var request = new MockHttpServletRequest("GET", "/api/private");
        if (header != null) request.addHeader("Accept-Language", header);
        var response = new MockHttpServletResponse();
        filter().doFilter(request, response, (r, s) -> fail("early auth"));
        assertEquals(401, response.getStatus());
        assertEquals(expected, new ObjectMapper().readTree(response.getContentAsByteArray()).get("message").asText());
        assertSame(previous, LocaleContextHolder.getLocaleContext());
    }

    @Test void productionRegistrationAndMvcResolverUseTheSameHeaderPolicy() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(JwtService.class, () -> jwt);
            context.registerBean(SessionService.class, () -> sessions);
            context.registerBean(WorkspaceMemberDao.class, () -> members);
            context.registerBean(WorkspaceDao.class, () -> workspaces);
            context.registerBean(UserDao.class, () -> users);
            context.register(AuthFilterConfig.class);
            context.refresh();
            var registration = context.getBean(AuthFilterConfig.class)
                    .authFilterRegistration(jwt, sessions, members, workspaces, users);
            assertEquals(1, registration.getOrder());
            assertEquals(java.util.List.of("/api/*"), java.util.List.copyOf(registration.getUrlPatterns()));
            assertTrue(context.containsBean("localeResolver"), "MVC locale resolver must be registered");
            var resolver = context.getBean("localeResolver", LocaleResolver.class);
            var mvc = MockMvcBuilders.standaloneSetup(new Controller())
                    .setControllerAdvice(new GlobalExceptionHandler()).setLocaleResolver(resolver)
                    .addFilters(logger(), registration.getFilter()).build();
            var english = mvc.perform(get("/api/auth/failure").header("Accept-Language", "fr;q=1,en-GB;q=.8")
                    .header("x-acs-request-id", "request-mvc")).andReturn().getResponse();
            var body = new ObjectMapper().readTree(english.getContentAsByteArray());
            assertEquals(403, english.getStatus());
            assertEquals("Permission denied", body.get("message").asText());
            assertEquals("request-mvc", body.get("request_id").asText());
            assertEquals("request-mvc", english.getHeader("x-acs-request-id"));
            assertNull(LocaleContextHolder.getLocaleContext());
            var chinese = mvc.perform(get("/api/auth/failure")).andReturn().getResponse();
            assertEquals("无权限", new ObjectMapper().readTree(chinese.getContentAsByteArray()).get("message").asText());
            assertNull(LocaleContextHolder.getLocaleContext());
            when(jwt.parse("token")).thenReturn(new TokenPayload(42L, null, "mvc-auth"));
            var active = new UserDO(); active.setStatus(0); active.setIsAdmin(0);
            when(users.findById(42L)).thenReturn(active);
            var authenticated = mvc.perform(get("/api/private/failure").header("Accept-Language", "fr,en;q=.8")
                    .header("Authorization", "Bearer token").header("x-acs-request-id", "authenticated-mvc"))
                    .andReturn().getResponse();
            var authenticatedBody = new ObjectMapper().readTree(authenticated.getContentAsByteArray());
            assertEquals(403,authenticated.getStatus());
            assertEquals("Permission denied",authenticatedBody.get("message").asText());
            assertFalse(authenticatedBody.get("traceId").asText().isBlank());
            assertEquals("authenticated-mvc",authenticatedBody.get("request_id").asText());
            assertNull(AutoWonderContext.get().getUserId());
            assertNull(LocaleContextHolder.getLocaleContext());
        }
    }

    @Test void authDenialBranchesKeepStatusCodesTracesAndRawLogs() throws Exception {
        String[][] cases = {{"invalid", "401", "10401", "Not signed in or session expired"},
            {"blacklisted", "401", "10401", "Not signed in or session expired"},
            {"deactivated", "401", "29007", "This account has been deactivated and cannot sign in"},
            {"unusable", "403", "11005", "Workspace deleted or disabled"},
            {"member", "403", "11001", "Not a member of this workspace"},
            {"level", "500", "12007", "Invalid workspace access level"}};
        for (String[] entry : cases) {
            reset(jwt, sessions, members, workspaces, users);
            when(jwt.parse("token")).thenReturn(new TokenPayload(42L, 100L, "jti"));
            when(workspaces.countUsable(100L)).thenReturn(1L);
            // AuthFilter resolves the live user row before any workspace check: every case with a
            // decodable token but a healthy account needs this stub, or it would stop at 401.
            if (!entry[0].equals("invalid") && !entry[0].equals("blacklisted")) {
                var active = new UserDO(); active.setStatus(0); active.setIsAdmin(0);
                when(users.findById(42L)).thenReturn(active);
            }
            if (entry[0].equals("invalid")) when(jwt.parse("token")).thenThrow(new IllegalArgumentException("raw JWT"));
            if (entry[0].equals("blacklisted")) when(sessions.isBlacklisted("jti")).thenReturn(true);
            if (entry[0].equals("deactivated")) {
                var user = new UserDO(); user.setStatus(1); user.setPasswordHash("DEACTIVATED");
                when(users.findById(42L)).thenReturn(user);
            }
            if (entry[0].equals("unusable")) when(workspaces.countUsable(100L)).thenReturn(0L);
            if (entry[0].equals("level")) {
                var member = new WorkspaceMemberDO(); member.setStatus(0); member.setIsDeleted(0); member.setAccessLevel("bad");
                when(members.findByWorkspaceAndUser(100L,42L)).thenReturn(member);
            }
            var log = new BizLog(); AutoWonderContext.get().setBizLog(log);
            AutoWonderContext.get().setRequestId("denial-rid");
            var request = new MockHttpServletRequest("GET", "/api/private");
            request.addHeader("Accept-Language", "en"); request.addHeader("Authorization", "Bearer token");
            var response = new MockHttpServletResponse();
            filter().doFilter(request, response, (r,s) -> fail("denial"));
            var body = new ObjectMapper().readTree(response.getContentAsByteArray());
            assertEquals(Integer.parseInt(entry[1]), response.getStatus(), entry[0]);
            assertEquals(entry[2], body.get("code").asText());
            assertEquals(entry[3], body.get("message").asText());
            assertEquals("denial-rid", body.get("request_id").asText());
            assertEquals(java.util.Set.of("unusable","member","level").contains(entry[0]), body.has("traceId"));
            assertEquals(entry[2], log.getErrorCode());
            assertTrue(log.getErrorMsg().matches(".*[\\p{IsHan}].*"));
            assertNull(LocaleContextHolder.getLocaleContext());
            AutoWonderContext.destroy();
        }
    }

    @Test void writerFailureRestoresLocaleAndDoesNotMakeItInheritable() throws Exception {
        var previous = new SimpleLocaleContext(Locale.FRANCE); LocaleContextHolder.setLocaleContext(previous);
        var request = new MockHttpServletRequest("GET", "/api/private"); request.addHeader("Accept-Language", "en");
        var broken = new MockHttpServletResponse() {
            @Override public PrintWriter getWriter() throws java.io.UnsupportedEncodingException {
                throw new java.io.UnsupportedEncodingException("writer failed");
            }
        };
        assertThrows(IOException.class, () -> filter().doFilter(request, broken, (r,s)->{}));
        assertSame(previous, LocaleContextHolder.getLocaleContext());
        var publicRequest = new MockHttpServletRequest("GET", "/api/hello"); publicRequest.addHeader("Accept-Language", "en");
        AtomicReference<String> background = new AtomicReference<>();
        filter().doFilter(publicRequest, new MockHttpServletResponse(), (r,s)-> {
            var thread = new Thread(() -> background.set(Result.fail(ErrorCode.NO_PERMISSION).getMessage()));
            thread.start(); try { thread.join(); } catch (InterruptedException e) { throw new RuntimeException(e); }
        });
        assertEquals("无权限", background.get());
        assertSame(previous, LocaleContextHolder.getLocaleContext());
    }

    private AuthFilter filter() { return new AuthFilter(jwt,sessions,members,workspaces,users); }
    private BizLoggerFilter logger() {
        var logger = new BizLoggerFilter();
        ReflectionTestUtils.setField(logger,"metricRegistry",new MetricRegistry());
        ReflectionTestUtils.setField(logger,"bizLogProducer",mock(BizLogProducer.class));
        return logger;
    }
    @RestController static class Controller {
        @GetMapping({"/api/auth/failure", "/api/private/failure"}) public Result<Void> failure() { throw new BizException(ErrorCode.NO_PERMISSION); }
    }
}
