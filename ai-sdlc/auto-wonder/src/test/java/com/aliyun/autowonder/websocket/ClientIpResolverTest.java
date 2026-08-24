package com.aliyun.autowonder.websocket;

import org.junit.jupiter.api.Test;

import javax.websocket.HandshakeResponse;
import javax.websocket.Session;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpointConfig;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClientIpResolverTest {

    @Test
    void resolve_noCapturedHeaders_returnsNull() {
        Session session = mock(Session.class);
        when(session.getUserProperties()).thenReturn(new HashMap<>());
        assertNull(ClientIpResolver.resolve(session));
    }

    @Test
    void resolve_xffFirstIp() {
        Session session = sessionWithHeaders(headerMap("203.0.113.50, 10.0.0.1", null));
        assertEquals("203.0.113.50", ClientIpResolver.resolve(session));
    }

    @Test
    void resolve_xffUnknown_fallsToXRealIp() {
        Session session = sessionWithHeaders(headerMap("unknown", "8.8.4.4"));
        assertEquals("8.8.4.4", ClientIpResolver.resolve(session));
    }

    @Test
    void resolve_xffBlank_fallsToXRealIp() {
        Session session = sessionWithHeaders(headerMap("  ", "8.8.4.4"));
        assertEquals("8.8.4.4", ClientIpResolver.resolve(session));
    }

    @Test
    void resolve_noIpHeaders_returnsNull() {
        Session session = sessionWithHeaders(headerMap(null, null));
        assertNull(ClientIpResolver.resolve(session));
    }

    @Test
    void resolve_truncatesAt64() {
        String longIp = "a".repeat(100);
        Session session = sessionWithHeaders(headerMap(null, longIp));
        assertEquals(64, ClientIpResolver.resolve(session).length());
    }

    @Test
    void resolve_shortIp_notTruncated() {
        Session session = sessionWithHeaders(headerMap(null, "1.2.3.4"));
        assertEquals("1.2.3.4", ClientIpResolver.resolve(session));
    }

    @Test
    void resolve_xffMixedUnknownAndValid() {
        Session session = sessionWithHeaders(headerMap("unknown, 203.0.113.1, 10.0.0.1", null));
        assertEquals("203.0.113.1", ClientIpResolver.resolve(session));
    }

    @Test
    void resolve_headerNameCaseInsensitive() {
        HandshakeHeaderCapturingConfigurator configurator = new HandshakeHeaderCapturingConfigurator();
        HandshakeRequest request = mock(HandshakeRequest.class);
        Map<String, List<String>> raw = new HashMap<>();
        raw.put("X-FORWARDED-FOR", List.of("203.0.113.9"));
        when(request.getHeaders()).thenReturn(raw);

        ServerEndpointConfig sec = ServerEndpointConfig.Builder
                .create(Object.class, "/ws/executor").build();
        configurator.modifyHandshake(sec, request, mock(HandshakeResponse.class));

        Session session = mock(Session.class);
        when(session.getUserProperties()).thenReturn(sec.getUserProperties());
        assertEquals("203.0.113.9", ClientIpResolver.resolve(session));
    }

    @Test
    void configurator_capturesLowercasedHeaders() {
        HandshakeHeaderCapturingConfigurator configurator = new HandshakeHeaderCapturingConfigurator();
        HandshakeRequest request = mock(HandshakeRequest.class);
        Map<String, List<String>> raw = new HashMap<>();
        raw.put("X-Forwarded-For", List.of("203.0.113.50"));
        raw.put("X-Real-IP", List.of("10.0.0.9"));
        raw.put(null, List.of("ignored"));
        raw.put("X-Empty", null);
        when(request.getHeaders()).thenReturn(raw);

        ServerEndpointConfig sec = ServerEndpointConfig.Builder
                .create(Object.class, "/ws/executor").build();
        configurator.modifyHandshake(sec, request, mock(HandshakeResponse.class));

        @SuppressWarnings("unchecked")
        Map<String, List<String>> captured = (Map<String, List<String>>)
                sec.getUserProperties().get(HandshakeHeaderCapturingConfigurator.HANDSHAKE_HEADERS_KEY);
        assertNotNull(captured);
        assertEquals(List.of("203.0.113.50"), captured.get("x-forwarded-for"));
        assertEquals(List.of("10.0.0.9"), captured.get("x-real-ip"));
        assertFalse(captured.containsKey("x-empty"));
    }

    @Test
    void configurator_nullHeaderMap_storesEmptyMap() {
        HandshakeHeaderCapturingConfigurator configurator = new HandshakeHeaderCapturingConfigurator();
        HandshakeRequest request = mock(HandshakeRequest.class);
        when(request.getHeaders()).thenReturn(null);

        ServerEndpointConfig sec = ServerEndpointConfig.Builder
                .create(Object.class, "/ws/executor").build();
        configurator.modifyHandshake(sec, request, mock(HandshakeResponse.class));

        @SuppressWarnings("unchecked")
        Map<String, List<String>> captured = (Map<String, List<String>>)
                sec.getUserProperties().get(HandshakeHeaderCapturingConfigurator.HANDSHAKE_HEADERS_KEY);
        assertNotNull(captured);
        assertTrue(captured.isEmpty());
    }

    private static Map<String, List<String>> headerMap(String xff, String realIp) {
        Map<String, List<String>> headers = new HashMap<>();
        if (xff != null) {
            headers.put("x-forwarded-for", new ArrayList<>(Arrays.asList(xff)));
        }
        if (realIp != null) {
            headers.put("x-real-ip", new ArrayList<>(Arrays.asList(realIp)));
        }
        return headers;
    }

    private static Session sessionWithHeaders(Map<String, List<String>> headers) {
        Session session = mock(Session.class);
        Map<String, Object> props = new HashMap<>();
        props.put(HandshakeHeaderCapturingConfigurator.HANDSHAKE_HEADERS_KEY, headers);
        when(session.getUserProperties()).thenReturn(props);
        return session;
    }
}
