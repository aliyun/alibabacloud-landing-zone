package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.workspace.WorkspaceDO;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workitem.dto.WorkitemOwningWorkspaceVO;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 当前项目查不到工单时解析工单归属（单 55699）。
 *
 * <p>归属解析必须经服务端权限校验：只有工单确实存在、其所属项目可用、且当前用户仍是该项目
 * 在职成员时才返回项目信息；其余情况一律返回空，调用方继续按「工单不存在」处理，避免用全局
 * 工单 id 探测无权访问的项目或工单。
 */
@Component
public class WorkitemOwningWorkspaceResolver {

    private final WorkitemDao workitemDao;
    private final WorkspaceDao workspaceDao;
    private final WorkspaceMemberDao workspaceMemberDao;

    public WorkitemOwningWorkspaceResolver(WorkitemDao workitemDao, WorkspaceDao workspaceDao,
                                           WorkspaceMemberDao workspaceMemberDao) {
        this.workitemDao = workitemDao;
        this.workspaceDao = workspaceDao;
        this.workspaceMemberDao = workspaceMemberDao;
    }

    public Optional<WorkitemOwningWorkspaceVO> resolve(long workitemId, long currentUserId,
                                                       long currentWorkspaceId) {
        Long owningWorkspaceId = workitemDao.findTenantIdById(workitemId);
        if (owningWorkspaceId == null || owningWorkspaceId == currentWorkspaceId) {
            return Optional.empty();
        }
        if (workspaceDao.countUsable(owningWorkspaceId) == 0) {
            return Optional.empty();
        }
        if (!isActiveMember(workspaceMemberDao.findByWorkspaceAndUser(owningWorkspaceId, currentUserId))) {
            return Optional.empty();
        }
        WorkspaceDO workspace = workspaceDao.findById(owningWorkspaceId);
        if (workspace == null || workspace.getName() == null) {
            return Optional.empty();
        }
        WorkitemOwningWorkspaceVO owning = new WorkitemOwningWorkspaceVO();
        owning.setWorkspaceId(owningWorkspaceId);
        owning.setWorkspaceName(workspace.getName());
        return Optional.of(owning);
    }

    /** 与 AuthFilter 的在职成员判定保持一致：未删除且 status=0。 */
    private static boolean isActiveMember(WorkspaceMemberDO member) {
        return member != null
                && Integer.valueOf(0).equals(member.getIsDeleted())
                && Integer.valueOf(0).equals(member.getStatus());
    }
}
