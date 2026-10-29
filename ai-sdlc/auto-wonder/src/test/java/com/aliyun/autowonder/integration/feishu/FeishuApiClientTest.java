package com.aliyun.autowonder.integration.feishu;

import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class FeishuApiClientTest {
    @Test void cachesTokenSendsCorrectReplyAndReusesChunkIdsOnRetry() throws Exception {
        var f = new FeishuTestSupport(); var b = f.create();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var tokens = new AtomicInteger(); var bodies = new ArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var auths = new ArrayList<String>();
        server.createContext("/auth/v3/tenant_access_token/internal", e -> {
            tokens.incrementAndGet(); var request = f.json.readTree(e.getRequestBody());
            assertEquals("cli_test", request.path("app_id").asText()); assertEquals("secret", request.path("app_secret").asText());
            respond(e, "{\"code\":0,\"tenant_access_token\":\"token\",\"expire\":7200}");
        });
        server.createContext("/bot/v3/info", e -> respond(e, "{\"code\":0,\"bot\":{\"open_id\":\"ou_bot\"}}"));
        server.createContext("/im/v1/messages/om_message/reply", e -> {
            auths.add(e.getRequestHeaders().getFirst("Authorization")); bodies.add(f.json.readTree(e.getRequestBody()));
            respond(e, "{\"code\":0,\"data\":{\"message_id\":\"om_reply\"}}");
        });
        server.start();
        try {
            var api = new FeishuApiClient(f.service, f.json, new OkHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort());
            assertEquals("ou_bot", api.botOpenId(b));
            String text = "你好😀".repeat(1000);
            api.reply(b, "om_message", text, "source"); api.reply(b, "om_message", text, "source");
            assertEquals(1, tokens.get()); assertEquals(4, bodies.size());
            assertEquals(bodies.get(0).path("uuid"), bodies.get(2).path("uuid"));
            assertEquals("text", bodies.get(0).path("msg_type").asText());
            String result = f.json.readTree(bodies.get(0).path("content").asText()).path("text").asText()
                    + f.json.readTree(bodies.get(1).path("content").asText()).path("text").asText();
            assertEquals(text, result); assertTrue(auths.stream().allMatch("Bearer token"::equals));
            assertThrows(IllegalArgumentException.class, () -> api.reply(b, "../evil", "hello", "source"));
        } finally { server.stop(0); }
    }
    @Test void providerErrorsExposeOnlySafeNumericCode() throws Exception {
        var f = new FeishuTestSupport(); var b = f.create(); var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/auth/v3/tenant_access_token/internal", e -> respond(e, "{\"code\":10003,\"msg\":\"private secret\"}"));
        server.start();
        try {
            var api = new FeishuApiClient(f.service, f.json, new OkHttpClient(), "http://127.0.0.1:" + server.getAddress().getPort());
            var error = assertThrows(IllegalStateException.class, () -> api.botOpenId(b));
            assertTrue(error.getMessage().contains("10003")); assertFalse(error.getMessage().contains("private"));
        } finally { server.stop(0); }
    }
    @Test void sharedConfigUsesPlatformCredentialsAndRefreshesOnRotation() throws Exception {
        var f = new FeishuTestSupport();
        var config = new PlatformImChannelConfigDO();
        config.setId(50L); config.setProvider("FEISHU"); config.setPurpose("GROUP_CHAT");
        config.setAppKey("cli_shared");
        var bindings = mock(FeishuBindingService.class);
        var secrets = new AtomicReference<>(
                new FeishuBindingService.Secrets("shared-secret", "shared-token", null));
        when(bindings.secrets(config)).thenAnswer(i -> secrets.get());
        when(bindings.credentialCacheKey(config)).thenReturn("SHARED:50:3");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var authBodies = new ArrayList<String>();
        var authorizations = new ArrayList<String>();
        server.createContext("/auth/v3/tenant_access_token/internal", e -> {
            authBodies.add(f.json.readTree(e.getRequestBody()).path("app_secret").asText());
            respond(e, "{\"code\":0,\"tenant_access_token\":\"token\",\"expire\":7200}");
        });
        server.createContext("/bot/v3/info", e -> respond(e, "{\"code\":0,\"bot\":{\"open_id\":\"ou_shared\"}}"));
        server.createContext("/im/v1/messages/om_message/reply", e -> {
            authorizations.add(e.getRequestHeaders().getFirst("Authorization"));
            respond(e, "{\"code\":0,\"data\":{\"message_id\":\"om_reply\"}}");
        });
        server.start();
        try {
            var api = new FeishuApiClient(bindings, f.json, new OkHttpClient(),
                    "http://127.0.0.1:" + server.getAddress().getPort());
            assertEquals("ou_shared", api.botOpenIdForConfig(config));
            api.replyForConfig(config, "om_message", "hello", "source");
            assertEquals(java.util.List.of("shared-secret"), authBodies);
            assertEquals(java.util.List.of("Bearer token"), authorizations);

            when(bindings.credentialCacheKey(config)).thenReturn("SHARED:50:4");
            secrets.set(new FeishuBindingService.Secrets("rotated-secret", "shared-token", null));
            assertEquals("ou_shared", api.botOpenIdForConfig(config));
            assertEquals(java.util.List.of("shared-secret", "rotated-secret"), authBodies);
        } finally { server.stop(0); }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange e, String text) throws java.io.IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8); e.getResponseHeaders().set("Content-Type", "application/json");
        e.sendResponseHeaders(200, bytes.length); e.getResponseBody().write(bytes); e.close();
    }
}
