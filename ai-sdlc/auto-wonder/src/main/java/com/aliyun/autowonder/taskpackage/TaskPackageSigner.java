package com.aliyun.autowonder.taskpackage;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;

/** Process-scoped Ed25519 signer for task-package dispatch envelopes. */
public final class TaskPackageSigner {
    public static final String ISSUER = "autowonder-server";
    public static final String ALGORITHM = "ed25519";

    private final PrivateKey privateKey;
    private final PublicKey publicKey;
    private final String signatureRef;

    public TaskPackageSigner() {
        this(generate());
    }

    TaskPackageSigner(KeyPair keyPair) {
        this(keyPair.getPrivate(), keyPair.getPublic());
    }

    TaskPackageSigner(PrivateKey privateKey, PublicKey publicKey) {
        this.privateKey = privateKey;
        this.publicKey = publicKey;
        this.signatureRef = "sha256:" + hex(sha256(publicKey.getEncoded()));
    }

    public Envelope envelope(Instant expiresAt) {
        return new Envelope(ISSUER, signatureRef, expiresAt.toString());
    }

    public String sign(Envelope envelope, String packageId, String archiveSha256) {
        try {
            Signature signature = Signature.getInstance("Ed25519");
            signature.initSign(privateKey);
            signature.update(payload(envelope, packageId, archiveSha256));
            return Base64.getEncoder().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new BizException(ErrorCode.PACKAGE_BUILD_FAILED, e);
        }
    }

    public String publicKeyBase64() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    static byte[] payload(Envelope envelope, String packageId, String archiveSha256) {
        return ("autowonder.taskPackage.signature.v1\n"
                + envelope.issuer() + "\n"
                + envelope.signatureRef() + "\n"
                + envelope.expiresAt() + "\n"
                + packageId + "\n"
                + normalizeDigest(archiveSha256) + "\n").getBytes(StandardCharsets.UTF_8);
    }

    private static String normalizeDigest(String digest) {
        String value = digest == null ? "" : digest.trim().toLowerCase();
        return value.startsWith("sha256:") ? value : "sha256:" + value;
    }

    private static KeyPair generate() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new BizException(ErrorCode.PACKAGE_BUILD_FAILED, e);
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (Exception e) {
            throw new BizException(ErrorCode.PACKAGE_BUILD_FAILED, e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format("%02x", value));
        }
        return out.toString();
    }

    public record Envelope(String issuer, String signatureRef, String expiresAt) {
    }
}
