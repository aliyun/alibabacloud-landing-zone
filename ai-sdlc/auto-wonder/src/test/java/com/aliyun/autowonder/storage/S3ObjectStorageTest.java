package com.aliyun.autowonder.storage;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class S3ObjectStorageTest {

    @Test
    void putUsesServiceClientWithoutCallingPresigner() {
        S3Client serviceClient = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        S3ObjectStorage storage = new S3ObjectStorage(serviceClient, presigner);

        StoredObject stored = storage.put("bucket", "object", new byte[]{1, 2, 3});

        verify(serviceClient).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verifyNoInteractions(presigner);
        assertEquals("bucket/object", stored.getOssRef());
        assertEquals(3, stored.getSize());
        assertEquals(StorageRefs.md5Hex(new byte[]{1, 2, 3}), stored.getMd5());
    }

    @Test
    void getReturnsBytesOnSuccess() {
        S3Client serviceClient = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        byte[] data = {10, 20, 30};
        when(serviceClient.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenReturn(ResponseBytes.fromByteArray(GetObjectResponse.builder().build(), data));
        S3ObjectStorage storage = new S3ObjectStorage(serviceClient, presigner);

        byte[] result = storage.get("bucket/key");

        assertArrayEquals(data, result);
    }

    @Test
    void getReturnsNullForNoSuchKey() {
        S3Client serviceClient = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        when(serviceClient.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build());
        S3ObjectStorage storage = new S3ObjectStorage(serviceClient, presigner);

        assertNull(storage.get("bucket/key"));
    }

    @Test
    void getThrowsObjectStorageExceptionForTransientS3Error() {
        S3Client serviceClient = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        when(serviceClient.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow((S3Exception) S3Exception.builder()
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("InternalError").build())
                        .build());
        S3ObjectStorage storage = new S3ObjectStorage(serviceClient, presigner);

        ObjectStorageException ex = assertThrows(ObjectStorageException.class,
                () -> storage.get("bucket/key"));
        assertEquals("InternalError", ex.getErrorCode());
    }

    @Test
    void getReturnsNullForS3Exception404() {
        S3Client serviceClient = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        when(serviceClient.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow((S3Exception) S3Exception.builder().statusCode(404).build());
        S3ObjectStorage storage = new S3ObjectStorage(serviceClient, presigner);

        assertNull(storage.get("bucket/key"));
    }

    @Test
    void existsReturnsTrueWhenHeadSucceeds() {
        S3Client serviceClient = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        when(serviceClient.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build());
        S3ObjectStorage storage = new S3ObjectStorage(serviceClient, presigner);

        assertTrue(storage.exists("bucket/key"));
    }

    @Test
    void existsReturnsFalseForNoSuchKey() {
        S3Client serviceClient = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        when(serviceClient.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build());
        S3ObjectStorage storage = new S3ObjectStorage(serviceClient, presigner);

        assertFalse(storage.exists("bucket/key"));
    }

    @Test
    void existsReturnsFalseForS3Exception404() {
        S3Client serviceClient = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        when(serviceClient.headObject(any(HeadObjectRequest.class)))
                .thenThrow((S3Exception) S3Exception.builder().statusCode(404).build());
        S3ObjectStorage storage = new S3ObjectStorage(serviceClient, presigner);

        assertFalse(storage.exists("bucket/key"));
    }

    @Test
    void existsThrowsObjectStorageExceptionForOtherS3Error() {
        S3Client serviceClient = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        when(serviceClient.headObject(any(HeadObjectRequest.class)))
                .thenThrow((S3Exception) S3Exception.builder()
                        .statusCode(500)
                        .awsErrorDetails(AwsErrorDetails.builder().errorCode("InternalError").build())
                        .build());
        S3ObjectStorage storage = new S3ObjectStorage(serviceClient, presigner);

        ObjectStorageException ex = assertThrows(ObjectStorageException.class,
                () -> storage.exists("bucket/key"));
        assertEquals("InternalError", ex.getErrorCode());
    }

    @Test
    void deleteInvokesServiceClient() {
        S3Client serviceClient = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        S3ObjectStorage storage = new S3ObjectStorage(serviceClient, presigner);

        storage.delete("bucket/key");

        verify(serviceClient).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void deleteSwallowsExceptions() {
        S3Client serviceClient = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        when(serviceClient.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(new RuntimeException("network down"));
        S3ObjectStorage storage = new S3ObjectStorage(serviceClient, presigner);

        storage.delete("bucket/key");

        verify(serviceClient).deleteObject(any(DeleteObjectRequest.class));
    }

    @Test
    void getThrowsObjectStorageExceptionForNonS3Exception() {
        S3Client serviceClient = mock(S3Client.class);
        S3Presigner presigner = mock(S3Presigner.class);
        when(serviceClient.getObjectAsBytes(any(GetObjectRequest.class)))
                .thenThrow(new RuntimeException("connection reset"));
        S3ObjectStorage storage = new S3ObjectStorage(serviceClient, presigner);

        ObjectStorageException ex = assertThrows(ObjectStorageException.class,
                () -> storage.get("bucket/key"));
        assertNull(ex.getErrorCode());
    }

    @Test
    void presignBuildsPathStyleUrlFromPublicEndpoint() {
        S3ObjectStorage storage = new S3ObjectStorage(
                "http://minio-internal.example.com:9000",
                "http://minio.example.com:9000",
                "us-east-1",
                "test-access-key-id", "test-access-key-secret",
                true);

        URI url = URI.create(storage.presignGet("bucket/object", 600));

        assertEquals("minio.example.com", url.getHost());
        assertEquals(9000, url.getPort());
        assertTrue(url.getPath().contains("/bucket/object"),
                "path-style URL should embed bucket in path, was: " + url.getPath());
        assertTrue(url.getQuery().contains("X-Amz-Signature"),
                "presigned URL should carry a SigV4 signature, was: " + url.getQuery());
    }
}
