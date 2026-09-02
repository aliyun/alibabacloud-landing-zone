package com.aliyun.autowonder.taskpackage;

import lombok.Getter;
import lombok.Setter;
import java.util.Map;

@Getter
public class TaskPackageResult {
    private final String ossRef;
    private final String md5;
    private final long size;
    private final String downloadUrl;
    private final String sha256;
    private final String contentHash;
    private final String issuer;
    private final String signatureRef;
    private final String signature;
    private final String signatureAlgorithm;
    private final String signaturePublicKey;
    private final String expiresAt;
    private final boolean allowCommit;
    private final boolean allowPush;
    private final boolean allowNetwork;
    private final boolean requiresHookProtocol;
    private final boolean requiresToolHookProtocol;
    /** Opaque secret references required by this task; cleartext is delivered separately. */
    @Setter
    private Map<String, String> mcpSecretRefs = Map.of();

    public TaskPackageResult(String ossRef, String md5, long size, String downloadUrl, String sha256) {
        this(ossRef, md5, size, downloadUrl, sha256, sha256);
    }

    public TaskPackageResult(String ossRef, String md5, long size, String downloadUrl, String sha256,
            String contentHash) {
        this(ossRef, md5, size, downloadUrl, sha256, contentHash,
                null, null, null, null, null, null);
    }

    public TaskPackageResult(String ossRef, String md5, long size, String downloadUrl, String sha256,
            String contentHash, String issuer, String signatureRef, String signature,
            String signatureAlgorithm, String signaturePublicKey, String expiresAt) {
        this(ossRef, md5, size, downloadUrl, sha256, contentHash, issuer, signatureRef, signature,
                signatureAlgorithm, signaturePublicKey, expiresAt, false, false, false, false);
    }

    public TaskPackageResult(String ossRef, String md5, long size, String downloadUrl, String sha256,
            String contentHash, String issuer, String signatureRef, String signature,
            String signatureAlgorithm, String signaturePublicKey, String expiresAt,
            boolean allowCommit, boolean allowPush, boolean allowNetwork) {
        this(ossRef, md5, size, downloadUrl, sha256, contentHash, issuer, signatureRef, signature,
                signatureAlgorithm, signaturePublicKey, expiresAt,
                allowCommit, allowPush, allowNetwork, false, false);
    }

    public TaskPackageResult(String ossRef, String md5, long size, String downloadUrl, String sha256,
            String contentHash, String issuer, String signatureRef, String signature,
            String signatureAlgorithm, String signaturePublicKey, String expiresAt,
            boolean allowCommit, boolean allowPush, boolean allowNetwork, boolean requiresHookProtocol) {
        this(ossRef, md5, size, downloadUrl, sha256, contentHash, issuer, signatureRef, signature,
                signatureAlgorithm, signaturePublicKey, expiresAt,
                allowCommit, allowPush, allowNetwork, requiresHookProtocol, false);
    }

    public TaskPackageResult(String ossRef, String md5, long size, String downloadUrl, String sha256,
            String contentHash, String issuer, String signatureRef, String signature,
            String signatureAlgorithm, String signaturePublicKey, String expiresAt,
            boolean allowCommit, boolean allowPush, boolean allowNetwork, boolean requiresHookProtocol,
            boolean requiresToolHookProtocol) {
        this.ossRef = ossRef;
        this.md5 = md5;
        this.size = size;
        this.downloadUrl = downloadUrl;
        this.sha256 = sha256;
        this.contentHash = contentHash;
        this.issuer = issuer;
        this.signatureRef = signatureRef;
        this.signature = signature;
        this.signatureAlgorithm = signatureAlgorithm;
        this.signaturePublicKey = signaturePublicKey;
        this.expiresAt = expiresAt;
        this.allowCommit = allowCommit;
        this.allowPush = allowPush;
        this.allowNetwork = allowNetwork;
        this.requiresHookProtocol = requiresHookProtocol;
        this.requiresToolHookProtocol = requiresToolHookProtocol;
    }
}
