package com.aliyun.autowonder.websocket;

import javax.servlet.http.HttpServletRequest;
import javax.websocket.Session;

public final class ClientIpResolver {

    private static final int MAX_IP_LENGTH = 64;

    private ClientIpResolver() {}

    public static String resolve(Session session) {
        Object req = session.getUserProperties().get("javax.servlet.http.HttpServletRequest");
        if (!(req instanceof HttpServletRequest)) {
            return null;
        }
        HttpServletRequest httpReq = (HttpServletRequest) req;

        String ip = firstValidHeader(httpReq, "X-Forwarded-For");
        if (ip == null) {
            ip = firstValidHeader(httpReq, "X-Real-IP");
        }
        if (ip == null) {
            ip = trimToNull(httpReq.getRemoteAddr());
        }
        return truncate(ip);
    }

    private static String firstValidHeader(HttpServletRequest req, String header) {
        String value = req.getHeader(header);
        if (value == null || value.isBlank()) {
            return null;
        }
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty() && !trimmed.equalsIgnoreCase("unknown")) {
                return trimmed;
            }
        }
        return null;
    }

    private static String trimToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static String truncate(String ip) {
        if (ip == null) {
            return null;
        }
        return ip.length() > MAX_IP_LENGTH ? ip.substring(0, MAX_IP_LENGTH) : ip;
    }
}
