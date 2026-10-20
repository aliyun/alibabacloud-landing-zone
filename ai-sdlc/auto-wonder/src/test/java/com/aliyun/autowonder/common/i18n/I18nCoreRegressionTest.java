package com.aliyun.autowonder.common.i18n;

import com.aliyun.autowonder.auth.filter.AuthFilter;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.session.SessionService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.common.web.GlobalExceptionHandler;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.log.BizLog;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import java.io.IOException;
import java.util.Locale;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.i18n.SimpleLocaleContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class I18nCoreRegressionTest {
    @AfterEach void cleanup() {
        LocaleContextHolder.resetLocaleContext();
        AutoWonderContext.destroy();
    }

    @Test void englishDefaultResponseKeepsChineseDiagnosticAndIdentifiers() {
        LocaleContextHolder.setLocale(Locale.US);
        BizLog log = new BizLog();
        AutoWonderContext.get().setBizLog(log);
        AutoWonderContext.get().setTraceId("trace-20");
        AutoWonderContext.get().setRequestId("request-20");
        BizException error = new BizException(ErrorCode.NO_PERMISSION);
        var response = new GlobalExceptionHandler().handleBiz(error);
        assertEquals(403, response.getStatusCodeValue());
        assertEquals("Permission denied", response.getBody().getMessage());
        assertEquals("10403", response.getBody().getCode());
        assertEquals("trace-20", response.getBody().getTraceId());
        assertEquals("request-20", response.getBody().getRequestId());
        assertEquals("无权限", error.getMessage());
        assertEquals("无权限", error.getLocalizedMessage());
        assertEquals("无权限", log.getErrorMsg());
    }

    @Test void explicitRawEqualDefaultAndExternalDetailStayRaw() {
        LocaleContextHolder.setLocale(Locale.US);
        var handler = new GlobalExceptionHandler();
        assertEquals("无权限", handler.handleBiz(new BizException(ErrorCode.NO_PERMISSION, "无权限"))
                .getBody().getMessage());
        String detail = "Provider's <error>{0}: 无权限";
        assertEquals(detail, handler.handleIllegalArg(new IllegalArgumentException(detail)).getMessage());
        assertEquals(detail, Result.fail("external", detail, "data").getMessage());
        assertEquals("", Result.ok("data").getMessage());
    }

    @Test void englishValidationUsesFirstFieldAndPreservesStatus() {
        LocaleContextHolder.setLocale(Locale.US);
        var binding = new BeanPropertyBindingResult(new Object(), "request");
        binding.addError(new FieldError("request", "appKey", "secret must never reach UI"));
        binding.addError(new FieldError("request", "ignored", "second"));
        var response = new GlobalExceptionHandler().handleValidation(new MethodArgumentNotValidException(null, binding));
        assertEquals(400, response.getStatusCodeValue());
        assertEquals("appKey is invalid", response.getBody().getMessage());
        assertEquals("10001", response.getBody().getCode());
    }

    @Test void englishConflictPreserves409AndSpecificCode() {
        LocaleContextHolder.setLocale(Locale.US);
        var response = new GlobalExceptionHandler().handleDataIntegrityViolation(
                new DataIntegrityViolationException("raw database diagnostic"));
        assertEquals(409, response.getStatusCodeValue());
        assertEquals("10409", response.getBody().getCode());
        assertEquals("Data conflict. Refresh the page and try again", response.getBody().getMessage());
    }

    @Test void earlyAuthUsesWeightedEnglishAndRestoresOriginalContext() throws Exception {
        var previous = new SimpleLocaleContext(Locale.FRANCE);
        LocaleContextHolder.setLocaleContext(previous);
        var request = new MockHttpServletRequest("GET", "/api/private");
        request.addHeader("Accept-Language", "fr-FR;q=1, en-GB;q=.8, zh-CN;q=.2");
        var response = new MockHttpServletResponse();
        AutoWonderContext.get().setRequestId("early-20");
        filter().doFilter(request, response, (r, s) -> fail("Unauthorized request reached controller"));
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("Not signed in or session expired"));
        assertTrue(response.getContentAsString().contains("\"request_id\":\"early-20\""));
        assertFalse(response.getContentAsString().contains("traceId"));
        assertSame(previous, LocaleContextHolder.getLocaleContext());
    }

    @Test void whitelistLocaleIsBoundBeforeChainAndRestoredOnDownstreamFailure() {
        var previous = new SimpleLocaleContext(Locale.FRANCE);
        LocaleContextHolder.setLocaleContext(previous);
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.addHeader("Accept-Language", "en-US");
        assertThrows(IOException.class, () -> filter().doFilter(request, new MockHttpServletResponse(), (r, s) -> {
            assertEquals(Locale.US, LocaleContextHolder.getLocale());
            throw new IOException("chain failure");
        }));
        assertSame(previous, LocaleContextHolder.getLocaleContext());
    }

    @Test void englishHandlersKeepEveryExistingStatusAndRawAction() {
        LocaleContextHolder.setLocale(Locale.US);
        var handler = new GlobalExceptionHandler();
        Object[][] statuses = {
            {ErrorCode.UNAUTHORIZED,401}, {ErrorCode.WORKSPACE_NOT_MEMBER,403}, {ErrorCode.NO_PERMISSION,403},
            {ErrorCode.PARAM_INVALID,400}, {ErrorCode.WORKSPACE_ACCESS_LEVEL_INVALID,400},
            {ErrorCode.CONFLICT,409}, {ErrorCode.WORKSPACE_OWNER_MUTATION_PROTECTED,409},
            {ErrorCode.WORKSPACE_SELF_LEVEL_MUTATION_FORBIDDEN,409}, {ErrorCode.WORKSPACE_OWNER_TRANSFER_INVALID,409},
            {ErrorCode.NOT_FOUND,200}, {ErrorCode.SYSTEM_ERROR,200}};
        for (var row : statuses) {
            ErrorCode code = (ErrorCode) row[0];
            var response = handler.handleBiz(new BizException(code));
            assertEquals(row[1],response.getStatusCodeValue());
            assertEquals(code.getCode(),response.getBody().getCode());
            assertFalse(response.getBody().isSuccess()); assertNull(response.getBody().getData());
            assertFalse(response.getBody().getMessage().matches(".*[\\p{IsHan}].*"));
        }
        var emptyBinding = new BeanPropertyBindingResult(new Object(), "request");
        assertEquals("request is invalid",handler.handleValidation(
                new MethodArgumentNotValidException(null,emptyBinding)).getBody().getMessage());
        var method = new org.springframework.web.HttpRequestMethodNotSupportedException("PATCH");
        var methodResponse = handler.handleMethodNotSupported(method,new MockHttpServletRequest("PATCH","/api/test"));
        assertEquals(405,methodResponse.getStatusCodeValue());
        assertEquals("Request method 'PATCH' is not supported",methodResponse.getBody().getMessage());
        var malformed = handler.handleMessageNotReadable(new org.springframework.http.converter.HttpMessageNotReadableException("raw malformed"));
        assertEquals(400,malformed.getStatusCodeValue()); assertEquals("Invalid parameter",malformed.getBody().getMessage());
        var level = com.fasterxml.jackson.databind.exc.InvalidFormatException.from(null,"raw","bad",com.aliyun.autowonder.access.WorkspaceAccessLevel.class);
        var invalidLevel = handler.handleMessageNotReadable(new org.springframework.http.converter.HttpMessageNotReadableException("raw",level));
        assertEquals(400,invalidLevel.getStatusCodeValue()); assertEquals("12007",invalidLevel.getBody().getCode());
        assertEquals("Invalid workspace access level",invalidLevel.getBody().getMessage());
        var logged = com.aliyun.autowonder.common.error.AlreadyLoggedException.from(new RuntimeException("secret"));
        assertEquals(500,handler.handleAlreadyLogged(logged).getStatusCodeValue());
        assertEquals("Internal system error",handler.handleThrowable(logged).getMessage());
        assertEquals("10000",handler.handleThrowable(new RuntimeException("raw unknown")).getCode());
        String action="管理's <raw>{0}";
        var denial=new com.aliyun.autowonder.access.WorkspaceAccessDeniedException(
                com.aliyun.autowonder.access.WorkspaceAccessLevel.READ_ONLY,
                com.aliyun.autowonder.access.WorkspaceAccessLevel.ADMIN,action);
        var log=new BizLog(); AutoWonderContext.get().setBizLog(log);
        AutoWonderContext.get().setTraceId("denial-trace"); AutoWonderContext.get().setRequestId("denial-request");
        var response=handler.handleWorkspaceAccessDenied(denial);
        assertEquals(403,response.getStatusCodeValue()); assertEquals("12008",response.getBody().getCode());
        assertEquals("Your role does not have permission to "+action,response.getBody().getMessage());
        assertEquals(action,response.getBody().getData().getAction());
        assertEquals(com.aliyun.autowonder.access.WorkspaceAccessLevel.READ_ONLY,response.getBody().getData().getCurrent());
        assertEquals(com.aliyun.autowonder.access.WorkspaceAccessLevel.ADMIN,response.getBody().getData().getRequired());
        assertEquals("denial-trace",response.getBody().getTraceId()); assertEquals("denial-request",response.getBody().getRequestId());
        assertEquals("工作空间访问级别不足，无法"+action,log.getErrorMsg());
    }

    @Test void warningDiagnosticDoesNotFollowResponseLocaleAndSafeCauseIsNotRelogged() {
        LocaleContextHolder.setLocale(Locale.US);
        var events = new java.util.ArrayList<org.apache.logging.log4j.core.LogEvent>();
        var logger = (org.apache.logging.log4j.core.Logger) org.apache.logging.log4j.LogManager.getLogger(GlobalExceptionHandler.class);
        var oldLevel = logger.getLevel();
        var appender = new org.apache.logging.log4j.core.appender.AbstractAppender("core-i18n", null,
                org.apache.logging.log4j.core.layout.PatternLayout.createDefaultLayout(), true,
                org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY) {
            @Override public void append(org.apache.logging.log4j.core.LogEvent event) { events.add(event.toImmutable()); }
        };
        appender.start(); logger.addAppender(appender); logger.setLevel(org.apache.logging.log4j.Level.WARN);
        try {
            var handler = new GlobalExceptionHandler();
            assertEquals("Permission denied",handler.handleBiz(new BizException(ErrorCode.NO_PERMISSION)).getBody().getMessage());
            assertEquals(1,events.size());
            assertEquals("biz exception: code=10403, msg=无权限",events.get(0).getMessage().getFormattedMessage());
            var safe = com.aliyun.autowonder.common.error.AlreadyLoggedException.from(new RuntimeException("secret"));
            assertEquals("Permission denied",handler.handleBiz(new BizException(ErrorCode.NO_PERMISSION,safe)).getBody().getMessage());
            assertEquals(1,events.size());
        } finally { logger.removeAppender(appender); logger.setLevel(oldLevel); appender.stop(); }
    }

    private AuthFilter filter() {
        return new AuthFilter(mock(JwtService.class), mock(SessionService.class),
                mock(WorkspaceMemberDao.class), mock(WorkspaceDao.class), mock(UserDao.class));
    }
}
