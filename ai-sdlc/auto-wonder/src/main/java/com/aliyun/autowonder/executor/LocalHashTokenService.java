package com.aliyun.autowonder.executor;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
public class LocalHashTokenService implements TokenService {

    private static final String SALT = "autowonder-executor";
    private static final String PREFIX_B64 = "b64:";
    private static final String PREFIX_SHA = "sha256:";
    private final SecureRandom random = new SecureRandom();

    @Override
    public IssuedToken issue(long executorId) {
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String plaintext = "exec_" + executorId + "_" + Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        String tokenRef = PREFIX_B64 + Base64.getEncoder().encodeToString(plaintext.getBytes(StandardCharsets.UTF_8));
        return new IssuedToken(plaintext, tokenRef);
    }

    @Override
    public boolean validate(String tokenRef, String plaintext) {
        if (tokenRef == null || plaintext == null) {
            return false;
        }
        if (tokenRef.startsWith(PREFIX_B64)) {
            String stored = resolve(tokenRef);
            return stored != null && MessageDigest.isEqual(
                    stored.getBytes(StandardCharsets.UTF_8),
                    plaintext.getBytes(StandardCharsets.UTF_8));
        }
        String expected = PREFIX_SHA + sha256Hex(plaintext);
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                tokenRef.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public String resolve(String tokenRef) {
        if (tokenRef == null || !tokenRef.startsWith(PREFIX_B64)) {
            return null;
        }
        return new String(Base64.getDecoder().decode(tokenRef.substring(PREFIX_B64.length())), StandardCharsets.UTF_8);
    }

    private static String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest((SALT + s).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
