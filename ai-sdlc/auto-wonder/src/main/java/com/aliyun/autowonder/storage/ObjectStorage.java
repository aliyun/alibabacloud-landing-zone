package com.aliyun.autowonder.storage;

/**
 * Abstraction over object storage (OSS). ossRef is the storage-internal reference
 * "{bucket}/{key}" and is what callers persist (e.g. dispatch.package_oss_ref, artifact.oss_ref).
 */
public interface ObjectStorage {

    /** Store bytes at bucket/key. Returns the ossRef plus computed md5 (hex) and size. */
    StoredObject put(String bucket, String key, byte[] data);

    /** Fetch bytes by ossRef, or null if absent. */
    byte[] get(String ossRef);

    /** Short-lived, externally reachable presigned download URL for the ossRef. */
    String presignGet(String ossRef, int ttlSeconds);

    /** Return whether the referenced object currently exists. */
    boolean exists(String ossRef);

    /** Delete by ossRef; no-op if absent. */
    void delete(String ossRef);
}
