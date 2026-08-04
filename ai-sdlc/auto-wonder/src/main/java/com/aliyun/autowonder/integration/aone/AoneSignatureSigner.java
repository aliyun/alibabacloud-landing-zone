package com.aliyun.autowonder.integration.aone;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public class AoneSignatureSigner {

    public String sign(String appName, String appSecret, long timestamp) {
        try {
            String content = "appName=" + appName + ";timestamp=" + timestamp;
            byte[] key = Base64.getDecoder().decode(appSecret);
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
            byte[] encrypted = cipher.doFinal(content.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(encrypted)
                    .replace('+', '-')
                    .replace('/', '_')
                    .replaceAll("=+$", "");
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to create Aone signature", e);
        }
    }
}
