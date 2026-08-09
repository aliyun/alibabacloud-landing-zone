package com.aliyun.autowonder.storage;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class S3PropertiesTest {

    @Test
    void validatePassesWhenAllRequiredSettingsPresent() {
        assertDoesNotThrow(() -> valid().validate());
    }

    @Test
    void validateRejectsMissingEndpoint() {
        S3Properties props = valid();
        props.setEndpoint("  ");

        IllegalStateException error = assertThrows(IllegalStateException.class, props::validate);
        assertEquals("s3.endpoint is required when s3.enabled is true", error.getMessage());
    }

    @Test
    void validateRejectsMissingRegion() {
        S3Properties props = valid();
        props.setRegion(null);

        IllegalStateException error = assertThrows(IllegalStateException.class, props::validate);
        assertEquals("s3.region is required when s3.enabled is true", error.getMessage());
    }

    @Test
    void validateRejectsMissingAccessKeyId() {
        S3Properties props = valid();
        props.setAccessKeyId("");

        IllegalStateException error = assertThrows(IllegalStateException.class, props::validate);
        assertEquals("s3.access-key-id is required when s3.enabled is true", error.getMessage());
    }

    @Test
    void validateRejectsMissingAccessKeySecret() {
        S3Properties props = valid();
        props.setAccessKeySecret(" ");

        IllegalStateException error = assertThrows(IllegalStateException.class, props::validate);
        assertEquals("s3.access-key-secret is required when s3.enabled is true", error.getMessage());
    }

    private static S3Properties valid() {
        S3Properties props = new S3Properties();
        props.setEnabled(true);
        props.setEndpoint("http://minio-internal.example.com:9000");
        props.setPublicEndpoint("http://minio.example.com:9000");
        props.setRegion("us-east-1");
        props.setAccessKeyId("test-access-key-id");
        props.setAccessKeySecret("test-access-key-secret");
        return props;
    }
}
