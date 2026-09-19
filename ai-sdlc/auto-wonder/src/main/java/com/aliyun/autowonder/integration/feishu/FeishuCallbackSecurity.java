package com.aliyun.autowonder.integration.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

final class FeishuCallbackSecurity {
    private FeishuCallbackSecurity() {}

    static JsonNode verify(ObjectMapper json, String body, FeishuBindingService.Secrets secrets,
                           String timestamp, String nonce, String signature, long nowSeconds) {
        try {
            if (body == null || body.length() > 1_000_000) throw new IllegalArgumentException();
            JsonNode envelope = json.readTree(body);
            if (envelope == null || !envelope.isObject()) throw new IllegalArgumentException();
            String key = secrets.encryptKey();
            boolean encrypted = key != null && !key.isBlank();
            JsonNode event = envelope;
            if (encrypted) {
                byte[] bytes = Base64.getDecoder().decode(envelope.path("encrypt").asText());
                if (bytes.length < 32 || bytes.length % 16 != 0) throw new IllegalArgumentException();
                var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(sha256(key), "AES"), new IvParameterSpec(Arrays.copyOf(bytes, 16)));
                event = json.readTree(cipher.doFinal(Arrays.copyOfRange(bytes, 16, bytes.length)));
            } else if (envelope.has("encrypt")) { throw new IllegalArgumentException(); }
            boolean challenge = "url_verification".equals(event.path("type").asText());
            String token = challenge ? event.path("token").asText() : event.path("header").path("token").asText();
            if (!equal(secrets.verificationToken(), token)) throw new IllegalArgumentException();
            // Feishu URL verification is authenticated by the token; its request has no signature.
            if (encrypted && !challenge) {
                long time = Long.parseLong(timestamp);
                if (time < nowSeconds - 300 || time > nowSeconds + 300 || nonce == null || nonce.isBlank()) throw new IllegalArgumentException();
                String expected = HexFormat.of().formatHex(sha256(timestamp + nonce + key + body));
                if (!equal(expected, signature)) throw new IllegalArgumentException();
            }
            return event;
        } catch (Exception e) { throw new SecurityException("invalid Feishu callback"); }
    }
    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }
    private static boolean equal(String expected, String actual) {
        return expected != null && !expected.isBlank() && actual != null
                && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }
}
