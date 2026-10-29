package com.aliyun.autowonder.integration.feishu;

import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

@Component
public class FeishuApiClient {
    private record Access(String credentialKey, String token, long expiresAt, String botId) {}
    private record Owner(String cacheEntry, String appId, String credentialKey,
                         Supplier<FeishuBindingService.Secrets> secrets) {}
    private final ConcurrentHashMap<String, Access> tokens = new ConcurrentHashMap<>();
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
    public String botOpenId(FeishuBinding binding) { return access(bindingOwner(binding)).botId(); }

    /** Bot identity of the platform GROUP_CHAT application, resolved only for shared group callbacks. */
    public String botOpenIdForConfig(PlatformImChannelConfigDO config) {
        return access(configOwner(config)).botId();
    }

    public void reply(FeishuBinding binding, String messageId, String text, String sourceId) {
        reply(bindingOwner(binding), String.valueOf(binding.getId()), messageId, text, sourceId);
    }

    public void replyForConfig(PlatformImChannelConfigDO config, String messageId, String text, String sourceId) {
        reply(configOwner(config), "config:" + config.getId(), messageId, text, sourceId);
    }

    private void reply(Owner owner, String uuidSeed, String messageId, String text, String sourceId) {
        if (messageId == null || !messageId.matches("om_[A-Za-z0-9]+")) throw new IllegalArgumentException("invalid Feishu message id");
        // Stay comfortably below the text-message byte limit, without cutting surrogate pairs.
        String remaining = text == null || text.isBlank() ? "（数字人未返回文本）" : text;
        int part = 0;
        while (!remaining.isEmpty()) {
            int end = remaining.offsetByCodePoints(0, Math.min(2000, remaining.codePointCount(0, remaining.length())));
            String chunk = remaining.substring(0, end);
            remaining = remaining.substring(end);
            String uuid = UUID.nameUUIDFromBytes((uuidSeed + ":" + sourceId + ":" + part++ + ":" + chunk).getBytes(StandardCharsets.UTF_8)).toString();
            try {
                exchange("/im/v1/messages/" + messageId + "/reply", access(owner).token(),
                        Map.of("msg_type", "text", "content", json.writeValueAsString(Map.of("text", chunk)), "uuid", uuid));
            } catch (Exception e) {
                tokens.remove(owner.cacheEntry());
                if (e instanceof IllegalStateException safe) throw safe;
                throw new IllegalStateException("飞书回复失败，请检查网络和应用权限");
            }
        }
    }

    private Owner bindingOwner(FeishuBinding binding) {
        return new Owner("binding:" + binding.getId(), binding.getAppId(),
                bindings.credentialCacheKey(binding), () -> bindings.secrets(binding));
    }

    private Owner configOwner(PlatformImChannelConfigDO config) {
        return new Owner("config:" + config.getId(), config.getAppKey(),
                bindings.credentialCacheKey(config), () -> bindings.secrets(config));
    }

    private synchronized Access access(Owner owner) {
        long now = System.currentTimeMillis();
        tokens.entrySet().removeIf(e -> e.getValue().expiresAt() <= now);
        Access existing = tokens.get(owner.cacheEntry());
        if (existing != null && Objects.equals(existing.credentialKey(), owner.credentialKey())) return existing;
        JsonNode response = exchange("/auth/v3/tenant_access_token/internal", null,
                Map.of("app_id", owner.appId(), "app_secret", owner.secrets().get().appSecret()));
        String token = response.path("tenant_access_token").asText();
        long expire = response.path("expire").asLong();
        if (token.isBlank() || expire <= 60) throw new IllegalStateException("飞书返回无效访问凭证");
        JsonNode bot = exchange("/bot/v3/info", token, null);
        String botId = bot.path("bot").path("open_id").asText();
        if (botId.isBlank()) throw new IllegalStateException("飞书机器人未启用或无法读取机器人信息");
        Access access = new Access(owner.credentialKey(), token, now + (expire - 60) * 1000, botId);
        tokens.put(owner.cacheEntry(), access);
        return access;
    }

    private JsonNode exchange(String path, String token, Object body) {
        try {
            var request = new Request.Builder().url(base + path);
            if (token != null) request.header("Authorization", "Bearer " + token);
            if (body != null) request.post(RequestBody.create(json.writeValueAsString(body), MediaType.get("application/json; charset=utf-8")));
            try (Response response = http.newCall(request.build()).execute()) {
                int status = response.code();
                if (!response.isSuccessful() || response.body() == null) {
                    throw new FeishuApiException("飞书接口 HTTP " + status, status, null, false);
                }
                JsonNode result = json.readTree(response.body().string());
                if (result == null || !result.path("code").isIntegralNumber()) {
                    throw new FeishuApiException("飞书接口响应无效", status, null, false);
                }
                int code = result.path("code").asInt();
                // Do not expose raw provider messages, response bodies, or credentials in logs/health.
                if (code != 0) {
                    throw new FeishuApiException("飞书接口错误码 " + code, status, code, false);
                }
                return result;
            }
        } catch (IOException e) {
            throw new FeishuApiException("飞书接口请求失败，请检查网络", null, null, true);
        }
    }
}
