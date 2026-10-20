package com.aliyun.autowonder.access;

import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import org.springframework.stereotype.Component;

import java.util.Set;

/** 在跨功能写入前检查角色权限；读取来源数据不需要写权限。 */
@Component
public class WorkspaceDomainGuard {

    private final WorkspaceMemberDao workspaceMemberDao;

    public WorkspaceDomainGuard(WorkspaceMemberDao workspaceMemberDao) {
        this.workspaceMemberDao = workspaceMemberDao;
    }

    public void requireWritableDomains(long workspaceId, long userId, String action, String actionKey,
                                      WorkspaceDomain... domains) {
        WorkspaceMemberDO member = workspaceMemberDao.findByWorkspaceAndUser(workspaceId, userId);
        if (member == null
                || !Integer.valueOf(0).equals(member.getStatus())
                || !Integer.valueOf(0).equals(member.getIsDeleted())) {
            return;
        }
        WorkspaceAccessLevel level;
        try {
            level = WorkspaceAccessLevel.valueOf(member.getAccessLevel());
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new com.aliyun.autowonder.common.error.BizException(
                    com.aliyun.autowonder.common.error.ErrorCode.WORKSPACE_ACCESS_LEVEL_INVALID);
        }
        if (level == WorkspaceAccessLevel.ADMIN) return;
        Set<WorkspaceDomain> readOnly = WorkspaceDomain.fromJson(member.getReadOnlyDomains());
        for (WorkspaceDomain domain : domains) {
            if (level == WorkspaceAccessLevel.READ_ONLY || readOnly.contains(domain)) {
                throw new WorkspaceDomainReadOnlyException(domain, action, actionKey);
            }
        }
    }
}
