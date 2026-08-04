package com.aliyun.autowonder.integration.generic;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.integration.aone.AoneOpenApiConfig;
import com.aliyun.autowonder.integration.aone.AoneOpenApiException;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericHttpWorkitemWritebackProviderTest {

    @Test
    void updateContentPutsStandardPayloadToExternalWorkitemEndpoint() {
        AtomicReference<Request> captured = new AtomicReference<>();
        GenericHttpWorkitemWritebackProvider provider = new GenericHttpWorkitemWritebackProvider(
                clientReturning(captured, 204, ""));

        provider.updateContent("JIRA", new AoneOpenApiConfig("http://example.com/", "auto-wonder",
                        "token-1", "1"),
                "JIRA-123", "跨系统标题", "跨系统正文");

        Request request = captured.get();
        assertEquals("PUT", request.method());
        assertEquals("http://example.com/api/workitems/JIRA-123/content", request.url().toString());
        assertEquals("JIRA", request.header("X-AutoWonder-Provider"));
        assertEquals("auto-wonder", request.header("X-AutoWonder-Client-Key"));
        assertEquals("Bearer token-1", request.header("Authorization"));
        JSONObject payload = JSON.parseObject(requestBody(request));
        assertEquals("JIRA", payload.getString("provider"));
        assertEquals("JIRA-123", payload.getString("externalWorkitemId"));
        assertEquals("跨系统标题", payload.getString("title"));
        assertEquals("跨系统正文", payload.getString("contentMd"));
    }

    @Test
    void updateContentThrowsWhenRemoteReturnsError() {
        GenericHttpWorkitemWritebackProvider provider = new GenericHttpWorkitemWritebackProvider(
                clientReturning(new AtomicReference<>(), 500, "boom"));

        AoneOpenApiException error = assertThrows(AoneOpenApiException.class,
                () -> provider.updateContent("JIRA",
                        new AoneOpenApiConfig("http://example.com", null, null, null),
                        "JIRA-123", "title", "body"));

        assertTrue(error.getMessage().contains("HTTP 500 boom"));
    }

    private OkHttpClient clientReturning(AtomicReference<Request> captured, int code, String body) {
        return new OkHttpClient.Builder()
                .addInterceptor((Interceptor) chain -> {
                    Request request = chain.request();
                    captured.set(request);
                    return new Response.Builder()
                            .request(request)
                            .protocol(Protocol.HTTP_1_1)
                            .code(code)
                            .message(code >= 200 && code < 300 ? "OK" : "ERROR")
                            .body(ResponseBody.create(body, MediaType.parse("text/plain")))
                            .build();
                })
                .build();
    }

    private String requestBody(Request request) {
        try {
            okio.Buffer buffer = new okio.Buffer();
            request.body().writeTo(buffer);
            return buffer.readUtf8();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
