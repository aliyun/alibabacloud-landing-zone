package com.aliyun.autowonder.storage;

import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import com.aliyun.oss.OSSException;
import com.aliyun.oss.model.GeneratePresignedUrlRequest;
import com.aliyun.oss.model.OSSObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.URL;
import java.util.Date;

public class AliyunOssObjectStorage implements ObjectStorage {

    private static final Logger LOGGER = LoggerFactory.getLogger(AliyunOssObjectStorage.class);

    private final OSS client;

    public AliyunOssObjectStorage(String endpoint, String accessKeyId, String accessKeySecret) {
        this.client = new OSSClientBuilder().build(endpoint, accessKeyId, accessKeySecret);
    }

    @Override
    public StoredObject put(String bucket, String key, byte[] data) {
        try {
            client.putObject(bucket, key, new ByteArrayInputStream(data));
            return new StoredObject(bucket + "/" + key, StorageRefs.md5Hex(data), data.length);
        } catch (OSSException e) {
            LOGGER.error("oss put failed bucket={} key={} errorCode={}", bucket, key, e.getErrorCode(), e);
            throw new ObjectStorageException("oss put failed", bucket, key, e.getErrorCode(), e);
        } catch (Exception e) {
            LOGGER.error("oss put failed bucket={} key={}", bucket, key, e);
            throw new ObjectStorageException("oss put failed", bucket, key, null, e);
        }
    }

    @Override
    public byte[] get(String ossRef) {
        String[] bk = StorageRefs.split(ossRef);
        try (OSSObject obj = client.getObject(bk[0], bk[1]);
             InputStream in = obj.getObjectContent()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Exception e) {
            LOGGER.error("oss get failed ref={}", ossRef, e);
            return null;
        }
    }

    @Override
    public String presignGet(String ossRef, int ttlSeconds) {
        String[] bk = StorageRefs.split(ossRef);
        Date expiry = new Date(System.currentTimeMillis() + ttlSeconds * 1000L);
        GeneratePresignedUrlRequest req = new GeneratePresignedUrlRequest(bk[0], bk[1]);
        req.setExpiration(expiry);
        URL url = client.generatePresignedUrl(req);
        return url.toString();
    }

    @Override
    public boolean exists(String ossRef) {
        String[] bk = StorageRefs.split(ossRef);
        return client.doesObjectExist(bk[0], bk[1]);
    }

    @Override
    public void delete(String ossRef) {
        String[] bk = StorageRefs.split(ossRef);
        try {
            client.deleteObject(bk[0], bk[1]);
        } catch (Exception e) {
            LOGGER.warn("oss delete failed ref={}", ossRef, e);
        }
    }
}
