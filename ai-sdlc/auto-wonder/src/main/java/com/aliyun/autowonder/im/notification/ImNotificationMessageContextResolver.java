package com.aliyun.autowonder.im.notification;

import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.workspace.WorkspaceDO;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import com.aliyun.autowonder.statemachine.StatusNodeDO;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.springframework.stereotype.Component;

@Component
public class ImNotificationMessageContextResolver {
    private static final String FALLBACK_STATUS_NAME = "已指派";

    private final WorkspaceDao workspaceDao;
    private final WorkitemDao workitemDao;
    private final StatusNodeDao statusNodeDao;
    private final PlatformBrandingService brandingService;

    public ImNotificationMessageContextResolver(WorkspaceDao workspaceDao,
                                                WorkitemDao workitemDao,
                                                StatusNodeDao statusNodeDao,
                                                PlatformBrandingService brandingService) {
        this.workspaceDao = workspaceDao;
        this.workitemDao = workitemDao;
        this.statusNodeDao = statusNodeDao;
        this.brandingService = brandingService;
    }

    public ImNotificationMessageContext resolve(ImNotificationTask task) {
        WorkitemDO workitem = workitemDao.findById(task.workitemId());
        String workspaceName = resolveWorkspaceName(task.tenantId());
        Status status = resolveStatus(workitem);
        String baseUrl = resolveBaseUrl();
        return new ImNotificationMessageContext(workspaceName, status.name(), baseUrl, task.tenantId(), status.fallback());
    }

    private String resolveWorkspaceName(long tenantId) {
        WorkspaceDO workspace = workspaceDao.findById(tenantId);
        return hasText(workspace == null ? null : workspace.getName())
                ? workspace.getName().trim()
                : PlatformBrandingService.DEFAULT_PLATFORM_NAME;
    }

    private Status resolveStatus(WorkitemDO workitem) {
        if (workitem == null || workitem.getStatusNodeId() == null) {
            return new Status(FALLBACK_STATUS_NAME, true);
        }
        StatusNodeDO node = statusNodeDao.findById(workitem.getStatusNodeId());
        if (node == null) {
            return new Status(FALLBACK_STATUS_NAME, true);
        }
        if (hasText(node.getName())) {
            return new Status(node.getName().trim(), false);
        }
        if (hasText(node.getCode())) {
            return new Status(node.getCode().trim(), false);
        }
        return new Status(FALLBACK_STATUS_NAME, true);
    }

    private record Status(String name, boolean fallback) {}

    private String resolveBaseUrl() {
        // Branding domain first, deployment autowonder.public-base-url as the fallback; the
        // same resolution every other outbound URL uses, so links stay in sync with them.
        return brandingService.effectivePublicBaseUrl();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
