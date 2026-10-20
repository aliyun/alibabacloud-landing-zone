package com.aliyun.autowonder.auth.federation;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;

/** 基于 JDK HttpClient 的默认实现，不引入额外 HTTP 依赖。响应体不进异常信息，避免泄漏 IdP 返回的细节。 */
@Service
public class JdkFederationHttpTransport implements FederationHttpTransport {
    private final HttpClient httpClient;
    private final Duration timeout;

    public JdkFederationHttpTransport(FederationProperties properties) {
        this.timeout = Duration.ofSeconds(Math.max(1, properties.getHttpTimeoutSeconds()));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String get(String url, Map<String, String> headers) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET().timeout(timeout);
        if (headers != null) {
            headers.forEach(builder::header);
        }
        return send(builder.build());
    }

    @Override
    public String postForm(String url, Map<String, String> form, Map<String, String> headers) throws IOException {
        String body = (form == null ? Map.<String, String>of() : form).entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .map(entry -> urlEncode(entry.getKey()) + "=" + urlEncode(entry.getValue()))
                .collect(Collectors.joining("&"));
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (headers != null) {
            headers.forEach(builder::header);
        }
        return send(builder.build());
    }

    private String send(HttpRequest request) throws IOException {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("federation http call interrupted", e);
        }
        if (response.statusCode() / 100 != 2) {
            throw new IOException("federation endpoint returned status " + response.statusCode());
        }
        return response.body();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
