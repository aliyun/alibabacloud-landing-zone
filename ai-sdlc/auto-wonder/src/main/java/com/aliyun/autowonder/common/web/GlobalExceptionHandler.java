package com.aliyun.autowonder.common.web;

import com.aliyun.autowonder.access.OrgAccessDeniedException;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.dto.OrgAccessDeniedVO;
import com.aliyun.autowonder.common.error.AlreadyLoggedException;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import javax.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBiz(BizException ex) {
        if (!(ex.getCause() instanceof AlreadyLoggedException)) {
            LOGGER.warn("biz exception: code={}, msg={}", ex.getCode(), ex.getMessage());
        }
        HttpStatus status = HttpStatus.OK;
        if (ErrorCode.UNAUTHORIZED.getCode().equals(ex.getCode())) {
            status = HttpStatus.UNAUTHORIZED;
        } else if (ErrorCode.ORG_NOT_MEMBER.getCode().equals(ex.getCode())
                || ErrorCode.NO_PERMISSION.getCode().equals(ex.getCode())) {
            status = HttpStatus.FORBIDDEN;
        } else if (ErrorCode.PARAM_INVALID.getCode().equals(ex.getCode())
                || ErrorCode.ORG_ACCESS_LEVEL_INVALID.getCode().equals(ex.getCode())) {
            status = HttpStatus.BAD_REQUEST;
        } else if (ErrorCode.CONFLICT.getCode().equals(ex.getCode())
                || ErrorCode.ORG_OWNER_MUTATION_PROTECTED.getCode().equals(ex.getCode())
                || ErrorCode.ORG_SELF_LEVEL_MUTATION_FORBIDDEN.getCode().equals(ex.getCode())
                || ErrorCode.ORG_OWNER_TRANSFER_INVALID.getCode().equals(ex.getCode())) {
            status = HttpStatus.CONFLICT;
        }
        return ResponseEntity.status(status)
                .body(Result.fail(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(OrgAccessDeniedException.class)
    public ResponseEntity<Result<OrgAccessDeniedVO>> handleOrgAccessDenied(
            OrgAccessDeniedException ex) {
        OrgAccessDeniedVO data = new OrgAccessDeniedVO(
                ex.getCurrent(), ex.getRequired(), ex.getAction());
        Result<OrgAccessDeniedVO> result = Result.fail(
                ErrorCode.ORG_ACCESS_INSUFFICIENT.getCode(), ex.getMessage(), data);
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(result);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArg(IllegalArgumentException ex) {
        return Result.fail(ErrorCode.PARAM_INVALID.getCode(), ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Result<Void>> handleMessageNotReadable(
            HttpMessageNotReadableException ex) {
        Throwable rootCause = rootCause(ex);
        ErrorCode errorCode = rootCause instanceof InvalidFormatException invalidFormat
                && OrgAccessLevel.class.equals(invalidFormat.getTargetType())
                ? ErrorCode.ORG_ACCESS_LEVEL_INVALID
                : ErrorCode.PARAM_INVALID;
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(errorCode));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Void>> handleValidation(MethodArgumentNotValidException ex) {
        FieldError fieldError = ex.getBindingResult().getFieldError();
        String field = fieldError == null
                ? "request"
                : fieldError.getField();
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.fail(ErrorCode.PARAM_INVALID.getCode(), field + " 参数不合法"));
    }

    @ExceptionHandler(AlreadyLoggedException.class)
    public ResponseEntity<Result<Void>> handleAlreadyLogged(AlreadyLoggedException ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.fail(ErrorCode.SYSTEM_ERROR));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Result<Void>> handleMethodNotSupported(
            HttpRequestMethodNotSupportedException ex, HttpServletRequest request) {
        LOGGER.warn("method not supported method={} uri={} supported={} requestId={}",
                request.getMethod(), request.getRequestURI(), ex.getSupportedHttpMethods(),
                MDC.get("requestId"));
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Result.fail(ErrorCode.PARAM_INVALID.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(Throwable.class)
    public Result<Void> handleThrowable(Throwable ex) {
        if (ex instanceof AlreadyLoggedException) {
            return Result.fail(ErrorCode.SYSTEM_ERROR);
        }
        LOGGER.error("unexpected exception", ex);
        return Result.fail(ErrorCode.SYSTEM_ERROR);
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current;
    }
}
