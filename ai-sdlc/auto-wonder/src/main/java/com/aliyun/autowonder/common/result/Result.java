package com.aliyun.autowonder.common.result;

import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.i18n.SystemMessages;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.log.BizLog;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.MDC;

public class Result<T> {
    private boolean success;
    private String code;
    private String message;
    private T data;
    private String traceId;
    @JsonProperty("request_id")
    private String requestId;

    public static <T> Result<T> ok(T data) {
        Result<T> r = new Result<>();
        r.success = true;
        r.code = ErrorCode.SUCCESS.getCode();
        r.message = "";
        r.data = data;
        r.traceId = currentTraceId();
        r.requestId = currentRequestId();
        recordBizOutcome(true, null, null);
        return r;
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return failKeyed(errorCode.getCode(), errorCode.getMessageKey(), null, errorCode.getMessage(), null);
    }

    public static <T> Result<T> fail(BizException error) {
        return failKeyed(error.getCode(), error.getMessageKey(), error.getMessageArgs(), error.getMessage(), null);
    }

    public static <T> Result<T> failKeyed(String code, String key, Object[] args, String diagnostic, T data) {
        return failure(code, SystemMessages.render(key, args, diagnostic), diagnostic, data);
    }

    public static <T> Result<T> fail(String code, String message) {
        return fail(code, message, null);
    }

    public static <T> Result<T> fail(String code, String message, T data) {
        return failure(code, message, message, data);
    }

    private static <T> Result<T> failure(String code, String message, String diagnostic, T data) {
        Result<T> r = new Result<>();
        r.success = false;
        r.code = code;
        r.message = message;
        r.data = data;
        r.traceId = currentTraceId();
        r.requestId = currentRequestId();
        recordBizOutcome(false, code, diagnostic);
        return r;
    }

    private static void recordBizOutcome(boolean success, String code, String message) {
        BizLog log = AutoWonderContext.get().getBizLog();
        if (log == null) {
            return;
        }
        log.setSuccess(success);
        log.setErrorCode(success ? null : code);
        log.setErrorMsg(success ? null : message);
    }

    private static String currentTraceId() {
        try {
            return AutoWonderContext.get().getTraceId();
        } catch (Exception e) {
            return null;
        }
    }

    private static String currentRequestId() {
        try {
            String requestId = AutoWonderContext.get().getRequestId();
            if (requestId != null) {
                return requestId;
            }
        } catch (Exception ignored) {
        }
        return MDC.get("requestId");
    }

    public boolean isSuccess() { return success; }
    public String getCode() { return code; }
    public String getMessage() { return message; }
    public T getData() { return data; }
    public String getTraceId() { return traceId; }
    @JsonProperty("request_id")
    public String getRequestId() { return requestId; }
}
