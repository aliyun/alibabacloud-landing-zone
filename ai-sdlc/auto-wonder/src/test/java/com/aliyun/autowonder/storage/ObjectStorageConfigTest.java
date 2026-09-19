package com.aliyun.autowonder.storage;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.taskpackage.PackageContext;
import com.aliyun.autowonder.taskpackage.TaskPackageResult;
import com.aliyun.autowonder.taskpackage.TaskPackager;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import java.io.ByteArrayInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ObjectStorageConfigTest {

    private final ObjectStorageConfig config = new ObjectStorageConfig();

    @Test
    void acceptsCurrentDailyBuckets() {
        OssProperties props = properties(
                "autowonder-task-pkg-daily-tmp-new",
                "autowonder-artifacts-daily-tmp-new");

        assertDoesNotThrow(() -> config.taskPackager(mock(ObjectStorage.class), props,
                mock(PlatformBrandingService.class)));
    }

    @Test
    void rejectsRetiredTaskPackageBucket() {
        OssProperties props = properties(
                "autowonder-task-pkg-daily-tmp",
                "autowonder-artifacts-daily-tmp-new");

        assertThrows(IllegalStateException.class,
                () -> config.taskPackager(mock(ObjectStorage.class), props,
                        mock(PlatformBrandingService.class)));
    }

    @Test
    void taskPackageMcpUrlFollowsTheBrandingDomainWithoutRestart() throws Exception {
        PlatformBrandingService brandingService = mock(PlatformBrandingService.class);
        when(brandingService.effectiveMcpBaseUrl()).thenReturn("https://wonder.example.com/api/mcp");
        InMemoryObjectStorage storage = new InMemoryObjectStorage();
        TaskPackager packager = config.taskPackager(storage,
                properties("autowonder-task-pkg-daily-tmp-new", "autowonder-artifacts-daily-tmp-new"),
                brandingService);

        TaskPackageResult first = packager.build(baseCtx());
        assertEquals("https://wonder.example.com/api/mcp", mcpUrlOf(storage.get(first.getOssRef())));

        // 品牌设置里清空域名后，之后打出的包立即回到部署地址，无需重启
        when(brandingService.effectiveMcpBaseUrl()).thenReturn("https://daily.auto-wonder.example.com/api/mcp");
        TaskPackageResult second = packager.build(baseCtx());
        assertEquals("https://daily.auto-wonder.example.com/api/mcp", mcpUrlOf(storage.get(second.getOssRef())));
    }

    @Test
    void blankEffectiveMcpUrlFailsThePackageBuildInsteadOfWritingAnUnusableAddress() {
        PlatformBrandingService brandingService = mock(PlatformBrandingService.class);
        when(brandingService.effectiveMcpBaseUrl()).thenReturn("  ");
        TaskPackager packager = config.taskPackager(new InMemoryObjectStorage(),
                properties("autowonder-task-pkg-daily-tmp-new", "autowonder-artifacts-daily-tmp-new"),
                brandingService);

        // 部署属性本身在 PlatformBrandingService 启动时校验（含 query/fragment），
        // 解析结果为空时必须在打包时 fail-closed，而不是写出一个不可用的 MCP 地址
        BizException ex = assertThrows(BizException.class, () -> packager.build(baseCtx()));

        assertEquals("17020", ex.getCode());
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
                .withBean(PlatformBrandingService.class, () -> mock(PlatformBrandingService.class))
                .withBean(OssProperties.class, ObjectStorageConfigTest::validProperties)
                .withBean(S3Properties.class, S3Properties::new)
                .withPropertyValues(
                        "oss.enabled=true",
                        "s3.enabled=false",
                        "autowonder.public-base-url=https://community.example.com")
                .run(context -> {
                    assertThat(context).hasSingleBean(ObjectStorage.class);
                    assertThat(context).doesNotHaveBean("inMemoryObjectStorage");
                });
    }

    @Test
    void contextCreatesExactlyOneS3StorageAndNoInMemoryFallback() {
        new ApplicationContextRunner()
                .withUserConfiguration(ObjectStorageConfig.class)
                .withBean(PlatformBrandingService.class, () -> mock(PlatformBrandingService.class))
                .withBean(OssProperties.class, () -> {
                    OssProperties props = validProperties();
                    props.setEnabled(false);
                    return props;
                })
                .withBean(S3Properties.class, ObjectStorageConfigTest::s3Properties)
                .withPropertyValues(
                        "oss.enabled=false",
                        "s3.enabled=true",
                        "autowonder.public-base-url=https://community.example.com")
                .run(context -> {
                    assertThat(context).hasSingleBean(ObjectStorage.class);
                    assertThat(context).getBean(ObjectStorage.class).isInstanceOf(S3ObjectStorage.class);
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

    @Test
    void buildsS3ObjectStorageWhenConfigured() {
        S3Properties props = s3Properties();
        OssProperties oss = new OssProperties();
        oss.setEnabled(false);

        ObjectStorage storage = config.s3ObjectStorage(props, oss);

        assertInstanceOf(S3ObjectStorage.class, storage);
    }

    @Test
    void rejectsWhenBothOssAndS3Enabled() {
        S3Properties s3 = s3Properties();
        OssProperties oss = new OssProperties();
        oss.setEnabled(true);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> config.s3ObjectStorage(s3, oss));

        assertEquals("oss.enabled and s3.enabled are mutually exclusive; disable one storage backend",
                error.getMessage());
    }

    private static PackageContext baseCtx() {
        PackageContext ctx = new PackageContext();
        ctx.setTenantId(100L);
        ctx.setWorkitemId(500L);
        ctx.setDispatchId(300L);
        ctx.setWorkitemTitle("t");
        ctx.setWorkitemContentMd("body");
        return ctx;
    }

    private static String mcpUrlOf(byte[] zip) throws Exception {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zip))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("skills.json".equals(entry.getName())) {
                    JSONObject capabilities = JSON.parseObject(new String(zis.readAllBytes()));
                    return capabilities.getJSONArray("mcpServers").getJSONObject(0).getString("url");
                }
            }
        }
        throw new AssertionError("skills.json missing from the task package");
    }

    private static S3Properties s3Properties() {
        S3Properties props = new S3Properties();
        props.setEnabled(true);
        props.setEndpoint("http://minio-internal.example.com:9000");
        props.setPublicEndpoint("http://minio.example.com:9000");
        props.setRegion("us-east-1");
        props.setAccessKeyId("test-access-key-id");
        props.setAccessKeySecret("test-access-key-secret");
        props.setForcePathStyle(true);
        return props;
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
