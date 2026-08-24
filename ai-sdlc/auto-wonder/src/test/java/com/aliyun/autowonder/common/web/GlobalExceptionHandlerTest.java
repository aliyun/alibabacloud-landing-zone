package com.aliyun.autowonder.common.web;

import com.aliyun.autowonder.access.WorkspaceAccessDeniedException;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.dto.WorkspaceAccessDeniedVO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import javax.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void unauthorizedBizExceptionReturnsHttp401WithBody() {
        assertBizResponse(ErrorCode.UNAUTHORIZED, HttpStatus.UNAUTHORIZED);
    }

    @Test
    void orgNotMemberBizExceptionReturnsHttp403WithBody() {
        assertBizResponse(ErrorCode.WORKSPACE_NOT_MEMBER, HttpStatus.FORBIDDEN);
    }

    @Test
    void genericNoPermissionBizExceptionReturnsHttp403WithBody() {
        assertBizResponse(ErrorCode.NO_PERMISSION, HttpStatus.FORBIDDEN);
    }

    @Test
    void otherBizExceptionPreservesHttp200WithBody() {
        assertBizResponse(ErrorCode.NOT_FOUND, HttpStatus.OK);
    }

    @Test
    void parameterValidationBizExceptionsReturnHttp400() {
        assertBizResponse(ErrorCode.PARAM_INVALID, HttpStatus.BAD_REQUEST);
        assertBizResponse(ErrorCode.WORKSPACE_ACCESS_LEVEL_INVALID, HttpStatus.BAD_REQUEST);
    }

    @Test
    void nullAccessLevelBizExceptionReturnsHttp400WithSpecificCode() {
        ResponseEntity<Result<Void>> response =
                handler.handleBiz(new BizException(ErrorCode.WORKSPACE_ACCESS_LEVEL_INVALID));

        assertErrorResponse(
                response, HttpStatus.BAD_REQUEST, ErrorCode.WORKSPACE_ACCESS_LEVEL_INVALID);
    }

    @Test
    void governanceConflictBizExceptionsReturnHttp409() {
        assertBizResponse(ErrorCode.CONFLICT, HttpStatus.CONFLICT);
        assertBizResponse(ErrorCode.WORKSPACE_OWNER_MUTATION_PROTECTED, HttpStatus.CONFLICT);
        assertBizResponse(ErrorCode.WORKSPACE_SELF_LEVEL_MUTATION_FORBIDDEN, HttpStatus.CONFLICT);
        assertBizResponse(ErrorCode.WORKSPACE_OWNER_TRANSFER_INVALID, HttpStatus.CONFLICT);
    }

    @Test
    void handlesUnexpectedAsSystemError() {
        Result<Void> r = handler.handleThrowable(new IllegalStateException("boom"));
        assertEquals("10000", r.getCode());
    }

    @Test
    void handlesUnsupportedMethodAsHttp405() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("GET");
        when(request.getRequestURI()).thenReturn("/api/mcp/rpc");
        HttpRequestMethodNotSupportedException error =
                new HttpRequestMethodNotSupportedException("GET", java.util.List.of("POST"));

        ResponseEntity<Result<Void>> response = handler.handleMethodNotSupported(error, request);

        assertEquals(HttpStatus.METHOD_NOT_ALLOWED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), response.getBody().getCode());
    }

    @Test
    void handlesWorkspaceAccessDenialAsStructuredHttp403() {
        AutoWonderContext.get().setTraceId("trace-access");
        AutoWonderContext.get().setRequestId("request-access");
        WorkspaceAccessDeniedException error = new WorkspaceAccessDeniedException(
                WorkspaceAccessLevel.READ_ONLY, WorkspaceAccessLevel.ADMIN, "管理工作空间");

        ResponseEntity<Result<WorkspaceAccessDeniedVO>> response =
                handler.handleWorkspaceAccessDenied(error);

        assertEquals(HttpStatus.FORBIDDEN, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        assertEquals(ErrorCode.WORKSPACE_ACCESS_INSUFFICIENT.getCode(), response.getBody().getCode());
        assertEquals("工作空间访问级别不足，无法管理工作空间", response.getBody().getMessage());
        assertEquals("trace-access", response.getBody().getTraceId());
        assertEquals("request-access", response.getBody().getRequestId());
        assertEquals(WorkspaceAccessLevel.READ_ONLY, response.getBody().getData().getCurrent());
        assertEquals(WorkspaceAccessLevel.ADMIN, response.getBody().getData().getRequired());
        assertEquals("管理工作空间", response.getBody().getData().getAction());
    }

    @Test
    void invalidWorkspaceAccessLevelJsonReturnsHttp400WithSpecificCode() {
        InvalidFormatException invalidLevel = InvalidFormatException.from(
                null, "invalid workspace access level", "OWNER", WorkspaceAccessLevel.class);
        HttpMessageNotReadableException error = new HttpMessageNotReadableException(
                "JSON parse error", new IllegalArgumentException(invalidLevel));

        ResponseEntity<Result<Void>> response = handler.handleMessageNotReadable(error);

        assertErrorResponse(
                response, HttpStatus.BAD_REQUEST, ErrorCode.WORKSPACE_ACCESS_LEVEL_INVALID);
    }

    @Test
    void otherMalformedJsonReturnsHttp400WithParameterInvalidCode() {
        HttpMessageNotReadableException error = new HttpMessageNotReadableException(
                "JSON parse error", new IllegalArgumentException("malformed JSON"));

        ResponseEntity<Result<Void>> response = handler.handleMessageNotReadable(error);

        assertErrorResponse(response, HttpStatus.BAD_REQUEST, ErrorCode.PARAM_INVALID);
    }

    @Test
    void handlesBeanValidationAsInvalidParameter() {
        Object target = new Object();
        BeanPropertyBindingResult bindingResult =
                new BeanPropertyBindingResult(target, "request");
        bindingResult.addError(new FieldError("request", "appKey", "too long"));
        MethodArgumentNotValidException error =
                new MethodArgumentNotValidException(null, bindingResult);

        ResponseEntity<Result<Void>> response = handler.handleValidation(error);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        assertEquals(ErrorCode.PARAM_INVALID.getCode(), response.getBody().getCode());
        assertEquals("appKey 参数不合法", response.getBody().getMessage());
    }

    private void assertBizResponse(ErrorCode errorCode, HttpStatus expectedStatus) {
        ResponseEntity<Result<Void>> response =
                handler.handleBiz(new BizException(errorCode));

        assertEquals(expectedStatus, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        assertEquals(errorCode.getCode(), response.getBody().getCode());
        assertEquals(errorCode.getMessage(), response.getBody().getMessage());
    }

    private void assertErrorResponse(ResponseEntity<Result<Void>> response,
                                     HttpStatus status, ErrorCode errorCode) {
        assertEquals(status, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isSuccess());
        assertEquals(errorCode.getCode(), response.getBody().getCode());
        assertEquals(errorCode.getMessage(), response.getBody().getMessage());
    }
}
