package com.aliyun.autowonder.common.error;

public class BizException extends RuntimeException {
    private final String code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public BizException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.code = errorCode.getCode();
    }

    public BizException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public String getCode() { return code; }
}
