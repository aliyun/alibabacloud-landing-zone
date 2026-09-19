package com.aliyun.autowonder.im;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeishuImProviderTest {
    @Test
    void authenticatesAndSendsRichTextToUserId() throws Exception {
        var configs = mock(PlatformImChannelConfigService.class);
        var config = new PlatformImChannelConfigDO();
        config.setAppKey("cli_test");
        when(configs.findEnabled("FEISHU")).thenReturn(config);
        when(configs.decryptSecret(config)).thenReturn("test-secret");
        var json = new ObjectMapper();
        var authBody = new AtomicReference<String>();
        var messageBody = new AtomicReference<String>();
        var authorization = new AtomicReference<String>();
        var query = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/v3/tenant_access_token/internal", e -> {
            authBody.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(e, 200, "{\"code\":0,\"tenant_access_token\":\"test-token\"}");
        });
        server.createContext("/im/v1/messages", e -> {
            authorization.set(e.getRequestHeaders().getFirst("Authorization"));
            query.set(e.getRequestURI().getQuery());
            messageBody.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(e, 200, "{\"code\":0}");
        });
        server.start();
        try {
            var provider = new FeishuImProvider(configs, json, new OkHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort());
            provider.send(new ImSendCommand("FEISHU", "user-123", "指派提醒", "**新工单**"));
            assertEquals("cli_test", json.readTree(authBody.get()).path("app_id").asText());
            assertEquals("test-secret", json.readTree(authBody.get()).path("app_secret").asText());
            assertEquals("Bearer test-token", authorization.get());
            assertEquals("receive_id_type=user_id", query.get());
            var body = json.readTree(messageBody.get());
            assertEquals("user-123", body.path("receive_id").asText());
            assertEquals("post", body.path("msg_type").asText());
            assertTrue(body.path("content").asText().contains("新工单"));
        } finally { server.stop(0); }
    }

    @Test
    void providerFailuresAreSanitizedAndRateLimitsAreRetryable() throws Exception {
        var configs = mock(PlatformImChannelConfigService.class);
        var config = new PlatformImChannelConfigDO(); config.setAppKey("cli_test");
        when(configs.findEnabled("FEISHU")).thenReturn(config);
        when(configs.decryptSecret(config)).thenReturn("secret");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var code = new java.util.concurrent.atomic.AtomicInteger(429);
        server.createContext("/auth/v3/tenant_access_token/internal", e -> respond(e, code.get(), "{\"code\":10003,\"msg\":\"private secret\"}"));
        server.start();
        try {
            var provider = new FeishuImProvider(configs, new ObjectMapper(), new OkHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort());
            var command = new ImSendCommand("FEISHU", "user", "title", "body");
            assertTrue(assertThrows(ImDeliveryException.class, () -> provider.send(command)).isRetryable());
            code.set(200);
            var error = assertThrows(ImDeliveryException.class, () -> provider.send(command));
            assertFalse(error.isRetryable()); assertEquals("10003", error.getProviderCode());
            assertFalse(error.toString().contains("private"));
        } finally { server.stop(0); }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange e, int status, String text) throws java.io.IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        e.sendResponseHeaders(status, bytes.length); e.getResponseBody().write(bytes); e.close();
    }
}
