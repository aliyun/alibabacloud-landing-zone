package com.aliyun.autowonder.integration.dingtalk;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class DingTalkSignature {

    private DingTalkSignature() {}

    /** HmacSHA256(appSecret, timestamp) -> Base64。TODO(#阿里钉): 对齐真实 base string。 */
    public static String sign(String appSecret, String timestamp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(appSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(timestamp.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new IllegalStateException("sign failed", e);
        }
    }

    public static boolean verify(String appSecret, String timestamp, String provided,
            long nowMs, long windowMs) {
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(nowMs - ts) > windowMs) {
            return false;
        }
        String expected = sign(appSecret, timestamp);
        return constantTimeEquals(expected, provided);
    }

    public static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int r = 0;
        for (int i = 0; i < a.length(); i++) {
            r |= a.charAt(i) ^ b.charAt(i);
        }
        return r == 0;
    }
}
