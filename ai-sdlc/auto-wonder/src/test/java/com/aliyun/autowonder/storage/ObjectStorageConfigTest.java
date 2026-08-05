package com.aliyun.autowonder.storage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

class ObjectStorageConfigTest {

    private final ObjectStorageConfig config = new ObjectStorageConfig();

    @Test
    void acceptsCurrentDailyBuckets() {
        OssProperties props = properties(
                "autowonder-task-pkg-daily-tmp-new",
                "autowonder-artifacts-daily-tmp-new");

        assertDoesNotThrow(() -> config.taskPackager(mock(ObjectStorage.class), props,
                "https://auto-wonder.alibaba.net"));
    }

    @Test
    void rejectsRetiredTaskPackageBucket() {
        OssProperties props = properties(
                "autowonder-task-pkg-daily-tmp",
                "autowonder-artifacts-daily-tmp-new");

        assertThrows(IllegalStateException.class,
                () -> config.taskPackager(mock(ObjectStorage.class), props,
                        "https://auto-wonder.alibaba.net"));
    }

    @Test
    void rejectsMissingPublicBaseUrlBeforeCreatingTaskPackager() {
        OssProperties props = properties(
                "autowonder-task-pkg-daily-tmp-new",
                "autowonder-artifacts-daily-tmp-new");

        assertThrows(IllegalStateException.class,
                () -> config.taskPackager(mock(ObjectStorage.class), props, ""));
    }

    @Test
    void rejectsPublicBaseUrlWithQueryOrFragmentBeforeCreatingTaskPackager() {
        OssProperties props = properties(
                "autowonder-task-pkg-daily-tmp-new",
                "autowonder-artifacts-daily-tmp-new");

        assertThrows(IllegalStateException.class,
                () -> config.taskPackager(mock(ObjectStorage.class), props, "https://daily.example.com?x=1"));
        assertThrows(IllegalStateException.class,
                () -> config.taskPackager(mock(ObjectStorage.class), props, "https://daily.example.com#anchor"));
    }

    @Test
    void rejectsRetiredArtifactBucketBeforeCreatingOssClient() {
        OssProperties props = properties(
                "autowonder-task-pkg-daily-tmp-new",
                "autowonder-artifacts-daily-tmp");
        props.setEndpoint("oss-cn-zhangjiakou.aliyuncs.com");
        props.setAccessKeyId("id");
        props.setAccessKeySecret("secret");

        assertThrows(IllegalStateException.class, () -> config.aliyunObjectStorage(props));
    }

    @Test
    void rejectsMissingRequiredOssProperties() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> config.aliyunObjectStorage(new OssProperties()));

        assertTrue(error.getMessage().contains("oss.endpoint"));
    }

    @Test
    void workloadBucketsFallBackToRequiredBaseBucket() {
        OssProperties props = validProperties();
        props.setBucket("community-bucket");

        assertEquals("community-bucket", props.resolveTaskPkgBucket());
        assertEquals("community-bucket", props.resolveArtifactBucket());
        assertEquals("community-bucket", props.resolveSkillBucket());
    }

    @Test
    void contextCreatesExactlyOneOssStorageAndNoInMemoryFallback() {
        new ApplicationContextRunner()
                .withUserConfiguration(ObjectStorageConfig.class)
                .withBean(OssProperties.class, ObjectStorageConfigTest::validProperties)
                .withPropertyValues("autowonder.public-base-url=https://community.example.com")
                .run(context -> {
                    assertThat(context).hasSingleBean(ObjectStorage.class);
                    assertThat(context).doesNotHaveBean("inMemoryObjectStorage");
                });
    }

    @Test
    void publicEndpointFallsBackToPublicServiceEndpoint() {
        OssProperties props = new OssProperties();
        props.setEndpoint("https://oss-cn-shanghai.aliyuncs.com");

        assertEquals("https://oss-cn-shanghai.aliyuncs.com", props.resolvePublicEndpoint());

        props.setPublicEndpoint("https://oss-accelerate.aliyuncs.com");
        assertEquals("https://oss-accelerate.aliyuncs.com", props.resolvePublicEndpoint());
    }

    @Test
    void rejectsInternalServiceEndpointWithoutPublicEndpoint() {
        OssProperties props = ossProperties(
                "https://oss-cn-shanghai-internal.aliyuncs.com", null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> config.aliyunObjectStorage(props));

        assertEquals("oss.public-endpoint is required when oss.endpoint is internal", error.getMessage());
    }

    @Test
    void rejectsInternalPublicEndpoint() {
        OssProperties props = ossProperties(
                "https://oss-cn-shanghai-internal.aliyuncs.com",
                "https://oss-cn-shanghai-internal.aliyuncs.com");

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> config.aliyunObjectStorage(props));

        assertEquals("oss.public-endpoint must be externally reachable", error.getMessage());
    }

    private static OssProperties ossProperties(String endpoint, String publicEndpoint) {
        OssProperties props = new OssProperties();
        props.setEndpoint(endpoint);
        props.setPublicEndpoint(publicEndpoint);
        props.setBucket("community-bucket");
        props.setAccessKeyId("test-access-key-id");
        props.setAccessKeySecret("test-access-key-secret");
        return props;
    }

    private static OssProperties properties(String taskBucket, String artifactBucket) {
        OssProperties props = new OssProperties();
        props.setTaskPkgBucket(taskBucket);
        props.setArtifactBucket(artifactBucket);
        return props;
    }

    private static OssProperties validProperties() {
        OssProperties props = new OssProperties();
        props.setEndpoint("https://oss.example.com");
        props.setBucket("community-bucket");
        props.setAccessKeyId("test-access-key-id");
        props.setAccessKeySecret("test-access-key-secret");
        return props;
    }
}
