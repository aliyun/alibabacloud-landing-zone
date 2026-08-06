package com.aliyun.autowonder.im.notification;

import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.branding.dto.PlatformBrandingVO;
import com.aliyun.autowonder.org.OrgDO;
import com.aliyun.autowonder.org.OrgDao;
import com.aliyun.autowonder.statemachine.StatusNodeDO;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ImNotificationMessageContextResolverTest {

    @Test
    void resolvesActualOrgStatusAndBrandingDomain() {
        OrgDao orgDao = mock(OrgDao.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        StatusNodeDao statusNodeDao = mock(StatusNodeDao.class);
        PlatformBrandingService brandingService = mock(PlatformBrandingService.class);
        ImNotificationMessageContextResolver resolver =
                new ImNotificationMessageContextResolver(orgDao, workitemDao, statusNodeDao, brandingService);
        OrgDO org = new OrgDO();
        org.setId(7L);
        org.setName("AutoWonder自迭代");
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(42L);
        workitem.setStatusNodeId(88L);
        StatusNodeDO status = new StatusNodeDO();
        status.setName("待我决策");
        when(orgDao.findById(7L)).thenReturn(org);
        when(workitemDao.findById(42L)).thenReturn(workitem);
        when(statusNodeDao.findById(88L)).thenReturn(status);
        when(brandingService.publicConfig()).thenReturn(new PlatformBrandingVO(
                "WonderHub", "/logo.png", "aliyun-orange", "#f97316",
                "https://wonder.example.com", "https://wonder.example.com/api/mcp", "0.2.117", false));

        ImNotificationMessageContext context = resolver.resolve(task());

        assertEquals("AutoWonder自迭代", context.orgName());
        assertEquals("待我决策", context.statusName());
        assertEquals("https://wonder.example.com", context.baseUrl());
        assertEquals(7L, context.tenantId());
    }

    @Test
    void fallsBackSafelyWhenDataIsMissing() {
        OrgDao orgDao = mock(OrgDao.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        StatusNodeDao statusNodeDao = mock(StatusNodeDao.class);
        PlatformBrandingService brandingService = mock(PlatformBrandingService.class);
        ImNotificationMessageContextResolver resolver =
                new ImNotificationMessageContextResolver(orgDao, workitemDao, statusNodeDao, brandingService);
        when(brandingService.publicConfig()).thenReturn(new PlatformBrandingVO(
                "WonderHub", "/logo.png", "aliyun-orange", "#f97316",
                null, "https://wonder.example.com/api/mcp", "0.2.117", false));

        ImNotificationMessageContext context = resolver.resolve(task());

        assertEquals(PlatformBrandingService.DEFAULT_PLATFORM_NAME, context.orgName());
        assertEquals("已指派", context.statusName());
        assertEquals(null, context.baseUrl());
        assertEquals(7L, context.tenantId());
    }

    private static ImNotificationTask task() {
        return new ImNotificationTask(
                "notification-key-1",
                100L,
                7L,
                42L,
                9L,
                "USER",
                3L,
                "张三",
                "rid-1",
                "生产环境发布审批");
    }
}
