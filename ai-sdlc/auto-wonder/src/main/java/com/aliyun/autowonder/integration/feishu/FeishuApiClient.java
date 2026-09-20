package com.aliyun.autowonder.integration.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class FeishuApiClient {
    private record Access(String credentialRef, String token, long expiresAt, String botId) {}
    private final ConcurrentHashMap<Long, Access> tokens = new ConcurrentHashMap<>();
    private final FeishuBindingService bindings;
    private final ObjectMapper json;
    private final OkHttpClient http;
    private final String base;

    @Autowired
    public FeishuApiClient(FeishuBindingService bindings, ObjectMapper json) {
        this(bindings, json, new OkHttpClient.Builder().callTimeout(Duration.ofSeconds(10))
                .followRedirects(false).followSslRedirects(false).build(), "https://open.feishu.cn/open-apis");
    }
    FeishuApiClient(FeishuBindingService bindings, ObjectMapper json, OkHttpClient http, String base) {
        this.bindings = bindings; this.json = json; this.http = http; this.base = base;
    }
    public String botOpenId(FeishuBinding binding) { return access(binding).botId(); }

    public void reply(FeishuBinding binding, String messageId, String text, String sourceId) {
        if (messageId == null || !messageId.matches("om_[A-Za-z0-9]+")) throw new IllegalArgumentException("invalid Feishu message id");
        // Stay comfortably below the text-message byte limit, without cutting surrogate pairs.
        String remaining = text == null || text.isBlank() ? "（数字人未返回文本）" : text;
        int part = 0;
        while (!remaining.isEmpty()) {
            int end = remaining.offsetByCodePoints(0, Math.min(2000, remaining.codePointCount(0, remaining.length())));
            String chunk = remaining.substring(0, end);
            remaining = remaining.substring(end);
            String uuid = UUID.nameUUIDFromBytes((binding.getId() + ":" + sourceId + ":" + part++ + ":" + chunk).getBytes(StandardCharsets.UTF_8)).toString();
            try {
                exchange("/im/v1/messages/" + messageId + "/reply", access(binding).token(),
                        Map.of("msg_type", "text", "content", json.writeValueAsString(Map.of("text", chunk)), "uuid", uuid));
            } catch (Exception e) {
                tokens.remove(binding.getId());
                if (e instanceof IllegalStateException safe) throw safe;
                throw new IllegalStateException("飞书回复失败，请检查网络和应用权限");
            }
        }
    }

    private synchronized Access access(FeishuBinding binding) {
        long now = System.currentTimeMillis();
        tokens.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
        Access existing = tokens.get(binding.getId());
        if (existing != null && existing.credentialRef().equals(binding.getCredentialRef())) return existing;
        JsonNode response = exchange("/auth/v3/tenant_access_token/internal", null,
                Map.of("app_id", binding.getAppId(), "app_secret", bindings.secrets(binding).appSecret()));
        String token = response.path("tenant_access_token").asText();
        long expire = response.path("expire").asLong();
        if (token.isBlank() || expire <= 60) throw new IllegalStateException("飞书返回无效访问凭证");
        JsonNode bot = exchange("/bot/v3/info", token, null);
        String botId = bot.path("bot").path("open_id").asText();
        if (botId.isBlank()) throw new IllegalStateException("飞书机器人未启用或无法读取机器人信息");
        Access access = new Access(binding.getCredentialRef(), token, now + (expire - 60) * 1000, botId);
        tokens.put(binding.getId(), access);
        return access;
    }

    private JsonNode exchange(String path, String token, Object body) {
        try {
            var request = new Request.Builder().url(base + path);
            if (token != null) request.header("Authorization", "Bearer " + token);
            if (body != null) request.post(RequestBody.create(json.writeValueAsString(body), MediaType.get("application/json; charset=utf-8")));
            try (Response response = http.newCall(request.build()).execute()) {
                if (!response.isSuccessful() || response.body() == null) throw new IllegalStateException("飞书接口 HTTP " + response.code());
                JsonNode result = json.readTree(response.body().string());
                if (result == null || !result.path("code").isIntegralNumber()) throw new IllegalStateException("飞书接口响应无效");
                int code = result.path("code").asInt();
                // Do not expose raw provider messages, response bodies, or credentials in logs/health.
                if (code != 0) throw new IllegalStateException("飞书接口错误码 " + code);
                return result;
            }
        } catch (IOException e) { throw new IllegalStateException("飞书接口请求失败，请检查网络"); }
    }
}
