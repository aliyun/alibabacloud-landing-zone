package com.aliyun.autowonder.integration.dingtalk;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DingTalkOkHttpExchangeTest {

    @Test
    void nonSuccessThrowsTypedSafeException() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/send", exchange -> {
            byte[] body = ("{\"code\":\"authFailed\",\"requestid\":\"req-401\","
                    + "\"message\":\"provider raw secret\"}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            DingTalkOutboundSenderConfig.OkHttpExchange exchange =
                    new DingTalkOutboundSenderConfig.OkHttpExchange();

            DingTalkHttpException error = assertThrows(DingTalkHttpException.class,
                    () -> exchange.post("http://127.0.0.1:" + server.getAddress().getPort() + "/send",
                            "{}", Map.of()));

            assertEquals(401, error.getStatus());
            assertEquals("authFailed", error.getProviderCode());
            assertFalse(error.getMessage().contains("provider raw secret"));
        } finally {
            server.stop(0);
        }
    }
}
