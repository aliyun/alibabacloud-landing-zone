package com.aliyun.autowonder.storage;

import java.util.Set;

public class ObjectStorageException extends RuntimeException {

    private static final Set<String> PERMANENT_CONFIGURATION_ERROR_CODES = Set.of(
            "NoSuchBucket",
            "InvalidBucketName",
            "AccessDenied",
            "InvalidAccessKeyId",
            "SignatureDoesNotMatch");

    private final String bucket;
    private final String key;
    private final String errorCode;

    public ObjectStorageException(String message, String bucket, String key, String errorCode, Throwable cause) {
        super(message, cause);
        this.bucket = bucket;
        this.key = key;
        this.errorCode = errorCode;
    }

    public String getBucket() {
        return bucket;
    }

    public String getKey() {
        return key;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public boolean isPermanentConfigurationError() {
        return errorCode != null && PERMANENT_CONFIGURATION_ERROR_CODES.contains(errorCode);
    }

    public String describe() {
        StringBuilder sb = new StringBuilder();
        if (errorCode != null && !errorCode.isBlank()) {
            sb.append(errorCode);
        } else {
            sb.append("ObjectStorageError");
        }
        if (bucket != null && !bucket.isBlank()) {
            sb.append(" bucket=").append(bucket);
        }
        if (key != null && !key.isBlank()) {
            sb.append(" key=").append(key);
        }
        return sb.toString();
    }
}
