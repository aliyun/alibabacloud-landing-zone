package com.aliyun.autowonder.branding;

import com.aliyun.autowonder.branding.dto.UpdatePlatformBrandingRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.storage.StoredObject;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mock.web.MockMultipartFile;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformBrandingServiceTest {

    private static final String RECOMMENDED_VERSION_PLACEHOLDER =
            "${autowonder.runtime.recommended-version:";
    private static final String YAML_RECOMMENDED_VERSION_PLACEHOLDER =
            "${AUTOWONDER_RUNTIME_RECOMMENDED_VERSION:";
    private static final String EXPECTED_RECOMMENDED_RUNTIME_VERSION = "0.2.163";

    @Test
    void publicConfigFallsBackWhenDatabaseRowIsMissing() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        PlatformBrandingService service = newService(dao);

        var config = service.publicConfig();

        assertEquals("AutoWonder", config.getPlatformName());
        assertEquals("#f97316", config.getPrimaryColor());
        assertNull(config.getDomain());
        assertEquals("https://daily.auto-wonder.example.com/api/mcp", config.getMcpBaseUrl());
        assertEquals("0.2.130", config.getRecommendedRuntimeVersion());
        assertEquals("x.x.x", config.getDeploymentVersion());
        assertFalse(config.isCommunityEdition());
        assertFalse(config.isCanManage());
    }

    @Test
    void recommendedRuntimeVersionConstructorDefaultIsPinned() {
        assertEquals(EXPECTED_RECOMMENDED_RUNTIME_VERSION, constructorRecommendedRuntimeVersionDefault());
    }

    @Test
    void applicationYmlRecommendedVersionDefaultIsPinnedAndMatchesConstructorDefault() throws Exception {
        String yamlDefault = applicationYmlRecommendedVersionDefault();

        assertEquals(EXPECTED_RECOMMENDED_RUNTIME_VERSION, yamlDefault);
        assertEquals(constructorRecommendedRuntimeVersionDefault(), yamlDefault,
                "application.yml 与 PlatformBrandingService @Value 的兜底版本必须一致，否则两处默认值会静默漂移");
    }

    @Test
    void publicConfigExposesCommunityEditionFlagWhenEnabled() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        PlatformBrandingService service =
                newService(dao, new InMemoryObjectStorage(), "x.x.x", true);

        assertTrue(service.publicConfig().isCommunityEdition());
        assertTrue(service.adminConfig(true).isCommunityEdition());
    }

    @Test
    void rejectsMissingDeploymentPublicBaseUrl() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        OssProperties props = new OssProperties();

        assertThrows(IllegalStateException.class,
                () -> new PlatformBrandingService(dao, new InMemoryObjectStorage(), props, "", "0.2.130", "x.x.x", false));
    }

    @Test
    void rejectsPublicBaseUrlWithQueryOrFragment() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        OssProperties props = new OssProperties();

        assertThrows(IllegalStateException.class,
                () -> new PlatformBrandingService(
                        dao, new InMemoryObjectStorage(), props, "https://daily.example.com?x=1", "0.2.130", "x.x.x", false));
        assertThrows(IllegalStateException.class,
                () -> new PlatformBrandingService(
                        dao, new InMemoryObjectStorage(), props, "https://daily.example.com#anchor", "0.2.130", "x.x.x", false));
    }

    @Test
    void updateAppliesTheBrandingDomainToThePublicMcpEndpoint() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        when(dao.update(any())).thenReturn(1);
        when(dao.findActive()).thenReturn(row(
                "WonderHub", "#2563eb", "https://wonder.example.com"));
        PlatformBrandingService service = newService(dao);

        UpdatePlatformBrandingRequest request = new UpdatePlatformBrandingRequest();
        request.setPlatformName("WonderHub");
        request.setThemeKey("ocean-blue");
        request.setPrimaryColor("#2563EB");
        request.setDomain("https://wonder.example.com/");

        var updated = service.update(100L, request);

        assertEquals("WonderHub", updated.getPlatformName());
        assertEquals("https://wonder.example.com/api/mcp", updated.getMcpBaseUrl());
        verify(dao).update(argThat(config ->
                "WonderHub".equals(config.getPlatformName())
                        && "#2563eb".equals(config.getPrimaryColor())
                        && "https://wonder.example.com".equals(config.getDomain())));
    }

    @Test
    void publicMcpEndpointFollowsTheConfiguredBrandingDomain() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        when(dao.findActive()).thenReturn(row("WonderHub", "#2563eb", "https://wonder.example.com"));
        PlatformBrandingService service = newService(dao);

        assertEquals("https://wonder.example.com", service.effectivePublicBaseUrl());
        assertEquals("https://wonder.example.com/api/mcp", service.effectiveMcpBaseUrl());
        assertEquals("https://wonder.example.com/api/mcp", service.publicConfig().getMcpBaseUrl());
        assertEquals("https://wonder.example.com/api/mcp", service.adminConfig(true).getMcpBaseUrl());
    }

    @Test
    void effectiveBaseUrlFallsBackToTheDeploymentBaseUrlWhenDomainIsMissing() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        when(dao.findActive()).thenReturn(row("WonderHub", "#2563eb", null));
        PlatformBrandingService service = newService(dao);

        assertEquals("https://daily.auto-wonder.example.com", service.effectivePublicBaseUrl());
        assertEquals("https://daily.auto-wonder.example.com/api/mcp", service.effectiveMcpBaseUrl());
        assertEquals("https://daily.auto-wonder.example.com/api/mcp", service.publicConfig().getMcpBaseUrl());
    }

    @Test
    void effectiveBaseUrlFallsBackToTheDeploymentBaseUrlWhenDomainIsBlank() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        when(dao.findActive()).thenReturn(row("WonderHub", "#2563eb", "   "));
        PlatformBrandingService service = newService(dao);

        assertEquals("https://daily.auto-wonder.example.com", service.effectivePublicBaseUrl());
    }

    @Test
    void effectiveBaseUrlUsesDeploymentUrlForCommunityDefaultConfiguration() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        when(dao.findActive()).thenReturn(null);
        PlatformBrandingService service = newService(dao);

        assertEquals("https://daily.auto-wonder.example.com", service.effectivePublicBaseUrl());
        assertEquals("https://daily.auto-wonder.example.com/api/mcp", service.publicConfig().getMcpBaseUrl());
    }

    @Test
    void effectiveBaseUrlFollowsDomainChangesWithoutRestart() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        when(dao.findActive()).thenReturn(row("WonderHub", "#2563eb", "https://first.example.com"));
        PlatformBrandingService service = newService(dao);
        assertEquals("https://first.example.com/api/mcp", service.publicConfig().getMcpBaseUrl());

        when(dao.findActive()).thenReturn(row("WonderHub", "#2563eb", "https://second.example.com"));
        assertEquals("https://second.example.com/api/mcp", service.publicConfig().getMcpBaseUrl());

        when(dao.findActive()).thenReturn(row("WonderHub", "#2563eb", null));
        assertEquals("https://daily.auto-wonder.example.com/api/mcp", service.publicConfig().getMcpBaseUrl());
    }

    @Test
    void updateRejectsInvalidColor() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        PlatformBrandingService service = newService(dao);

        UpdatePlatformBrandingRequest request = new UpdatePlatformBrandingRequest();
        request.setPlatformName("WonderHub");
        request.setThemeKey("ocean-blue");
        request.setPrimaryColor("blue");

        assertThrows(BizException.class, () -> service.update(100L, request));
    }

    @Test
    void updateRejectsPlainHttpDomain() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        PlatformBrandingService service = newService(dao);

        UpdatePlatformBrandingRequest request = new UpdatePlatformBrandingRequest();
        request.setPlatformName("WonderHub");
        request.setThemeKey("ocean-blue");
        request.setPrimaryColor("#2563eb");
        request.setDomain("http://wonder.example.com");
        assertThrows(BizException.class, () -> service.update(100L, request));
    }

    @Test
    void uploadLogoStoresSupportedImageAndPublishesLogoEndpoint() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        when(dao.updateLogo(anyString(), anyString(), eq(100L))).thenReturn(1);
        PlatformBrandingDO withLogo = row("AutoWonder", "#f97316", null);
        withLogo.setLogoOssRef("community-test/platform/branding/logo-1.png");
        when(dao.findActive()).thenReturn(withLogo);
        InMemoryObjectStorage storage = new InMemoryObjectStorage();
        PlatformBrandingService service = newService(dao, storage);

        MockMultipartFile file = new MockMultipartFile("file", "logo.png", "image/png", new byte[]{1, 2, 3});
        var result = service.uploadLogo(100L, file);

        assertTrue(result.getLogoUrl().startsWith("/api/platform/branding/logo"));
        verify(dao).updateLogo(startsWith("community-test/platform/branding/logo-"), eq("image/png"), eq(100L));
    }

    @Test
    void logoBytesReturnsNullWhenNoLogoConfigured() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        when(dao.findActive()).thenReturn(row("AutoWonder", "#f97316", null));
        ObjectStorage storage = mock(ObjectStorage.class);
        PlatformBrandingService service = newService(dao, storage);

        assertNull(service.logoBytes());
        verifyNoInteractions(storage);
    }

    @Test
    void logoBytesCachesResultOnRepeatedCalls() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        PlatformBrandingDO withLogo = row("AutoWonder", "#f97316", null);
        withLogo.setLogoOssRef("bucket/key");
        withLogo.setLogoContentType("image/png");
        when(dao.findActive()).thenReturn(withLogo);
        when(storage.get("bucket/key")).thenReturn(new byte[]{1, 2, 3});
        PlatformBrandingService service = newService(dao, storage);

        byte[] first = service.logoBytes();
        byte[] second = service.logoBytes();

        assertArrayEquals(new byte[]{1, 2, 3}, first);
        assertArrayEquals(new byte[]{1, 2, 3}, second);
        verify(storage, times(1)).get("bucket/key");
    }

    @Test
    void uploadLogoInvalidatesCacheForNewOssRef() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        ObjectStorage storage = mock(ObjectStorage.class);
        when(storage.put(anyString(), anyString(), any())).thenReturn(
                new StoredObject("bucket/new-logo.png", "abc", 3));
        when(dao.updateLogo(anyString(), anyString(), eq(100L))).thenReturn(1);

        PlatformBrandingDO withOldLogo = row("AutoWonder", "#f97316", null);
        withOldLogo.setLogoOssRef("bucket/old-logo.png");
        withOldLogo.setLogoContentType("image/png");
        when(dao.findActive()).thenReturn(withOldLogo);
        when(storage.get("bucket/old-logo.png")).thenReturn(new byte[]{1});

        PlatformBrandingService service = newService(dao, storage);
        service.logoBytes();

        PlatformBrandingDO withNewLogo = row("AutoWonder", "#f97316", null);
        withNewLogo.setLogoOssRef("bucket/new-logo.png");
        withNewLogo.setLogoContentType("image/png");
        when(dao.findActive()).thenReturn(withNewLogo);
        when(storage.get("bucket/new-logo.png")).thenReturn(new byte[]{2});

        service.uploadLogo(100L, new MockMultipartFile("file", "logo.png", "image/png", new byte[]{3}));

        byte[] result = service.logoBytes();
        assertArrayEquals(new byte[]{2}, result);
        verify(storage).get("bucket/new-logo.png");
    }

    @Test
    void publicConfigReturnsConfiguredDeploymentVersion() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        PlatformBrandingService service = newService(dao, new InMemoryObjectStorage(), "1.2.3");

        assertEquals("1.2.3", service.publicConfig().getDeploymentVersion());
    }

    @Test
    void publicConfigAcceptsSemanticDeploymentVersionWithPrerelease() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        PlatformBrandingService service = newService(dao, new InMemoryObjectStorage(), "1.2.3-beta.1");

        assertEquals("1.2.3-beta.1", service.publicConfig().getDeploymentVersion());
    }

    @Test
    void deploymentVersionFallsBackToPlaceholderWhenBlank() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        PlatformBrandingService service = newService(dao, new InMemoryObjectStorage(), "  ");

        assertEquals("x.x.x", service.publicConfig().getDeploymentVersion());
    }

    @Test
    void rejectsInvalidDeploymentVersionAtStartup() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        OssProperties props = new OssProperties();

        assertThrows(IllegalStateException.class,
                () -> new PlatformBrandingService(
                        dao, new InMemoryObjectStorage(), props,
                        "https://daily.auto-wonder.example.com", "0.2.130", "not-a-version", false));
    }

    @Test
    void exposesEffectivePublicBaseUrlAndRuntimeVersion() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        PlatformBrandingService service = newService(dao);

        assertEquals("https://daily.auto-wonder.example.com", service.effectivePublicBaseUrl());
        assertEquals("0.2.130", service.recommendedRuntimeVersion());
    }

    @Test
    void effectiveBaseUrlSupportsPrivateDeploymentsAndStripsTrailingSlashes() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        PlatformBrandingService service = newService(dao, new InMemoryObjectStorage(), "x.x.x");

        assertEquals("https://daily.auto-wonder.example.com", service.effectivePublicBaseUrl());

        OssProperties props = new OssProperties();
        PlatformBrandingService privateDeployment = new PlatformBrandingService(
                dao, new InMemoryObjectStorage(), props,
                "http://autowonder.internal.example.com:8080//", "1.0.0", "x.x.x", false);

        assertEquals("http://autowonder.internal.example.com:8080", privateDeployment.effectivePublicBaseUrl());
        assertEquals("http://autowonder.internal.example.com:8080/api/mcp",
                privateDeployment.publicConfig().getMcpBaseUrl());
    }

    @Test
    void recommendedRuntimeVersionPreservesPrereleaseSuffix() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        OssProperties props = new OssProperties();
        PlatformBrandingService service = new PlatformBrandingService(
                dao, new InMemoryObjectStorage(), props,
                "https://daily.auto-wonder.example.com", "0.3.0-beta.2", "x.x.x", false);

        assertEquals("0.3.0-beta.2", service.recommendedRuntimeVersion());
    }

    private static String constructorRecommendedRuntimeVersionDefault() {
        String foundDefault = null;
        for (Constructor<?> constructor : PlatformBrandingService.class.getConstructors()) {
            for (Parameter parameter : constructor.getParameters()) {
                Value annotation = parameter.getAnnotation(Value.class);
                if (annotation != null && annotation.value().startsWith(RECOMMENDED_VERSION_PLACEHOLDER)
                        && annotation.value().endsWith("}")) {
                    foundDefault = annotation.value()
                            .substring(RECOMMENDED_VERSION_PLACEHOLDER.length(),
                                    annotation.value().length() - 1);
                }
            }
        }
        assertNotNull(foundDefault, "PlatformBrandingService 构造函数缺少 recommended-version 的 @Value 默认值");
        return foundDefault;
    }

    @SuppressWarnings("unchecked")
    private static String applicationYmlRecommendedVersionDefault() throws Exception {
        // Community ships src/test/resources/application.yml, which shadows the real config on the test classpath.
        Path mainConfig = Path.of("src/main/resources/application.yml");
        assertTrue(Files.isRegularFile(mainConfig), "application.yml 必须存在于 src/main/resources");
        try (InputStream in = Files.newInputStream(mainConfig)) {
            Map<String, Object> root = new Yaml().loadAs(in, Map.class);
            Map<String, Object> autowonder = childSection(root, "autowonder");
            Map<String, Object> runtime = childSection(autowonder, "runtime");
            Object value = runtime.get("recommended-version");
            assertNotNull(value, "缺少配置项: autowonder.runtime.recommended-version");
            String placeholder = String.valueOf(value);
            assertTrue(placeholder.startsWith(YAML_RECOMMENDED_VERSION_PLACEHOLDER),
                    "应保留 AUTOWONDER_RUNTIME_RECOMMENDED_VERSION 环境变量覆盖能力，实际: " + placeholder);
            assertTrue(placeholder.endsWith("}"), "占位符必须闭合，实际: " + placeholder);
            return placeholder.substring(YAML_RECOMMENDED_VERSION_PLACEHOLDER.length(),
                    placeholder.length() - 1);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> childSection(Map<String, Object> parent, String key) {
        Object value = parent.get(key);
        assertNotNull(value, "缺少配置节: " + key);
        return (Map<String, Object>) value;
    }

    private static PlatformBrandingService newService(PlatformBrandingDao dao) {
        return newService(dao, new InMemoryObjectStorage());
    }

    private static PlatformBrandingService newService(
            PlatformBrandingDao dao, ObjectStorage storage) {
        return newService(dao, storage, "x.x.x");
    }

    private static PlatformBrandingService newService(
            PlatformBrandingDao dao, ObjectStorage storage, String deploymentVersion) {
        return newService(dao, storage, deploymentVersion, false);
    }

    private static PlatformBrandingService newService(
            PlatformBrandingDao dao, ObjectStorage storage,
            String deploymentVersion, boolean communityEdition) {
        OssProperties props = new OssProperties();
        props.setBucket("community-test");
        return new PlatformBrandingService(
                dao, storage, props, "https://daily.auto-wonder.example.com",
                "0.2.130", deploymentVersion, communityEdition);
    }

    private static PlatformBrandingDO row(String name, String color, String domain) {
        PlatformBrandingDO row = new PlatformBrandingDO();
        row.setPlatformName(name);
        row.setThemeKey("ocean-blue");
        row.setPrimaryColor(color);
        row.setDomain(domain);
        row.setVersion(3);
        return row;
    }
}
