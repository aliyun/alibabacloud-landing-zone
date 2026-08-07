package com.aliyun.autowonder.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.OSSObject;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AliyunOssObjectStorageTest {

    @Test
    void putUsesServiceClientWithoutCallingPublicClient() {
        OSS serviceClient = mock(OSS.class);
        OSS publicClient = mock(OSS.class);
        AliyunOssObjectStorage storage = new AliyunOssObjectStorage(serviceClient, publicClient);

        storage.put("bucket", "object", new byte[]{1, 2, 3});

        verify(serviceClient).putObject(eq("bucket"), eq("object"), any(InputStream.class));
        verifyNoInteractions(publicClient);
    }

    @Test
    void presignUsesPublicClientWithoutCallingServiceClient() throws MalformedURLException {
        OSS serviceClient = mock(OSS.class);
        OSS publicClient = mock(OSS.class);
        when(publicClient.generatePresignedUrl(any()))
                .thenReturn(new URL("https://bucket.oss-cn-shanghai.aliyuncs.com/object?signature=test"));
        AliyunOssObjectStorage storage = new AliyunOssObjectStorage(serviceClient, publicClient);

        String url = storage.presignGet("bucket/object", 600);

        assertEquals("https://bucket.oss-cn-shanghai.aliyuncs.com/object?signature=test", url);
        verify(publicClient).generatePresignedUrl(any());
        verifyNoInteractions(serviceClient);
    }

    @Test
    void presignBuildsUrlFromPublicEndpointWithoutRewritingSignedHost() {
        AliyunOssObjectStorage storage = new AliyunOssObjectStorage(
                "https://oss-cn-shanghai-internal.aliyuncs.com",
                "https://oss-cn-shanghai.aliyuncs.com",
                "test-access-key-id", "test-access-key-secret");

        URI url = URI.create(storage.presignGet("bucket/object", 600));

        assertEquals("bucket.oss-cn-shanghai.aliyuncs.com", url.getHost());
    }

    @Test
    void getReturnsBytesOnSuccess() {
        OSS serviceClient = mock(OSS.class);
        OSS publicClient = mock(OSS.class);
        byte[] data = {10, 20, 30};
        OSSObject ossObject = mock(OSSObject.class);
        when(ossObject.getObjectContent()).thenReturn(new ByteArrayInputStream(data));
        when(serviceClient.getObject("bucket", "key")).thenReturn(ossObject);
        AliyunOssObjectStorage storage = new AliyunOssObjectStorage(serviceClient, publicClient);

        byte[] result = storage.get("bucket/key");

        assertArrayEquals(data, result);
    }

    @Test
    void getReturnsNullForNoSuchKey() {
        OSS serviceClient = mock(OSS.class);
        OSS publicClient = mock(OSS.class);
        when(serviceClient.getObject("bucket", "key"))
                .thenThrow(new OSSException("NoSuchKey", "NoSuchKey", null, null, null, null, null));
        AliyunOssObjectStorage storage = new AliyunOssObjectStorage(serviceClient, publicClient);

        assertNull(storage.get("bucket/key"));
    }

    @Test
    void getThrowsObjectStorageExceptionForTransientOssError() {
        OSS serviceClient = mock(OSS.class);
        OSS publicClient = mock(OSS.class);
        when(serviceClient.getObject("bucket", "key"))
                .thenThrow(new OSSException("InternalError", "InternalError", null, null, null, null, null));
        AliyunOssObjectStorage storage = new AliyunOssObjectStorage(serviceClient, publicClient);

        ObjectStorageException ex = assertThrows(ObjectStorageException.class,
                () -> storage.get("bucket/key"));
        assertEquals("InternalError", ex.getErrorCode());
    }

    @Test
    void getThrowsObjectStorageExceptionForNonOssException() {
        OSS serviceClient = mock(OSS.class);
        OSS publicClient = mock(OSS.class);
        when(serviceClient.getObject("bucket", "key"))
                .thenThrow(new RuntimeException("connection reset"));
        AliyunOssObjectStorage storage = new AliyunOssObjectStorage(serviceClient, publicClient);

        ObjectStorageException ex = assertThrows(ObjectStorageException.class,
                () -> storage.get("bucket/key"));
        assertNull(ex.getErrorCode());
    }
}
