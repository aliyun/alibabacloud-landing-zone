package com.aliyun.autowonder.branding;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.branding.dto.PlatformBrandingVO;
import com.aliyun.autowonder.branding.dto.UpdatePlatformBrandingRequest;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformBrandingControllerTest {

    @AfterEach
    void cleanup() {
        AutoWonderContext.destroy();
    }

    @Test
    void adminConfigIsVisibleAndReturnsWhetherCurrentUserCanManage() {
        PlatformBrandingService brandingService = mock(PlatformBrandingService.class);
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        PlatformBrandingVO vo = branding("AutoWonder");
        when(systemAdminService.isFirstActiveUser(10000L)).thenReturn(true);
        when(brandingService.adminConfig(true)).thenReturn(vo);
        AutoWonderContext.get().setUserId(10000L);
        PlatformBrandingController controller =
                new PlatformBrandingController(brandingService, systemAdminService);

        assertEquals(vo, controller.adminConfig().getData());

        verify(systemAdminService).isFirstActiveUser(10000L);
    }

    @Test
    void updateRequiresFirstActiveUserInsteadOfOrgAdmin() {
        PlatformBrandingService brandingService = mock(PlatformBrandingService.class);
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        PlatformBrandingVO vo = branding("AutoWonder");
        UpdatePlatformBrandingRequest request = new UpdatePlatformBrandingRequest();
        when(brandingService.update(10000L, request)).thenReturn(vo);
        AutoWonderContext.get().setUserId(10000L);
        PlatformBrandingController controller =
                new PlatformBrandingController(brandingService, systemAdminService);

        assertEquals(vo, controller.update(request).getData());

        verify(systemAdminService).requireFirstActiveUser(10000L, "更新平台品牌配置");
    }

    @Test
    void uploadLogoRequiresFirstActiveUserInsteadOfOrgAdmin() {
        PlatformBrandingService brandingService = mock(PlatformBrandingService.class);
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        MockMultipartFile file = new MockMultipartFile(
                "file", "logo.png", "image/png", new byte[]{1, 2, 3});
        AutoWonderContext.get().setUserId(10000L);
        PlatformBrandingController controller =
                new PlatformBrandingController(brandingService, systemAdminService);

        controller.uploadLogo(file);

        verify(systemAdminService).requireFirstActiveUser(10000L, "上传平台品牌标志");
    }

    private static PlatformBrandingVO branding(String platformName) {
        return new PlatformBrandingVO(
                platformName,
                null,
                "default",
                "#f97316",
                null,
                "https://daily.auto-wonder.example.com/api/mcp",
                "0.2.114",
                false);
    }
}
