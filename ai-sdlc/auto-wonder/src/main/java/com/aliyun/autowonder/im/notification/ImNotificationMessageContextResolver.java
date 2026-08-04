package com.aliyun.autowonder.im.notification;

import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.branding.dto.PlatformBrandingVO;
import com.aliyun.autowonder.org.OrgDO;
import com.aliyun.autowonder.org.OrgDao;
import com.aliyun.autowonder.statemachine.StatusNodeDO;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.springframework.stereotype.Component;

@Component
public class ImNotificationMessageContextResolver {
    private static final String FALLBACK_STATUS_NAME = "已指派";

    private final OrgDao orgDao;
    private final WorkitemDao workitemDao;
    private final StatusNodeDao statusNodeDao;
    private final PlatformBrandingService brandingService;

    public ImNotificationMessageContextResolver(OrgDao orgDao,
                                                WorkitemDao workitemDao,
                                                StatusNodeDao statusNodeDao,
                                                PlatformBrandingService brandingService) {
        this.orgDao = orgDao;
        this.workitemDao = workitemDao;
        this.statusNodeDao = statusNodeDao;
        this.brandingService = brandingService;
    }

    public ImNotificationMessageContext resolve(ImNotificationTask task) {
        WorkitemDO workitem = workitemDao.findById(task.workitemId());
        String orgName = resolveOrgName(task.tenantId());
        String statusName = resolveStatusName(workitem);
        String baseUrl = resolveBaseUrl();
        return new ImNotificationMessageContext(orgName, statusName, baseUrl, task.tenantId());
    }

    private String resolveOrgName(long tenantId) {
        OrgDO org = orgDao.findById(tenantId);
        return hasText(org == null ? null : org.getName())
                ? org.getName().trim()
                : PlatformBrandingService.DEFAULT_PLATFORM_NAME;
    }

    private String resolveStatusName(WorkitemDO workitem) {
        if (workitem == null || workitem.getStatusNodeId() == null) {
            return FALLBACK_STATUS_NAME;
        }
        StatusNodeDO node = statusNodeDao.findById(workitem.getStatusNodeId());
        if (node == null) {
            return FALLBACK_STATUS_NAME;
        }
        if (hasText(node.getName())) {
            return node.getName().trim();
        }
        if (hasText(node.getCode())) {
            return node.getCode().trim();
        }
        return FALLBACK_STATUS_NAME;
    }

    private String resolveBaseUrl() {
        PlatformBrandingVO branding = brandingService.publicConfig();
        String domain = branding == null ? null : branding.getDomain();
        return hasText(domain) ? domain.trim() : null;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
