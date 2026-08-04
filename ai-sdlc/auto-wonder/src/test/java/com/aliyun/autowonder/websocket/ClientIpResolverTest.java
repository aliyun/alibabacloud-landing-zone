package com.aliyun.autowonder.websocket;

import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import javax.websocket.Session;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientIpResolverTest {

    @Test
    void resolve_noHttpRequest_returnsNull() {
        Session session = mock(Session.class);
        when(session.getUserProperties()).thenReturn(new HashMap<>());
        assertNull(ClientIpResolver.resolve(session));
    }

    @Test
    void resolve_xffFirstIp() {
        Session session = sessionWithHeaders("203.0.113.50, 10.0.0.1", null, "127.0.0.1");
        assertEquals("203.0.113.50", ClientIpResolver.resolve(session));
    }

    @Test
    void resolve_xffUnknown_fallsToXRealIp() {
        Session session = sessionWithHeaders("unknown", "8.8.4.4", "127.0.0.1");
        assertEquals("8.8.4.4", ClientIpResolver.resolve(session));
    }

    @Test
    void resolve_xffBlank_fallsToXRealIp() {
        Session session = sessionWithHeaders("  ", "8.8.4.4", "127.0.0.1");
        assertEquals("8.8.4.4", ClientIpResolver.resolve(session));
    }

    @Test
    void resolve_noHeaders_fallsToRemoteAddr() {
        Session session = sessionWithHeaders(null, null, "192.168.1.100");
        assertEquals("192.168.1.100", ClientIpResolver.resolve(session));
    }

    @Test
    void resolve_allNull_returnsNull() {
        Session session = sessionWithHeaders(null, null, null);
        assertNull(ClientIpResolver.resolve(session));
    }

    @Test
    void resolve_truncatesAt64() {
        String longIp = "a".repeat(100);
        Session session = sessionWithHeaders(null, null, longIp);
        assertEquals(64, ClientIpResolver.resolve(session).length());
    }

    @Test
    void resolve_shortIp_notTruncated() {
        Session session = sessionWithHeaders(null, null, "1.2.3.4");
        assertEquals("1.2.3.4", ClientIpResolver.resolve(session));
    }

    @Test
    void resolve_xffMixedUnknownAndValid() {
        Session session = sessionWithHeaders("unknown, 203.0.113.1, 10.0.0.1", null, "127.0.0.1");
        assertEquals("203.0.113.1", ClientIpResolver.resolve(session));
    }

    private static Session sessionWithHeaders(String xff, String realIp, String remoteAddr) {
        HttpServletRequest httpReq = mock(HttpServletRequest.class);
        when(httpReq.getHeader("X-Forwarded-For")).thenReturn(xff);
        when(httpReq.getHeader("X-Real-IP")).thenReturn(realIp);
        when(httpReq.getRemoteAddr()).thenReturn(remoteAddr);

        Session session = mock(Session.class);
        Map<String, Object> props = new HashMap<>();
        props.put("javax.servlet.http.HttpServletRequest", httpReq);
        when(session.getUserProperties()).thenReturn(props);
        return session;
    }
}
