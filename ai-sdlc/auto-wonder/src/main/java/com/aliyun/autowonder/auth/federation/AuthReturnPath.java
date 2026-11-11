package com.aliyun.autowonder.auth.federation;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;

/**
 * 登录后回跳目标的服务端校验：只接受站内绝对路径。
 * 回调里携带的返回地址不可信，必须重新校验后才能进入 state、票据和完成页地址。
 */
public final class AuthReturnPath {
    public static final int MAX_LENGTH = 2048;

    private static final List<String> AUTH_ENTRY_PATHS = List.of("/login", "/register", "/sso");

    private AuthReturnPath() {
    }

    public static String sanitize(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            return null;
        }
        if (!value.startsWith("/") || value.startsWith("//")) {
            return null;
        }
        if (value.indexOf('\\') >= 0) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return null;
            }
        }
        try {
            URI uri = new URI(value);
            if (uri.isAbsolute() || uri.getRawAuthority() != null) {
                return null;
            }
            String normalized = uri.normalize().toString();
            // 点段归一化可能把 /..//evil.com 变成 //evil.com，必须复查；
            // 保留任何 .. 段也可能在不同解析器下越出站内路径，直接拒绝。
            if (!normalized.startsWith("/") || normalized.startsWith("//") || hasDotSegment(normalized)) {
                return null;
            }
            if (isAuthEntry(normalized)) {
                return null;
            }
            return normalized;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    private static boolean hasDotSegment(String path) {
        String pathname = path.split("[?#]", 2)[0];
        for (String segment : pathname.split("/", -1)) {
            if ("..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAuthEntry(String path) {
        String pathname = path.split("[?#]", 2)[0];
        for (String entry : AUTH_ENTRY_PATHS) {
            if (pathname.equals(entry) || pathname.startsWith(entry + "/")) {
                return true;
            }
        }
        return false;
    }
}
