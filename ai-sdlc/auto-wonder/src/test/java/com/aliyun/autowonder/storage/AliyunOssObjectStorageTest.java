package com.aliyun.autowonder.storage;

import com.aliyun.oss.OSS;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
