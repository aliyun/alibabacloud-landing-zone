package com.aliyun.autowonder.websocket;

import javax.websocket.Session;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ClientIpResolver {

    private static final int MAX_IP_LENGTH = 64;

    private ClientIpResolver() {}

    public static String resolve(Session session) {
        Map<String, List<String>> headers = handshakeHeaders(session);
        if (headers == null) {
            return null;
        }
        String ip = firstValidHeader(headers, "x-forwarded-for");
        if (ip == null) {
            ip = firstValidHeader(headers, "x-real-ip");
        }
        return truncate(ip);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, List<String>> handshakeHeaders(Session session) {
        Object value = session.getUserProperties()
                .get(HandshakeHeaderCapturingConfigurator.HANDSHAKE_HEADERS_KEY);
        if (value instanceof Map) {
            return (Map<String, List<String>>) value;
        }
        return null;
    }

    private static String firstValidHeader(Map<String, List<String>> headers, String name) {
        List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            for (String part : value.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty() && !trimmed.equalsIgnoreCase("unknown")) {
                    return trimmed;
                }
            }
        }
        return null;
    }

    private static String truncate(String ip) {
        if (ip == null) {
            return null;
        }
        return ip.length() > MAX_IP_LENGTH ? ip.substring(0, MAX_IP_LENGTH) : ip;
    }
}
