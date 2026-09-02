package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDao;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDO;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;

@Service
public class DispatchMcpTokenService {
    static final String PREFIX = "awdispatch_";
    private static final String PURPOSE = "dispatch-mcp";
    private static final long TTL_SECONDS = 24 * 60 * 60;
    private static final Set<String> ACTIVE = Set.of("PACKAGING", "PENDING", "DISPATCHED", "ACKED", "RUNNING", "PAUSING");

    private final JwtService jwtService;
    private final DispatchDao dispatchDao;
    private final WorkitemDao workitemDao;
    private final WorkspaceMemberDao workspaceMemberDao;
    private final ScheduledTaskRunDao scheduledTaskRunDao;

    public DispatchMcpTokenService(JwtService jwtService, DispatchDao dispatchDao,
                                   WorkitemDao workitemDao, WorkspaceMemberDao workspaceMemberDao) {
        this(jwtService, dispatchDao, workitemDao, workspaceMemberDao, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DispatchMcpTokenService(JwtService jwtService, DispatchDao dispatchDao,
                                   WorkitemDao workitemDao, WorkspaceMemberDao workspaceMemberDao,
                                   ScheduledTaskRunDao scheduledTaskRunDao) {
        this.jwtService = jwtService;
        this.dispatchDao = dispatchDao;
        this.workitemDao = workitemDao;
        this.workspaceMemberDao = workspaceMemberDao;
        this.scheduledTaskRunDao = scheduledTaskRunDao;
    }

    public String issue(DispatchDO dispatch) {
        long userId = dispatch.getCreatorId() == null ? 0L : dispatch.getCreatorId();
        if (dispatch.executionSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN
                && scheduledTaskRunDao != null) {
            ScheduledTaskRunDO run = scheduledTaskRunDao.findById(dispatch.getTenantId(), dispatch.getWorkitemId());
            if (run != null && dispatch.getTenantId().equals(run.getWorkspaceId())
                    && run.getOwnerId() != null && run.getOwnerId() > 0) {
                userId = run.getOwnerId();
            }
        }
        if (userId <= 0 && workitemDao != null) {
            WorkitemDO workitem = workitemDao.findById(dispatch.getWorkitemId());
            if (workitem != null && dispatch.getTenantId().equals(workitem.getTenantId())) {
                if (workitem.getCreatorId() != null && workitem.getCreatorId() > 0) {
                    userId = workitem.getCreatorId();
                } else if (workitem.getAssignOperatorId() != null && workitem.getAssignOperatorId() > 0) {
                    userId = workitem.getAssignOperatorId();
                }
            }
        }
        if (userId <= 0) {
            throw new IllegalStateException("dispatch MCP principal is unavailable for dispatch " + dispatch.getId());
        }
        return PREFIX + jwtService.signScoped(userId, dispatch.getTenantId(), PURPOSE, dispatch.getId(), TTL_SECONDS);
    }

    public McpAccessTokenService.Principal authenticate(String token) {
        try {
            if (token == null || !token.startsWith(PREFIX)) {
                throw new IllegalArgumentException("invalid prefix");
            }
            Map<String, Object> claims = jwtService.parseScoped(token.substring(PREFIX.length()));
            if (!PURPOSE.equals(claims.get("purpose"))) {
                throw new IllegalArgumentException("invalid purpose");
            }
            long dispatchId = ((Number) claims.get("subjectId")).longValue();
            long workspaceId = ((Number) claims.get("workspace")).longValue();
            DispatchDO dispatch = dispatchDao.findById(dispatchId);
            if (dispatch == null || !workspaceIdEquals(dispatch, workspaceId) || !ACTIVE.contains(dispatch.getStatus())) {
                throw new IllegalArgumentException("dispatch is inactive");
            }
            long userId = ((Number) claims.get("uid")).longValue();
            WorkspaceAccessLevel level = resolveAccessLevel(workspaceId, userId);
            return new McpAccessTokenService.Principal(
                    workspaceId, userId, -dispatchId, level,
                    McpAccessTokenService.CredentialType.DISPATCH);
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }

    private boolean workspaceIdEquals(DispatchDO dispatch, long workspaceId) {
        return dispatch.getTenantId() != null && dispatch.getTenantId() == workspaceId;
    }

    private WorkspaceAccessLevel resolveAccessLevel(long workspaceId, long userId) {
        if (workspaceMemberDao == null) {
            return WorkspaceAccessLevel.READ_WRITE;
        }
        WorkspaceMemberDO member = workspaceMemberDao.findByWorkspaceAndUser(workspaceId, userId);
        if (member == null || member.getAccessLevel() == null) {
            return WorkspaceAccessLevel.READ_WRITE;
        }
        try {
            return WorkspaceAccessLevel.valueOf(member.getAccessLevel());
        } catch (IllegalArgumentException e) {
            return WorkspaceAccessLevel.READ_WRITE;
        }
    }
}
