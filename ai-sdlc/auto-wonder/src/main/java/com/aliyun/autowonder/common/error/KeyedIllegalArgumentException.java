package com.aliyun.autowonder.common.error;

/** Explicit system validation metadata; superclass diagnostics and catchability stay raw. */
public final class KeyedIllegalArgumentException extends IllegalArgumentException {
    private final String messageKey;
    private final Object[] messageArgs;

    public KeyedIllegalArgumentException(String key, Object[] args, String diagnostic) {
        this(key, args, diagnostic, null);
    }

    public KeyedIllegalArgumentException(String key, Object[] args, String diagnostic, Throwable cause) {
        super(diagnostic, cause);
        this.messageKey = key;
        this.messageArgs = args == null ? new Object[0] : args.clone();
    }

    public String getMessageKey() { return messageKey; }
    public Object[] getMessageArgs() { return messageArgs.clone(); }
}
