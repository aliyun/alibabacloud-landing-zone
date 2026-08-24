package com.aliyun.autowonder.sdlc;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.sdlc.dto.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sdlcs")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看SDLC流程")
public class SdlcController {

    private final SdlcService sdlcService;

    public SdlcController(SdlcService sdlcService) {
        this.sdlcService = sdlcService;
    }

    @PostMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "创建SDLC流程")
    public Result<SdlcVO> create(@RequestBody CreateSdlcRequest req) {
        return Result.ok(sdlcService.create(req, currentWorkspaceId(), currentUserId()));
    }

    @GetMapping("/{id}")
    public Result<SdlcVO> get(@PathVariable("id") Long id) {
        return Result.ok(sdlcService.get(id));
    }

    @GetMapping
    public Result<List<SdlcVO>> list(
            @RequestParam(value = "workType", required = false) String workType,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return Result.ok(sdlcService.list(workType, status, page, size));
    }

    @PutMapping("/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "更新SDLC流程")
    public Result<SdlcVO> update(@PathVariable("id") Long id, @RequestBody UpdateSdlcRequest req) {
        return Result.ok(sdlcService.update(id, req, currentWorkspaceId(), currentUserId()));
    }

    @DeleteMapping("/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "删除SDLC流程")
    public Result<Void> delete(@PathVariable("id") Long id) {
        sdlcService.delete(id, currentWorkspaceId(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/{id}/steps")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "添加SDLC步骤")
    public Result<StepVO> addStep(@PathVariable("id") Long id, @RequestBody CreateStepRequest req) {
        return Result.ok(sdlcService.addStep(id, req, currentWorkspaceId(), currentUserId()));
    }

    @PutMapping("/{id}/steps/{stepId}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "更新SDLC步骤")
    public Result<StepVO> updateStep(@PathVariable("id") Long id,
                                     @PathVariable("stepId") Long stepId,
                                     @RequestBody UpdateStepRequest req) {
        return Result.ok(sdlcService.updateStep(id, stepId, req, currentWorkspaceId(), currentUserId()));
    }

    @DeleteMapping("/{id}/steps/{stepId}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "删除SDLC步骤")
    public Result<Void> deleteStep(@PathVariable("id") Long id, @PathVariable("stepId") Long stepId) {
        sdlcService.deleteStep(id, stepId, currentWorkspaceId(), currentUserId());
        return Result.ok(null);
    }

    @PutMapping("/{id}/steps/reorder")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "重排SDLC步骤")
    public Result<Void> reorder(@PathVariable("id") Long id, @RequestBody ReorderRequest req) {
        sdlcService.reorderSteps(id, req, currentWorkspaceId(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/{id}/enable")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "启用SDLC流程")
    public Result<SdlcVO> enable(@PathVariable("id") Long id,
                                 @RequestParam(value = "statusTemplateId", required = false) Long statusTemplateId) {
        return Result.ok(sdlcService.enable(id, statusTemplateId, currentWorkspaceId(), currentUserId()));
    }

    @PostMapping("/{id}/disable")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "停用SDLC流程")
    public Result<Void> disable(@PathVariable("id") Long id) {
        sdlcService.disable(id, currentWorkspaceId(), currentUserId());
        return Result.ok(null);
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
