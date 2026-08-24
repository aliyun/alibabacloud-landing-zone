package com.aliyun.autowonder.websocket;

import javax.websocket.HandshakeResponse;
import javax.websocket.server.HandshakeRequest;
import javax.websocket.server.ServerEndpointConfig;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JSR-356 containers (e.g. Tomcat) never expose the HttpServletRequest on the
 * WebSocket Session, so client IP must be captured from the handshake request
 * headers here, before the upgrade completes.
 */
public class HandshakeHeaderCapturingConfigurator extends ServerEndpointConfig.Configurator {

    public static final String HANDSHAKE_HEADERS_KEY = "autowonder.handshake.headers";

    @Override
    public void modifyHandshake(ServerEndpointConfig sec, HandshakeRequest request,
            HandshakeResponse response) {
        Map<String, List<String>> headers = new HashMap<>();
        if (request.getHeaders() != null) {
            for (Map.Entry<String, List<String>> entry : request.getHeaders().entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                headers.put(entry.getKey().toLowerCase(Locale.ROOT),
                        new ArrayList<>(entry.getValue()));
            }
        }
        sec.getUserProperties().put(HANDSHAKE_HEADERS_KEY, headers);
    }
}
