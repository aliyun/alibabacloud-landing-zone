package com.aliyun.autowonder.security.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

public class AesGcmSecretCrypto implements SecretCrypto {

    private static final String PREFIX = "enc:v1:";
    private static final int KEY_BYTES = 32;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final int TAG_BYTES = TAG_BITS / Byte.SIZE;

    private final SecretKeySpec key;
    private final SecureRandom secureRandom;

    public AesGcmSecretCrypto(SecretCryptoProperties properties) {
        this(properties == null ? null : properties.getMasterKey(), new SecureRandom());
    }

    AesGcmSecretCrypto(String base64MasterKey) {
        this(base64MasterKey, new SecureRandom());
    }

    AesGcmSecretCrypto(String base64MasterKey, SecureRandom secureRandom) {
        this.key = decodeKey(base64MasterKey);
        this.secureRandom = secureRandom;
    }

    @Override
    public String encrypt(String plaintext) {
        if (plaintext == null) {
            throw new IllegalArgumentException("plaintext secret must not be null");
        }
        byte[] nonce = new byte[NONCE_BYTES];
        secureRandom.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] envelope = ByteBuffer.allocate(nonce.length + encrypted.length)
                    .put(nonce)
                    .put(encrypted)
                    .array();
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(envelope);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("failed to encrypt secret", e);
        }
    }

    @Override
    public String decrypt(String ciphertext) {
        byte[] envelope = decodeEnvelope(ciphertext);
        byte[] nonce = Arrays.copyOfRange(envelope, 0, NONCE_BYTES);
        byte[] encrypted = Arrays.copyOfRange(envelope, NONCE_BYTES, envelope.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encrypted secret authentication failed", e);
        }
    }

    @Override
    public String mask(String value) {
        if (value == null || value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    private static SecretKeySpec decodeKey(String base64MasterKey) {
        if (base64MasterKey == null || base64MasterKey.isBlank()) {
            throw new IllegalStateException("secret crypto master key is required");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64MasterKey);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("secret crypto master key must be valid Base64", e);
        }
        if (decoded.length != KEY_BYTES) {
            Arrays.fill(decoded, (byte) 0);
            throw new IllegalStateException("secret crypto master key must decode to 32 bytes");
        }
        try {
            return new SecretKeySpec(decoded, "AES");
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    private static byte[] decodeEnvelope(String ciphertext) {
        if (ciphertext == null || !ciphertext.startsWith(PREFIX)) {
            throw new IllegalArgumentException("invalid encrypted secret envelope");
        }
        byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(ciphertext.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("invalid encrypted secret envelope", e);
        }
        if (decoded.length < NONCE_BYTES + TAG_BYTES) {
            throw new IllegalArgumentException("invalid encrypted secret envelope");
        }
        return decoded;
    }
}
