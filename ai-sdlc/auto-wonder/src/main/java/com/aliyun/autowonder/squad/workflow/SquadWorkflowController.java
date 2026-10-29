package com.aliyun.autowonder.squad.workflow;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.squad.workflow.dto.SquadWorkflowGenerationRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only graph access. Generating is an analysis of configuration the caller can already view,
 * so it uses the same workspace read permission instead of a broader write grant.
 */
@RestController
@RequestMapping("/api/squads/{id}/workflow")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看小队工作流",
        actionKey = "access.action.view_squad_workflow")
public class SquadWorkflowController {

    private final SquadWorkflowService workflowService;

    public SquadWorkflowController(SquadWorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @GetMapping
    public Result<SquadWorkflowView> current(@PathVariable("id") Long id) {
        return Result.ok(workflowService.getCurrent(currentWorkspaceId(), id));
    }

    @PostMapping("/generations")
    public Result<SquadWorkflowView> generate(@PathVariable("id") Long id,
            @RequestBody(required = false) SquadWorkflowGenerationRequest request) {
        long workspaceId = currentWorkspaceId();
        String expectedHash = request == null ? null : request.expectedInputHash();
        boolean retry = request != null && request.retryRequested();
        try {
            return Result.ok(workflowService.ensureGeneration(workspaceId, id, currentUserId(),
                    expectedHash, retry));
        } catch (SquadWorkflowStaleInputException e) {
            return Result.failKeyed(ErrorCode.SQUAD_WORKFLOW_STALE_INPUT.getCode(),
                    ErrorCode.SQUAD_WORKFLOW_STALE_INPUT.getMessageKey(), null,
                    ErrorCode.SQUAD_WORKFLOW_STALE_INPUT.getMessage(), e.getCurrent());
        }
    }

    @GetMapping("/generations/{generationId}")
    public Result<SquadWorkflowView> generation(@PathVariable("id") Long id,
            @PathVariable("generationId") Long generationId) {
        return Result.ok(workflowService.getGeneration(currentWorkspaceId(), id, generationId));
    }

    private long currentUserId() {
        Long uid = AutoWonderContext.get().getUserId();
        if (uid == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return uid;
    }

    private long currentWorkspaceId() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return workspaceId;
    }
}
