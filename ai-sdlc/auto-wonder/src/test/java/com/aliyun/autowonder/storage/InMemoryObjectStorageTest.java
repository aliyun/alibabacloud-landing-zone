package com.aliyun.autowonder.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryObjectStorageTest {

    @Test
    void put_then_get_roundtrips_and_computes_md5_size() {
        InMemoryObjectStorage s = new InMemoryObjectStorage();
        byte[] data = "hello".getBytes(StandardCharsets.UTF_8);
        StoredObject obj = s.put("bkt", "a/b.zip", data);

        assertEquals("bkt/a/b.zip", obj.getOssRef());
        assertEquals(5L, obj.getSize());
        assertEquals("5d41402abc4b2a76b9719d911017c592", obj.getMd5());
        assertArrayEquals(data, s.get(obj.getOssRef()));
    }

    @Test
    void presign_returns_nonblank_url_containing_ref() {
        InMemoryObjectStorage s = new InMemoryObjectStorage();
        StoredObject obj = s.put("bkt", "k", new byte[]{1, 2, 3});
        String url = s.presignGet(obj.getOssRef(), 600);
        assertTrue(url.contains("bkt/k"));
    }

    @Test
    void get_missing_returns_null_and_delete_is_idempotent() {
        InMemoryObjectStorage s = new InMemoryObjectStorage();
        assertNull(s.get("nope/x"));
        s.delete("nope/x"); // no throw
        StoredObject obj = s.put("b", "k", new byte[]{9});
        s.delete(obj.getOssRef());
        assertNull(s.get(obj.getOssRef()));
    }
}
