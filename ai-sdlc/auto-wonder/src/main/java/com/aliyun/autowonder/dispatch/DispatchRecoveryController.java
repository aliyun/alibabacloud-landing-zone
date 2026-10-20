package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.websocket.PresenceManager;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@RestController
@RequestMapping("/api/workitems")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看调度恢复", actionKey = "access.action.view_dispatch_recovery")
public class DispatchRecoveryController {

    @org.springframework.beans.factory.annotation.Autowired
    private DispatchRecoveryService recoveryService;

    @GetMapping("/{workitemId}/recovery")
    public Result<Map<String, Object>> recovery(@PathVariable long workitemId) {
        return Result.ok(recoveryService.state(currentWorkspaceId(), workitemId));
    }

    @PostMapping("/{workitemId}/recovery")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "恢复或关闭交付", actionKey = "access.action.recover_or_close_delivery")
    public Result<Map<String, Object>> control(@PathVariable long workitemId, @RequestBody ControlRequest request) {
        if (request.action() == null) throw BizException.keyed(ErrorCode.CONFLICT, "dispatch.recovery.action_required", new Object[0], "action required");
        long tenantId = currentWorkspaceId(), userId = currentUserId();
        return Result.ok(switch (request.action()) {
            case "close" -> recoveryService.close(tenantId, workitemId, userId, request.force());
            case "reopen" -> recoveryService.reopen(tenantId, workitemId, userId);
            case "cancel" -> recoveryService.cancel(tenantId, workitemId, request.dispatchId(), userId, request.force());
            default -> throw BizException.keyed(ErrorCode.CONFLICT, "dispatch.recovery.unsupported", new Object[0], "不支持的恢复操作");
        });
    }
    public record ControlRequest(String action, long dispatchId, boolean force) {}

    private final DispatchService dispatchService;
    private final PresenceManager presenceManager;
    private final DispatchPauseService pauseService;

    public DispatchRecoveryController(DispatchService dispatchService, PresenceManager presenceManager,
            DispatchPauseService pauseService) {
        this.dispatchService = dispatchService;
        this.presenceManager = presenceManager;
        this.pauseService = pauseService;
    }

    @PostMapping("/{workitemId}/dispatches/{dispatchId}/pause")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "暂停调度", actionKey = "access.action.pause_a_dispatch")
    public Result<Map<String, Object>> pauseDispatch(@PathVariable long workitemId,
            @PathVariable long dispatchId) {
        DispatchDO dispatch = pauseService.requestPause(currentWorkspaceId(), workitemId,
                dispatchId, currentUserId());
        return Result.ok(Map.of(
                "dispatchId", dispatch.getId(),
                "status", dispatch.getStatus()));
    }

    @PostMapping("/{workitemId}/dispatches/{dispatchId}/continue")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "继续调度", actionKey = "access.action.resume_a_dispatch")
    public Result<Map<String, Object>> continueDispatch(@PathVariable long workitemId,
            @PathVariable long dispatchId) {
        long tenantId = currentWorkspaceId();
        DispatchDO source = dispatchService.loadForTenant(dispatchId);
        if (source.getTenantId() != tenantId
                || source.executionSourceType() != ExecutionSourceType.WORKITEM
                || source.getWorkitemId() != workitemId) {
            throw new BizException(ErrorCode.DISPATCH_NOT_FOUND);
        }
        if (!DispatchStatus.isTerminal(source.getStatus())
                && !DispatchStatus.PAUSED.equals(source.getStatus())
                && source.getExecutorId() != null
                && presenceManager.isExecutorOnline(source.getExecutorId())) {
            throw BizException.keyed(ErrorCode.CONFLICT, "dispatch.recovery.executor_online", new Object[0], "原执行器仍在线，不能创建并行恢复执行");
        }
        DispatchDO created = dispatchService.continueDispatch(tenantId, workitemId,
                dispatchId, currentUserId());
        return Result.ok(Map.of(
                "dispatchId", created.getId(),
                "attempt", created.getAttempt(),
                "status", created.getStatus()));
    }

    private long currentWorkspaceId() {
        Long value = AutoWonderContext.get().getCurrentWorkspaceId();
        if (value == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return value;
    }

    private long currentUserId() {
        Long value = AutoWonderContext.get().getUserId();
        if (value == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return value;
    }
}
