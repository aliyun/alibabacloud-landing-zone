package com.aliyun.autowonder.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.net.URI;
import java.time.Duration;

/**
 * ObjectStorage backed by any S3-compatible service (AWS S3, MinIO, etc.).
 *
 * <p>Mirrors {@link AliyunOssObjectStorage}'s dual-client split: the service client talks to the
 * internal {@code endpoint} for put/get/head/delete, while the presigner signs against the
 * externally reachable {@code publicEndpoint} so download URLs resolve for clients outside the VPC.
 *
 * <p>Checksums are set to WHEN_REQUIRED so we stay compatible with older S3-compatible stores
 * (e.g. MinIO releases predating AWS SDK v2's 2.30.0 default flip from MD5 to CRC32).
 */
public class S3ObjectStorage implements ObjectStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(S3ObjectStorage.class);

    private final S3Client serviceClient;
    private final S3Presigner presigner;

    public S3ObjectStorage(String endpoint, String publicEndpoint, String region,
                           String accessKeyId, String accessKeySecret, boolean forcePathStyle) {
        StaticCredentialsProvider credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKeyId, accessKeySecret));
        Region awsRegion = Region.of(region);
        S3Configuration s3Config = S3Configuration.builder()
                .pathStyleAccessEnabled(forcePathStyle)
                .build();
        this.serviceClient = S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(awsRegion)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .httpClient(UrlConnectionHttpClient.create())
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED)
                .build();
        this.presigner = S3Presigner.builder()
                .endpointOverride(URI.create(publicEndpoint))
                .region(awsRegion)
                .credentialsProvider(credentials)
                .serviceConfiguration(s3Config)
                .build();
    }

    S3ObjectStorage(S3Client serviceClient, S3Presigner presigner) {
        this.serviceClient = serviceClient;
        this.presigner = presigner;
    }

    @Override
    public StoredObject put(String bucket, String key, byte[] data) {
        try {
            serviceClient.putObject(
                    PutObjectRequest.builder().bucket(bucket).key(key).build(),
                    RequestBody.fromBytes(data));
            return new StoredObject(bucket + "/" + key, StorageRefs.md5Hex(data), data.length);
        } catch (S3Exception e) {
            LOGGER.error("s3 put failed bucket={} key={} errorCode={}", bucket, key, errorCode(e), e);
            throw new ObjectStorageException("s3 put failed", bucket, key, errorCode(e), e);
        } catch (Exception e) {
            LOGGER.error("s3 put failed bucket={} key={}", bucket, key, e);
            throw new ObjectStorageException("s3 put failed", bucket, key, null, e);
        }
    }

    @Override
    public byte[] get(String ossRef) {
        String[] bk = StorageRefs.split(ossRef);
        try {
            ResponseBytes<GetObjectResponse> obj = serviceClient.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bk[0]).key(bk[1]).build());
            return obj.asByteArray();
        } catch (NoSuchKeyException e) {
            LOGGER.info("s3 object not found ref={}", ossRef);
            return null;
        } catch (S3Exception e) {
            String code = errorCode(e);
            if (e.statusCode() == 404) {
                LOGGER.info("s3 object not found ref={}", ossRef);
                return null;
            }
            LOGGER.error("s3 get failed ref={} errorCode={}", ossRef, code, e);
            throw new ObjectStorageException("s3 get failed", bk[0], bk[1], code, e);
        } catch (Exception e) {
            LOGGER.error("s3 get failed ref={} errorType={}", ossRef, e.getClass().getSimpleName(), e);
            throw new ObjectStorageException("s3 get failed", bk[0], bk[1], null, e);
        }
    }

    @Override
    public String presignGet(String ossRef, int ttlSeconds) {
        String[] bk = StorageRefs.split(ossRef);
        PresignedGetObjectRequest presigned = presigner.presignGetObject(
                GetObjectPresignRequest.builder()
                        .signatureDuration(Duration.ofSeconds(ttlSeconds))
                        .getObjectRequest(GetObjectRequest.builder().bucket(bk[0]).key(bk[1]).build())
                        .build());
        return presigned.url().toString();
    }

    @Override
    public boolean exists(String ossRef) {
        String[] bk = StorageRefs.split(ossRef);
        try {
            serviceClient.headObject(HeadObjectRequest.builder().bucket(bk[0]).key(bk[1]).build());
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                return false;
            }
            throw new ObjectStorageException("s3 head failed", bk[0], bk[1], errorCode(e), e);
        }
    }

    @Override
    public void delete(String ossRef) {
        String[] bk = StorageRefs.split(ossRef);
        try {
            serviceClient.deleteObject(DeleteObjectRequest.builder().bucket(bk[0]).key(bk[1]).build());
        } catch (Exception e) {
            LOGGER.warn("s3 delete failed ref={}", ossRef, e);
        }
    }

    private static String errorCode(S3Exception e) {
        return e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : null;
    }
}
