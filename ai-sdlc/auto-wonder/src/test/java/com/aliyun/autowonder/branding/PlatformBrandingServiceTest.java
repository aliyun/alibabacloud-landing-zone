package com.aliyun.autowonder.branding;

import com.aliyun.autowonder.branding.dto.UpdatePlatformBrandingRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
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
        assertEquals("https://daily.auto-wonder.example.com/api/mcp", config.getMcpBaseUrl());
        assertFalse(config.isCanManage());
    }

    @Test
    void rejectsMissingDeploymentPublicBaseUrl() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        OssProperties props = new OssProperties();

        assertThrows(IllegalStateException.class,
                () -> new PlatformBrandingService(dao, new InMemoryObjectStorage(), props, ""));
    }

    @Test
    void rejectsPublicBaseUrlWithQueryOrFragment() {
        PlatformBrandingDao dao = mock(PlatformBrandingDao.class);
        OssProperties props = new OssProperties();

        assertThrows(IllegalStateException.class,
                () -> new PlatformBrandingService(
                        dao, new InMemoryObjectStorage(), props, "https://daily.example.com?x=1"));
        assertThrows(IllegalStateException.class,
                () -> new PlatformBrandingService(
                        dao, new InMemoryObjectStorage(), props, "https://daily.example.com#anchor"));
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

    private static PlatformBrandingService newService(PlatformBrandingDao dao) {
        return newService(dao, new InMemoryObjectStorage());
    }

    private static PlatformBrandingService newService(
            PlatformBrandingDao dao, InMemoryObjectStorage storage) {
        OssProperties props = new OssProperties();
        props.setBucket("community-test");
        return new PlatformBrandingService(
                dao, storage, props, "https://daily.auto-wonder.example.com");
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
