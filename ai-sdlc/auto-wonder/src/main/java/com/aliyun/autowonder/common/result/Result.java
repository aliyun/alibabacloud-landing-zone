package com.aliyun.autowonder.common.result;

import com.alibaba.fastjson.annotation.JSONField;
import com.aliyun.autowonder.common.error.ErrorCode;
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
    @JSONField(name = "request_id")
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
        return fail(errorCode.getCode(), errorCode.getMessage());
    }

    public static <T> Result<T> fail(String code, String message) {
        return fail(code, message, null);
    }

    public static <T> Result<T> fail(String code, String message, T data) {
        Result<T> r = new Result<>();
        r.success = false;
        r.code = code;
        r.message = message;
        r.data = data;
        r.traceId = currentTraceId();
        r.requestId = currentRequestId();
        recordBizOutcome(false, code, message);
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
    @JSONField(name = "request_id")
    public String getRequestId() { return requestId; }
}
