package com.aliyun.autowonder.auth.federation;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 回环 HTTP 桩覆盖默认传输实现：请求构造、表单编码、失败语义与中断恢复。 */
class JdkFederationHttpTransportTest {
    private HttpServer server;
    private String baseUrl;
    private JdkFederationHttpTransport transport;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        transport = new JdkFederationHttpTransport(new FederationProperties());
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void getSendsConfiguredHeadersAndReturnsBody() throws Exception {
        RecordingHandler handler = new RecordingHandler(200, "jwks-document");
        server.createContext("/keys", handler);

        String body = transport.get(baseUrl + "/keys",
                Map.of("Accept", "application/json", "X-Federation-Probe", "on"));

        assertEquals("jwks-document", body);
        assertEquals("GET", handler.method);
        assertEquals("application/json", handler.headers.getFirst("Accept"));
        assertEquals("on", handler.headers.getFirst("X-Federation-Probe"));
        assertEquals("", handler.requestBody);
    }

    @Test
    void getWithoutHeadersStillReturnsBody() throws Exception {
        server.createContext("/keys", new RecordingHandler(200, "ok"));

        assertEquals("ok", transport.get(baseUrl + "/keys", null));
    }

    @Test
    void postFormUrlEncodesBodyAndDeclaresFormContentType() throws Exception {
        RecordingHandler handler = new RecordingHandler(200, "{\"access_token\":\"t\"}");
        server.createContext("/token", handler);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", "a+b/c d");
        form.put("client_id", "cid");

        String body = transport.postForm(baseUrl + "/token", form, Map.of("X-Federation-Probe", "on"));

        assertEquals("grant_type=authorization_code&code=a%2Bb%2Fc+d&client_id=cid", handler.requestBody);
        assertEquals("POST", handler.method);
        assertEquals("application/x-www-form-urlencoded", handler.headers.getFirst("Content-Type"));
        assertEquals("application/json", handler.headers.getFirst("Accept"));
        assertEquals("on", handler.headers.getFirst("X-Federation-Probe"));
        assertEquals("{\"access_token\":\"t\"}", body);
    }

    @Test
    void postFormWithoutFormSendsEmptyBody() throws Exception {
        RecordingHandler handler = new RecordingHandler(200, "{}");
        server.createContext("/token", handler);

        assertEquals("{}", transport.postForm(baseUrl + "/token", null, null));

        assertEquals("POST", handler.method);
        assertEquals("", handler.requestBody);
    }

    @Test
    void postFormDropsNullValuedFields() throws Exception {
        RecordingHandler handler = new RecordingHandler(200, "{}");
        server.createContext("/token", handler);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", "cid");
        form.put("client_secret", null);
        form.put("code", "xyz");

        transport.postForm(baseUrl + "/token", form, null);

        assertEquals("client_id=cid&code=xyz", handler.requestBody);
    }

    @Test
    void nonSuccessStatusFailsWithoutLeakingResponseBody() throws Exception {
        server.createContext("/keys", new RecordingHandler(500, "idp-internal-detail"));

        IOException failure = assertThrows(IOException.class,
                () -> transport.get(baseUrl + "/keys", null));

        assertEquals("federation endpoint returned status 500", failure.getMessage());
        assertFalse(failure.getMessage().contains("idp-internal-detail"));
    }

    @Test
    void redirectIsNotFollowedAndTreatedAsFailure() throws Exception {
        server.createContext("/keys", new RecordingHandler(302, "",
                Map.of("Location", "http://127.0.0.1:1/elsewhere")));

        IOException failure = assertThrows(IOException.class,
                () -> transport.get(baseUrl + "/keys", null));

        assertEquals("federation endpoint returned status 302", failure.getMessage());
    }

    @Test
    void interruptedSendFailsAndRestoresInterruptFlag() throws Exception {
        CountDownLatch arrived = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/slow", exchange -> {
            arrived.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                exchange.sendResponseHeaders(200, 0);
            } catch (IOException ignored) {
                // 客户端中断后连接已关闭；桩服务器无需再响应。
            } finally {
                exchange.close();
            }
        });

        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptFlagRestored = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                transport.get(baseUrl + "/slow", null);
                failure.set(new IllegalStateException("expected IOException but call returned"));
            } catch (IOException e) {
                failure.set(e);
                interruptFlagRestored.set(Thread.currentThread().isInterrupted());
            }
        });
        try {
            worker.start();
            assertTrue(arrived.await(5, TimeUnit.SECONDS), "桩服务器未收到请求");
            worker.interrupt();
            worker.join(5000);
        } finally {
            release.countDown();
        }

        assertFalse(worker.isAlive(), "中断后传输调用未返回");
        Throwable thrown = failure.get();
        assertTrue(thrown instanceof IOException, "预期 IOException，实际: " + thrown);
        assertTrue(thrown.getMessage().contains("interrupted"), "异常信息不含中断语义: " + thrown);
        assertTrue(interruptFlagRestored.get(), "中断标记未恢复");
    }

    private static final class RecordingHandler implements HttpHandler {
        private final int status;
        private final String body;
        private final Map<String, String> responseHeaders;
        private volatile String method;
        private volatile Headers headers;
        private volatile String requestBody;

        RecordingHandler(int status, String body) {
            this(status, body, Map.of());
        }

        RecordingHandler(int status, String body, Map<String, String> responseHeaders) {
            this.status = status;
            this.body = body;
            this.responseHeaders = responseHeaders;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            method = exchange.getRequestMethod();
            headers = exchange.getRequestHeaders();
            requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            responseHeaders.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
            byte[] payload = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(payload);
            }
        }
    }
}
