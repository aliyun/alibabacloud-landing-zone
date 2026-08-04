package com.aliyun.autowonder.sdlc;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import com.aliyun.autowonder.sdlc.dto.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/sdlcs")
@RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "查看SDLC流程")
public class SdlcController {

    private final SdlcService sdlcService;

    public SdlcController(SdlcService sdlcService) {
        this.sdlcService = sdlcService;
    }

    @PostMapping
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "创建SDLC流程")
    public Result<SdlcVO> create(@RequestBody CreateSdlcRequest req) {
        return Result.ok(sdlcService.create(req, currentOrgId(), currentUserId()));
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
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "更新SDLC流程")
    public Result<SdlcVO> update(@PathVariable("id") Long id, @RequestBody UpdateSdlcRequest req) {
        return Result.ok(sdlcService.update(id, req, currentOrgId(), currentUserId()));
    }

    @DeleteMapping("/{id}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "删除SDLC流程")
    public Result<Void> delete(@PathVariable("id") Long id) {
        sdlcService.delete(id, currentOrgId(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/{id}/steps")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "添加SDLC步骤")
    public Result<StepVO> addStep(@PathVariable("id") Long id, @RequestBody CreateStepRequest req) {
        return Result.ok(sdlcService.addStep(id, req, currentOrgId(), currentUserId()));
    }

    @PutMapping("/{id}/steps/{stepId}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "更新SDLC步骤")
    public Result<StepVO> updateStep(@PathVariable("id") Long id,
                                     @PathVariable("stepId") Long stepId,
                                     @RequestBody UpdateStepRequest req) {
        return Result.ok(sdlcService.updateStep(id, stepId, req, currentOrgId(), currentUserId()));
    }

    @DeleteMapping("/{id}/steps/{stepId}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "删除SDLC步骤")
    public Result<Void> deleteStep(@PathVariable("id") Long id, @PathVariable("stepId") Long stepId) {
        sdlcService.deleteStep(id, stepId, currentOrgId(), currentUserId());
        return Result.ok(null);
    }

    @PutMapping("/{id}/steps/reorder")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "重排SDLC步骤")
    public Result<Void> reorder(@PathVariable("id") Long id, @RequestBody ReorderRequest req) {
        sdlcService.reorderSteps(id, req, currentOrgId(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/{id}/enable")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "启用SDLC流程")
    public Result<SdlcVO> enable(@PathVariable("id") Long id,
                                 @RequestParam(value = "statusTemplateId", required = false) Long statusTemplateId) {
        return Result.ok(sdlcService.enable(id, statusTemplateId, currentOrgId(), currentUserId()));
    }

    @PostMapping("/{id}/disable")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "停用SDLC流程")
    public Result<Void> disable(@PathVariable("id") Long id) {
        sdlcService.disable(id, currentOrgId(), currentUserId());
        return Result.ok(null);
    }

    private long currentUserId() {
        Long uid = AutoWonderContext.get().getUserId();
        if (uid == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return uid;
    }

    private long currentOrgId() {
        Long orgId = AutoWonderContext.get().getCurrentOrgId();
        if (orgId == null) {
            throw new BizException(ErrorCode.ORG_NOT_MEMBER);
        }
        return orgId;
    }
}
