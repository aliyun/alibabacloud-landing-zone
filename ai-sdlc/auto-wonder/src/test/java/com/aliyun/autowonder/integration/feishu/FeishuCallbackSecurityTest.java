package com.aliyun.autowonder.integration.feishu;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class FeishuCallbackSecurityTest {
    private final ObjectMapper json = new ObjectMapper();
    private final String event = "{\"header\":{\"token\":\"verify\"}}";
    private final FeishuBindingService.Secrets plain = new FeishuBindingService.Secrets("secret", "verify", null);
    private final FeishuBindingService.Secrets encrypted = new FeishuBindingService.Secrets("secret", "verify", "encrypt-key");
    @Test void plainCallbacksRequireTokenAndRejectUnexpectedEncryption() {
        assertEquals("verify", FeishuCallbackSecurity.verify(json, event, plain, null, null, null, 1000).path("header").path("token").asText());
        for (String body : new String[]{"{}", "null", "garbage", "{\"encrypt\":\"bad\"}", event.replace("verify", "wrong")}) {
            assertThrows(SecurityException.class, () -> FeishuCallbackSecurity.verify(json, body, plain, null, null, null, 1000));
        }
    }
    @Test void decryptsSignedEventsAndRejectsTamperingStaleSignaturesAndPlainDowngrade() throws Exception {
        String body = encrypt(event);
        String signature = sign(body);
        assertEquals("verify", FeishuCallbackSecurity.verify(json, body, encrypted, "1000", "nonce", signature, 1000).path("header").path("token").asText());
        assertThrows(SecurityException.class, () -> FeishuCallbackSecurity.verify(json, body + " ", encrypted, "1000", "nonce", signature, 1000));
        assertThrows(SecurityException.class, () -> FeishuCallbackSecurity.verify(json, body, encrypted, "1000", "nonce", signature, 1400));
        assertThrows(SecurityException.class, () -> FeishuCallbackSecurity.verify(json, body, encrypted, null, null, null, 1000));
        assertThrows(SecurityException.class, () -> FeishuCallbackSecurity.verify(json, event, encrypted, "1000", "nonce", signature, 1000));
    }
    @Test void encryptedUrlVerificationUsesTokenWithoutSignature() throws Exception {
        String body = encrypt("{\"type\":\"url_verification\",\"token\":\"verify\",\"challenge\":\"test\"}");
        assertEquals("test", FeishuCallbackSecurity.verify(json, body, encrypted, null, null, null, 1000).path("challenge").asText());
    }
    private String sign(String body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(("1000nonceencrypt-key" + body).getBytes(StandardCharsets.UTF_8)));
    }
    private String encrypt(String body) throws Exception {
        byte[] iv = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("encrypt-key".getBytes(StandardCharsets.UTF_8)), "AES"), new IvParameterSpec(iv));
        byte[] data = cipher.doFinal(body.getBytes(StandardCharsets.UTF_8));
        byte[] payload = new byte[16 + data.length]; System.arraycopy(iv, 0, payload, 0, 16); System.arraycopy(data, 0, payload, 16, data.length);
        return json.writeValueAsString(Map.of("encrypt", Base64.getEncoder().encodeToString(payload)));
    }
}
