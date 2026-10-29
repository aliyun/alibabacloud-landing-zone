package com.aliyun.autowonder.common.error;

public class BizException extends RuntimeException {
    private final String code;
    private final String messageKey;
    private final Object[] messageArgs;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
        this.messageKey = errorCode.getMessageKey();
        this.messageArgs = new Object[0];
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
        this.messageKey = null;
        this.messageArgs = new Object[0];
    }

    public BizException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.code = errorCode.getCode();
        this.messageKey = errorCode.getMessageKey();
        this.messageArgs = new Object[0];
    }

    public BizException(String code, String message, Throwable cause) {
        this(code, null, null, message, cause);
    }

    private BizException(String code, String key, Object[] args, String diagnostic, Throwable cause) {
        super(diagnostic, cause);
        this.code = code;
        this.messageKey = key;
        this.messageArgs = args == null ? new Object[0] : args.clone();
    }

    /** For subclasses that carry additional structured metadata besides the keyed message. */
    protected BizException(String code, String key, Object[] args, String diagnostic) {
        this(code, key, args, diagnostic, null);
    }

    public static BizException keyed(ErrorCode code, String key, Object[] args, String diagnostic) {
        return keyed(code, key, args, diagnostic, null);
    }

    public static BizException keyed(ErrorCode code, String key, Object[] args, String diagnostic, Throwable cause) {
        return keyed(code.getCode(), key, args, diagnostic, cause);
    }

    public static BizException keyed(String code, String key, Object[] args, String diagnostic, Throwable cause) {
        return new BizException(code, key, args, diagnostic, cause);
    }

    public String getCode() { return code; }
    public String getMessageKey() { return messageKey; }
    public Object[] getMessageArgs() { return messageArgs.clone(); }
}
