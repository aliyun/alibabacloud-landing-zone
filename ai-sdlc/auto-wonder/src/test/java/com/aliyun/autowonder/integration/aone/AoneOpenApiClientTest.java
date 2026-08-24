package com.aliyun.autowonder.integration.aone;

import com.alibaba.fastjson.JSONObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AoneOpenApiClientTest {

    @Test
    void disabledClientRejectsBeforeHttpCall() {
        OkHttpClient httpClient = mock(OkHttpClient.class);
        AoneIntegrationProperties properties = new AoneIntegrationProperties();
        AoneOpenApiClient client = new AoneOpenApiClient(new AoneSignatureSigner(),
                () -> 1720680000000L, () -> { }, httpClient, properties);
        AoneOpenApiConfig config = config("https://aone.invalid");

        assertThrows(AoneDisabledException.class,
                () -> client.get(config, "/get", Map.of()));
        assertThrows(AoneDisabledException.class,
                () -> client.postForm(config, "/post", Map.of()));
        verifyNoInteractions(httpClient);
    }

    HttpServer server;
    String seenClientKey;
    String seenTimestamp;
    String seenSignature;
    String seenRegion;
    String seenBody;
    String seenRawQuery;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void postFormAddsSignedHeadersAndParsesResult() throws Exception {
        String baseUrl = startServer(200, "{\"success\":true,\"result\":{\"ok\":true}}");
        AoneOpenApiClient client = client();
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("akProjectId", "2161074");
        form.put("stamp", "Req,Bug,Task");

        JSONObject result = client.postForm(config(baseUrl), "/issue/openapi/IssueTopService/searchV4",
                form);

        assertEquals(Boolean.TRUE, result.getBoolean("ok"));
        assertEquals("auto-wonder", seenClientKey);
        assertEquals("1720680000000", seenTimestamp);
        assertEquals("1", seenRegion);
        assertEquals("0xSCEN8_v0H-PPIqpDLGhQMQBumpi9byCrdrXRoFpnixvkl5tCP1irXuT05LN4pW", seenSignature);
        assertEquals("akProjectId=2161074&stamp=Req%2CBug%2CTask", seenBody);
    }

    @Test
    void postFormPercentEncodesMultibyteAndReservedCharsIntoBody() throws Exception {
        String baseUrl = startServer(200, "{\"success\":true,\"result\":{\"ok\":true}}");
        AoneOpenApiClient client = client();
        String longContent = "标题 & 特殊=字符\n" + "长内容".repeat(600);
        Map<String, Object> form = new LinkedHashMap<>();
        form.put("targetType", "Issue");
        form.put("targetId", "85299639");
        form.put("user", "WORKER_1");
        form.put("content", longContent);

        client.postForm(config(baseUrl), "/issue/openapi/IssueTopService/createComment", form);

        // Content must travel in the body, percent-encoded: a GET query string of this size
        // exceeds gateway URL length limits.
        assertTrue(seenRawQuery == null || seenRawQuery.isBlank());
        assertTrue(seenBody.startsWith("targetType=Issue&targetId=85299639&user=WORKER_1&content="));
        assertTrue(seenBody.contains("%26") && seenBody.contains("%3D"));
        assertFalse(seenBody.contains("长内容"));
    }

    @Test
    void throwsUsefulErrorForAoneFailure() throws Exception {
        String baseUrl = startServer(200, "{\"success\":false,\"message\":\"bad auth\"}");
        AoneOpenApiClient client = client();

        AoneOpenApiException error = assertThrows(AoneOpenApiException.class,
                () -> client.postForm(config(baseUrl), "/issue/openapi/IssueTopService/searchV4", Map.of()));

        assertTrue(error.getMessage().contains("bad auth"));
    }

    @Test
    void businessFailureIsTerminal() throws Exception {
        String baseUrl = startServer(200, "{\"success\":false,\"message\":\"状态流转限制,不能将状态置为Closed\"}");
        AoneOpenApiClient client = client();

        AoneOpenApiException error = assertThrows(AoneOpenApiException.class,
                () -> client.postForm(config(baseUrl), "/issue/openapi/IssueTopService/update", Map.of()));

        assertTrue(error.isTerminal());
    }

    @Test
    void rateLimitFailureIsNotTerminal() throws Exception {
        String baseUrl = startServer(200, "{\"success\":false,\"message\":\"auto-wonder invoke IssueTopService-searchV4 over limit, over rate limit. rate limit is 100\"}");
        AoneOpenApiClient client = client();

        AoneOpenApiException error = assertThrows(AoneOpenApiException.class,
                () -> client.postForm(config(baseUrl), "/issue/openapi/IssueTopService/searchV4", Map.of()));

        assertFalse(error.isTerminal());
    }

    @Test
    void serverErrorIsNotTerminal() throws Exception {
        String baseUrl = startServer(503, "{\"success\":false,\"message\":\"service unavailable\"}");
        AoneOpenApiClient client = client();

        AoneOpenApiException error = assertThrows(AoneOpenApiException.class,
                () -> client.postForm(config(baseUrl), "/issue/openapi/IssueTopService/searchV4", Map.of()));

        assertFalse(error.isTerminal());
    }

    @Test
    void preservesAoneEnvelopeForPrimitiveResult() throws Exception {
        String baseUrl = startServer(200, "{\"success\":true,\"result\":true,\"message\":\"comment id:124709025\",\"totalCount\":3}");
        AoneOpenApiClient client = client();

        JSONObject result = client.postForm(config(baseUrl), "/issue/openapi/IssueTopService/createComment", Map.of());

        assertEquals(Boolean.TRUE, result.getBoolean("result"));
        assertEquals("comment id:124709025", result.getString("message"));
        assertEquals(3, result.getIntValue("totalCount"));
    }

    @Test
    void acquiresRatePermitBeforeEachRequest() throws Exception {
        String baseUrl = startServer(200, "{\"success\":true,\"result\":{\"ok\":true}}");
        AtomicInteger acquired = new AtomicInteger();
        AoneRateLimiter countingLimiter = new AoneRateLimiter(1000.0) {
            @Override
            public void acquire() {
                acquired.incrementAndGet();
                super.acquire();
            }
        };
        AoneOpenApiClient client = client(countingLimiter);

        client.get(config(baseUrl), "/issue/openapi/IssueTopService/getById", Map.of("id", "1"));
        client.postForm(config(baseUrl), "/issue/openapi/IssueTopService/searchV4", Map.of());

        assertEquals(2, acquired.get());
    }

    @Test
    void getUrlEncodesQueryValues() throws Exception {
        String baseUrl = startServer(200, "{\"success\":true,\"result\":{\"ok\":true}}");
        AoneOpenApiClient client = client();

        client.get(config(baseUrl), "/ak/project/openapi/ProjectApiFacade/searchByQuery",
                Map.of("query", "{\"region\":\"alibaba\",\"name\":\"auto wonder\"}"));

        assertEquals("query=%7B%22region%22%3A%22alibaba%22%2C%22name%22%3A%22auto+wonder%22%7D", seenRawQuery);
    }

    private AoneOpenApiConfig config(String baseUrl) {
        return new AoneOpenApiConfig(baseUrl, "auto-wonder", "MDEyMzQ1Njc4OWFiY2RlZg==", "1");
    }

    private AoneOpenApiClient client() {
        return client(() -> { });
    }

    private AoneOpenApiClient client(AoneThrottle throttle) {
        AoneIntegrationProperties properties = new AoneIntegrationProperties();
        properties.setEnabled(true);
        return new AoneOpenApiClient(new AoneSignatureSigner(), () -> 1720680000000L,
                throttle, new OkHttpClient(), properties);
    }

    private String startServer(int status, String response) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/", exchange -> {
            capture(exchange);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private void capture(HttpExchange exchange) throws IOException {
        seenClientKey = exchange.getRequestHeaders().getFirst("clientKey");
        seenTimestamp = exchange.getRequestHeaders().getFirst("timestamp");
        seenSignature = exchange.getRequestHeaders().getFirst("signature");
        seenRegion = exchange.getRequestHeaders().getFirst("Ao-Region-Id");
        seenRawQuery = exchange.getRequestURI().getRawQuery();
        seenBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }
}
