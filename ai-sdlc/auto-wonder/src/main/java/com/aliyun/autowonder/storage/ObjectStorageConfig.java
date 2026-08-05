package com.aliyun.autowonder.storage;

import com.aliyun.autowonder.taskpackage.TaskPackager;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.Set;

@Configuration
public class ObjectStorageConfig {

    private static final Set<String> RETIRED_BUCKETS = Set.of(
            "autowonder-task-pkg-daily-tmp",
            "autowonder-artifacts-daily-tmp");

    @Bean
    public ObjectStorage aliyunObjectStorage(OssProperties props) {
        validateRequiredProperties(props);
        validateConfiguredBuckets(props);
        validateEndpointRouting(props);
        return new AliyunOssObjectStorage(props.getEndpoint(), props.resolvePublicEndpoint(),
                props.getAccessKeyId(), props.getAccessKeySecret());
    }

    @Bean
    public TaskPackager taskPackager(ObjectStorage objectStorage, OssProperties props,
                                     @Value("${autowonder.public-base-url:}") String publicBaseUrl) {
        validateConfiguredBuckets(props);
        return new TaskPackager(objectStorage, props.resolveTaskPkgBucket(), publicBaseUrl);
    }

    private static void validateRequiredProperties(OssProperties props) {
        require("oss.endpoint", props.getEndpoint());
        require("oss.bucket", props.getBucket());
        require("oss.access-key-id", props.getAccessKeyId());
        require("oss.access-key-secret", props.getAccessKeySecret());
    }

    private static void require(String property, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(property + " is required");
        }
    }

    private static void validateConfiguredBuckets(OssProperties props) {
        rejectRetiredBucket("oss.task-pkg-bucket", props.getTaskPkgBucket());
        rejectRetiredBucket("oss.artifact-bucket", props.getArtifactBucket());
    }

    private static void validateEndpointRouting(OssProperties props) {
        if (isInternalEndpoint(props.getEndpoint()) && !StringUtils.hasText(props.getPublicEndpoint())) {
            throw new IllegalStateException("oss.public-endpoint is required when oss.endpoint is internal");
        }
        if (isInternalEndpoint(props.resolvePublicEndpoint())) {
            throw new IllegalStateException("oss.public-endpoint must be externally reachable");
        }
    }

    private static boolean isInternalEndpoint(String endpoint) {
        return StringUtils.hasText(endpoint) && endpoint.toLowerCase().contains("-internal.");
    }

    private static void rejectRetiredBucket(String property, String bucket) {
        if (bucket != null && RETIRED_BUCKETS.contains(bucket.trim())) {
            throw new IllegalStateException(property + " references retired OSS bucket " + bucket
                    + "; use the corresponding *-tmp-new bucket");
        }
    }
}
