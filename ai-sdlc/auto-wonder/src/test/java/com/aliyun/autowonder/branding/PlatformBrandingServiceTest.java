package com.aliyun.autowonder.branding;

import com.aliyun.autowonder.branding.dto.UpdatePlatformBrandingRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.storage.StoredObject;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlatformBrandingServiceTest {

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
        assertFalse(config.isCanManage());
    }

    @Test
    void rejectsMissingDeploymentPublicBaseUrl() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        OssProperties props = new OssProperties();

        assertThrows(IllegalStateException.class,
                () -> new PlatformBrandingService(dao, new InMemoryObjectStorage(), props, "", "0.2.130", "x.x.x"));
    }

    @Test
    void rejectsPublicBaseUrlWithQueryOrFragment() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        OssProperties props = new OssProperties();

        assertThrows(IllegalStateException.class,
                () -> new PlatformBrandingService(
                        dao, new InMemoryObjectStorage(), props, "https://daily.example.com?x=1", "0.2.130", "x.x.x"));
        assertThrows(IllegalStateException.class,
                () -> new PlatformBrandingService(
                        dao, new InMemoryObjectStorage(), props, "https://daily.example.com#anchor", "0.2.130", "x.x.x"));
    }

    @Test
    void updateCannotRedirectTheDeploymentManagedMcpEndpoint() {
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
        assertEquals("https://daily.auto-wonder.example.com/api/mcp", updated.getMcpBaseUrl());
        verify(dao).update(argThat(config ->
                "WonderHub".equals(config.getPlatformName())
                        && "#2563eb".equals(config.getPrimaryColor())
                        && "https://wonder.example.com".equals(config.getDomain())));
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
                        "https://daily.auto-wonder.example.com", "0.2.130", "not-a-version"));
    }

    @Test
    void exposesTrustedPublicBaseUrlAndRuntimeVersion() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        PlatformBrandingService service = newService(dao);

        assertEquals("https://daily.auto-wonder.example.com", service.trustedPublicBaseUrl());
        assertEquals("0.2.130", service.recommendedRuntimeVersion());
    }

    @Test
    void trustedBaseUrlSupportsPrivateDeploymentsAndStripsTrailingSlashes() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        OssProperties props = new OssProperties();
        PlatformBrandingService service = new PlatformBrandingService(
                dao, new InMemoryObjectStorage(), props,
                "http://autowonder.internal.example.com:8080//", "1.0.0", "x.x.x");

        assertEquals("http://autowonder.internal.example.com:8080", service.trustedPublicBaseUrl());
        assertEquals("http://autowonder.internal.example.com:8080/api/mcp",
                service.publicConfig().getMcpBaseUrl());
    }

    @Test
    void recommendedRuntimeVersionPreservesPrereleaseSuffix() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        OssProperties props = new OssProperties();
        PlatformBrandingService service = new PlatformBrandingService(
                dao, new InMemoryObjectStorage(), props,
                "https://daily.auto-wonder.example.com", "0.3.0-beta.2", "x.x.x");

        assertEquals("0.3.0-beta.2", service.recommendedRuntimeVersion());
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
        OssProperties props = new OssProperties();
        props.setBucket("community-test");
        return new PlatformBrandingService(
                dao, storage, props, "https://daily.auto-wonder.example.com", "0.2.130", deploymentVersion);
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
