package com.aliyun.autowonder.integration.aone;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class AoneOpenApiClient {

    private static final MediaType FORM = MediaType.parse("application/x-www-form-urlencoded");

    private final AoneSignatureSigner signer;
    private final ClockMillis clock;
    private final AoneThrottle rateLimiter;
    private final OkHttpClient httpClient;
    private final AoneIntegrationProperties properties;

    @Autowired
    public AoneOpenApiClient(DistributedAoneRateLimiter rateLimiter,
                             AoneIntegrationProperties properties) {
        this(new AoneSignatureSigner(), System::currentTimeMillis, rateLimiter,
                new OkHttpClient.Builder()
                        .connectTimeout(5, TimeUnit.SECONDS)
                        .readTimeout(10, TimeUnit.SECONDS)
                        .build(), properties);
    }

    protected AoneOpenApiClient(AoneIntegrationProperties properties) {
        this(new AoneSignatureSigner(), System::currentTimeMillis, new AoneRateLimiter(),
                new OkHttpClient(), properties);
    }

    AoneOpenApiClient(AoneSignatureSigner signer, ClockMillis clock,
                      AoneThrottle rateLimiter, OkHttpClient httpClient,
                      AoneIntegrationProperties properties) {
        this.signer = signer;
        this.clock = clock;
        this.rateLimiter = rateLimiter;
        this.httpClient = httpClient;
        this.properties = properties;
    }

    public JSONObject postForm(AoneOpenApiConfig config, String path, Map<String, ?> form) {
        properties.requireEnabled();
        long timestamp = clock.now();
        String signature = signer.sign(config.clientKey(), config.accessSecret(), timestamp);
        String url = config.baseUrl() + path;
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(AoneQueryString.toQuery(form), FORM))
                .addHeader("clientKey", config.clientKey())
                .addHeader("timestamp", String.valueOf(timestamp))
                .addHeader("signature", signature)
                .addHeader("Ao-Region-Id", config.regionId() == null ? "1" : config.regionId())
                .build();
        return execute(request);
    }

    public JSONObject get(AoneOpenApiConfig config, String path, Map<String, ?> query) {
        properties.requireEnabled();
        long timestamp = clock.now();
        String signature = signer.sign(config.clientKey(), config.accessSecret(), timestamp);
        String qs = AoneQueryString.toUrlEncodedQuery(query);
        String url = config.baseUrl() + path + (qs.isEmpty() ? "" : "?" + qs);
        Request request = new Request.Builder()
                .url(url)
                .get()
                .addHeader("clientKey", config.clientKey())
                .addHeader("timestamp", String.valueOf(timestamp))
                .addHeader("signature", signature)
                .addHeader("Ao-Region-Id", config.regionId() == null ? "1" : config.regionId())
                .build();
        return execute(request);
    }

    private JSONObject execute(Request request) {
        rateLimiter.acquire();
        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            String text = body == null ? "" : body.string();
            JSONObject json;
            try {
                json = JSON.parseObject(text);
            } catch (Exception e) {
                throw new AoneOpenApiException("Aone returned non-JSON response: HTTP " + response.code() + " " + text, e);
            }
            if (!response.isSuccessful() || !json.getBooleanValue("success")) {
                String message = json.getString("message");
                String detail = message == null || message.isBlank() ? text : message;
                throw new AoneOpenApiException(detail, isTerminalFailure(response.code(), detail));
            }
            Object result = json.get("result");
            if (result instanceof JSONObject object) {
                copyEnvelope(json, object);
                return object;
            }
            JSONObject wrapped = new JSONObject();
            wrapped.put("result", result);
            copyEnvelope(json, wrapped);
            return wrapped;
        } catch (IOException e) {
            throw new AoneOpenApiException("Aone request failed: " + e.getMessage(), e);
        }
    }

    /**
     * Rate-limit rejections and 5xx are transient (the same call succeeds once the quota
     * frees up or the server recovers), so they stay retryable. Business rejections and
     * other 4xx (e.g. Aone's HTTP-200 {@code success:false} status-transition errors) can
     * never succeed on retry, so they are terminal and get dead-lettered by the dispatcher.
     */
    private boolean isTerminalFailure(int httpStatus, String message) {
        if (message != null) {
            String lower = message.toLowerCase(Locale.ROOT);
            if (lower.contains("rate limit") || lower.contains("over limit")) {
                return false;
            }
        }
        return httpStatus < 500;
    }

    private void copyEnvelope(JSONObject source, JSONObject target) {
        target.put("message", source.get("message"));
        target.put("messages", source.get("messages"));
        target.put("errorMessages", source.get("errorMessages"));
        target.put("totalCount", source.get("totalCount"));
        target.put("pageSize", source.get("pageSize"));
        target.put("toPage", source.get("toPage"));
        target.put("totalPages", source.get("totalPages"));
    }
}
