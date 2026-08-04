package com.aliyun.autowonder.storage;

import java.util.concurrent.ConcurrentHashMap;

public class InMemoryObjectStorage implements ObjectStorage {

    private final ConcurrentHashMap<String, byte[]> store = new ConcurrentHashMap<>();

    @Override
    public StoredObject put(String bucket, String key, byte[] data) {
        String ossRef = bucket + "/" + key;
        store.put(ossRef, data.clone());
        return new StoredObject(ossRef, StorageRefs.md5Hex(data), data.length);
    }

    @Override
    public byte[] get(String ossRef) {
        byte[] v = store.get(ossRef);
        return v == null ? null : v.clone();
    }

    @Override
    public String presignGet(String ossRef, int ttlSeconds) {
        return "mem://" + ossRef + "?ttl=" + ttlSeconds;
    }

    @Override
    public boolean exists(String ossRef) {
        return store.containsKey(ossRef);
    }

    @Override
    public void delete(String ossRef) {
        store.remove(ossRef);
    }
}
