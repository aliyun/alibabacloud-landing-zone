package com.aliyun.autowonder.statemachine;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import com.aliyun.autowonder.statemachine.dto.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/status-templates")
@RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "查看状态模板")
public class StatusTemplateController {

    private final StatusTemplateService service;

    public StatusTemplateController(StatusTemplateService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<TemplateVO>> list(@RequestParam("workType") String workType) {
        return Result.ok(service.listTemplates(currentOrgId(), workType));
    }

    @GetMapping("/{id}")
    public Result<TemplateDetailVO> get(@PathVariable("id") Long id) {
        return Result.ok(service.getTemplateDetail(id));
    }

    @PostMapping
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "创建状态模板")
    public Result<TemplateVO> create(@RequestBody CreateTemplateRequest req) {
        return Result.ok(service.createTemplate(req, currentOrgId(), currentUserId()));
    }

    @PutMapping("/{id}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "更新状态模板")
    public Result<TemplateVO> update(@PathVariable("id") Long id, @RequestBody UpdateTemplateRequest req) {
        return Result.ok(service.updateTemplate(id, req, currentOrgId(), currentUserId()));
    }

    @DeleteMapping("/{id}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "删除状态模板")
    public Result<Void> delete(@PathVariable("id") Long id) {
        service.deleteTemplate(id, currentOrgId());
        return Result.ok(null);
    }

    // --- Nodes ---

    @GetMapping("/{id}/nodes")
    public Result<List<NodeVO>> listNodes(@PathVariable("id") Long id) {
        return Result.ok(service.listNodes(id));
    }

    @PostMapping("/{id}/nodes")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "创建状态节点")
    public Result<NodeVO> createNode(@PathVariable("id") Long id, @RequestBody CreateNodeRequest req) {
        return Result.ok(service.createNode(id, req, currentOrgId()));
    }

    @PutMapping("/{id}/nodes/{nodeId}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "更新状态节点")
    public Result<NodeVO> updateNode(@PathVariable("id") Long id, @PathVariable("nodeId") Long nodeId,
                                     @RequestBody UpdateNodeRequest req) {
        return Result.ok(service.updateNode(nodeId, req));
    }

    @DeleteMapping("/{id}/nodes/{nodeId}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "删除状态节点")
    public Result<Void> deleteNode(@PathVariable("id") Long id, @PathVariable("nodeId") Long nodeId) {
        service.deleteNode(nodeId);
        return Result.ok(null);
    }

    // --- Transitions ---

    @GetMapping("/{id}/transitions")
    public Result<List<TransitionVO>> listTransitions(@PathVariable("id") Long id) {
        return Result.ok(service.listTransitions(id));
    }

    @PostMapping("/{id}/transitions")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "创建状态流转")
    public Result<TransitionVO> createTransition(@PathVariable("id") Long id,
                                                  @RequestBody CreateTransitionRequest req) {
        return Result.ok(service.createTransition(id, req, currentOrgId()));
    }

    @PutMapping("/{id}/transitions/{tid}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "更新状态流转")
    public Result<TransitionVO> updateTransition(@PathVariable("id") Long id, @PathVariable("tid") Long tid,
                                                  @RequestBody UpdateTransitionRequest req) {
        return Result.ok(service.updateTransition(tid, req));
    }

    @DeleteMapping("/{id}/transitions/{tid}")
    @RequireOrgAccess(value = OrgAccessLevel.READ_WRITE, action = "删除状态流转")
    public Result<Void> deleteTransition(@PathVariable("id") Long id, @PathVariable("tid") Long tid) {
        service.deleteTransition(tid);
        return Result.ok(null);
    }

    private long currentUserId() {
        Long uid = AutoWonderContext.get().getUserId();
        if (uid == null) throw new BizException(ErrorCode.UNAUTHORIZED);
        return uid;
    }

    private long currentOrgId() {
        Long orgId = AutoWonderContext.get().getCurrentOrgId();
        if (orgId == null) throw new BizException(ErrorCode.ORG_NOT_MEMBER);
        return orgId;
    }
}
