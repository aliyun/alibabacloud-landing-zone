package com.aliyun.autowonder.integration.dingtalk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DingTalkOutboundSender {

    public static final String DEFAULT_BASE_URL = "https://api.dingtalk.com";

    /** 窄接口:POST 返回响应体字符串;真实实现对接阿里钉网关。 */
    public interface HttpExchange {
        String post(String url, String jsonBody, java.util.Map<String, String> headers);
    }

    private static final class TokenEntry {
        String token;
        long expireAtMs;
    }

    private final HttpExchange http;
    private final ConcurrentHashMap<TokenCacheKey, TokenEntry> tokenCache =
            new ConcurrentHashMap<>();

    public DingTalkOutboundSender(HttpExchange http) {
        this.http = http;
    }

    /**
     * 获取(并缓存)access token。TODO(#阿里钉): 对齐真实获取 endpoint 与响应字段。
     */
    public String accessToken(String appKey, String appSecret, String baseUrl, long nowMs) {
        String resolvedBaseUrl = resolveBaseUrl(baseUrl);
        TokenCacheKey cacheKey =
                new TokenCacheKey(appKey, resolvedBaseUrl, fingerprint(appSecret));
        TokenEntry e = tokenCache.get(cacheKey);
        if (e != null && e.expireAtMs > nowMs + 60_000) {
            return e.token;
        }
        com.alibaba.fastjson.JSONObject payload = new com.alibaba.fastjson.JSONObject(true);
        payload.put("appKey", appKey);
        payload.put("appSecret", appSecret);
        String resp = http.post(resolvedBaseUrl + "/v1.0/oauth2/accessToken",
                payload.toJSONString(), java.util.Map.of());
        String token = com.alibaba.fastjson.JSON.parseObject(resp).getString("accessToken");
        long expiresIn = com.alibaba.fastjson.JSON.parseObject(resp).getLongValue("expireIn");
        if (token == null || token.isBlank()) {
            throw new IllegalStateException("DingTalk access token response is incomplete");
        }
        TokenEntry ne = new TokenEntry();
        ne.token = token;
        ne.expireAtMs = nowMs + (expiresIn > 0 ? expiresIn * 1000 : 7200_000);
        tokenCache.put(cacheKey, ne);
        return token;
    }

    /**
     * 机器人主动发送 markdown 到会话。TODO(#阿里钉): 对齐真实 endpoint / 请求体。
     */
    public void sendMarkdown(String appKey, String appSecret, String baseUrl, String robotCode,
            String openConversationId, String markdown, long nowMs) {
        sendGroupMarkdown(appKey, appSecret, baseUrl, robotCode, openConversationId, markdown,
                List.of(), nowMs);
    }

    public void sendSessionWebhookMarkdown(String sessionWebhook, String markdown,
            List<String> atUserIds) {
        com.alibaba.fastjson.JSONObject payload = new com.alibaba.fastjson.JSONObject(true);
        payload.put("msgtype", "markdown");

        com.alibaba.fastjson.JSONObject markdownPayload = new com.alibaba.fastjson.JSONObject(true);
        markdownPayload.put("title", "Auto Wonder");
        markdownPayload.put("text", markdown);
        payload.put("markdown", markdownPayload);

        com.alibaba.fastjson.JSONObject at = new com.alibaba.fastjson.JSONObject(true);
        at.put("isAtAll", false);
        at.put("atUserIds", atUserIds == null ? List.of() : atUserIds);
        payload.put("at", at);

        http.post(sessionWebhook, payload.toJSONString(), Map.of());
    }

    public void sendSessionWebhookText(String sessionWebhook, String text, List<String> atUserIds) {
        com.alibaba.fastjson.JSONObject payload = new com.alibaba.fastjson.JSONObject(true);
        payload.put("msgtype", "text");

        com.alibaba.fastjson.JSONObject textPayload = new com.alibaba.fastjson.JSONObject(true);
        textPayload.put("content", text);
        payload.put("text", textPayload);

        com.alibaba.fastjson.JSONObject at = new com.alibaba.fastjson.JSONObject(true);
        at.put("isAtAll", false);
        at.put("atUserIds", atUserIds == null ? List.of() : atUserIds);
        payload.put("at", at);

        http.post(sessionWebhook, payload.toJSONString(), Map.of());
    }

    public void sendGroupMarkdown(String appKey, String appSecret, String baseUrl, String robotCode,
            String openConversationId, String markdown, List<String> atUserIds, long nowMs) {
        String resolvedBaseUrl = resolveBaseUrl(baseUrl);
        String token = accessToken(appKey, appSecret, baseUrl, nowMs);
        com.alibaba.fastjson.JSONObject payload = new com.alibaba.fastjson.JSONObject(true);
        payload.put("robotCode", robotCode);
        payload.put("openConversationId", openConversationId);
        payload.put("msgKey", "sampleMarkdown");
        com.alibaba.fastjson.JSONObject param = new com.alibaba.fastjson.JSONObject(true);
        param.put("title", "Auto Wonder");
        param.put("text", markdown);
        if (atUserIds != null && !atUserIds.isEmpty()) {
            param.put("atUserIds", atUserIds);
        }
        payload.put("msgParam", param.toJSONString());
        http.post(resolvedBaseUrl + "/v1.0/robot/groupMessages/send", payload.toJSONString(),
                Map.of("x-acs-dingtalk-access-token", token));
    }

    public void sendGroupText(String appKey, String appSecret, String baseUrl, String robotCode,
            String openConversationId, String text, List<String> atUserIds, long nowMs) {
        String resolvedBaseUrl = resolveBaseUrl(baseUrl);
        String token = accessToken(appKey, appSecret, baseUrl, nowMs);
        com.alibaba.fastjson.JSONObject payload = new com.alibaba.fastjson.JSONObject(true);
        payload.put("robotCode", robotCode);
        payload.put("openConversationId", openConversationId);
        payload.put("msgKey", "sampleText");
        com.alibaba.fastjson.JSONObject param = new com.alibaba.fastjson.JSONObject(true);
        param.put("content", text);
        if (atUserIds != null && !atUserIds.isEmpty()) {
            param.put("atUserIds", atUserIds);
        }
        payload.put("msgParam", param.toJSONString());
        http.post(resolvedBaseUrl + "/v1.0/robot/groupMessages/send", payload.toJSONString(),
                Map.of("x-acs-dingtalk-access-token", token));
    }

    public void sendSingleMarkdown(String appKey, String appSecret, String baseUrl, String robotCode,
            List<String> userIds, String markdown, long nowMs) {
        String resolvedBaseUrl = resolveBaseUrl(baseUrl);
        String token = accessToken(appKey, appSecret, baseUrl, nowMs);
        com.alibaba.fastjson.JSONObject payload = new com.alibaba.fastjson.JSONObject(true);
        payload.put("robotCode", robotCode);
        payload.put("userIds", userIds == null ? List.of() : userIds);
        payload.put("msgKey", "sampleMarkdown");
        com.alibaba.fastjson.JSONObject param = new com.alibaba.fastjson.JSONObject(true);
        param.put("title", "Auto Wonder");
        param.put("text", markdown);
        payload.put("msgParam", param.toJSONString());
        http.post(resolvedBaseUrl + "/v1.0/robot/oToMessages/batchSend", payload.toJSONString(),
                Map.of("x-acs-dingtalk-access-token", token));
    }

    public void replyThinkingEmotion(String appKey, String appSecret, String baseUrl,
            String robotCode, String openConversationId, String openMsgId, long nowMs) {
        sendThinkingEmotion(appKey, appSecret, baseUrl, robotCode, openConversationId, openMsgId,
                "/v1.0/robot/emotion/reply", nowMs);
    }

    public void recallThinkingEmotion(String appKey, String appSecret, String baseUrl,
            String robotCode, String openConversationId, String openMsgId, long nowMs) {
        sendThinkingEmotion(appKey, appSecret, baseUrl, robotCode, openConversationId, openMsgId,
                "/v1.0/robot/emotion/recall", nowMs);
    }

    private void sendThinkingEmotion(String appKey, String appSecret, String baseUrl,
            String robotCode, String openConversationId, String openMsgId, String path, long nowMs) {
        String resolvedBaseUrl = resolveBaseUrl(baseUrl);
        String token = accessToken(appKey, appSecret, baseUrl, nowMs);
        com.alibaba.fastjson.JSONObject payload = new com.alibaba.fastjson.JSONObject(true);
        payload.put("robotCode", robotCode);
        payload.put("openConversationId", openConversationId);
        payload.put("openMsgId", openMsgId);
        payload.put("emotionType", 2);
        payload.put("emotionName", "🤔思考中");
        com.alibaba.fastjson.JSONObject textEmotion = new com.alibaba.fastjson.JSONObject(true);
        textEmotion.put("emotionId", "2659900");
        textEmotion.put("emotionName", "🤔思考中");
        textEmotion.put("text", "🤔思考中");
        textEmotion.put("backgroundId", "im_bg_1");
        payload.put("textEmotion", textEmotion);
        http.post(resolvedBaseUrl + path, payload.toJSONString(),
                java.util.Map.of("x-acs-dingtalk-access-token", token));
    }

    private String resolveBaseUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return DEFAULT_BASE_URL;
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static String fingerprint(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((secret == null ? "" : secret).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record TokenCacheKey(String appKey, String baseUrl, String credentialFingerprint) {
    }
}
