package com.aliyun.autowonder.storage;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@ConfigurationProperties(prefix = "s3")
@Getter
@Setter
public class S3Properties {
    private boolean enabled = false;
    /** Internal endpoint used by the server for put/get/head/delete. */
    private String endpoint;
    /** Externally reachable endpoint used to sign download URLs handed to clients. */
    private String publicEndpoint;
    /** SigV4 region; a placeholder such as "us-east-1" is fine for MinIO. */
    private String region = "us-east-1";
    private String accessKeyId;
    private String accessKeySecret;
    /** MinIO and most self-hosted stores require path-style addressing. */
    private boolean forcePathStyle = true;
    // Bucket names are backend-agnostic and shared via oss.* (task-pkg/artifact/skill),
    // consumed by TaskPackager and the artifact services regardless of the active backend.

    public String resolvePublicEndpoint() {
        return StringUtils.hasText(publicEndpoint) ? publicEndpoint : endpoint;
    }

    /** Fail fast on incomplete S3 configuration before a client is built. */
    public void validate() {
        require("s3.endpoint", endpoint);
        require("s3.region", region);
        require("s3.access-key-id", accessKeyId);
        require("s3.access-key-secret", accessKeySecret);
    }

    private static void require(String property, String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalStateException(property + " is required when s3.enabled is true");
        }
    }
}
