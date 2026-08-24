package com.aliyun.autowonder.template;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.template.dto.ApplyResultVO;
import com.aliyun.autowonder.template.dto.SquadTemplateDetailVO;
import com.aliyun.autowonder.template.dto.SquadTemplateVO;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/squad-templates")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看小队模板")
public class SquadTemplateController {

    private final SquadTemplateService templateService;

    public SquadTemplateController(SquadTemplateService templateService) {
        this.templateService = templateService;
    }

    @GetMapping
    public Result<List<SquadTemplateVO>> list() {
        return Result.ok(templateService.list(currentWorkspaceId()));
    }

    @GetMapping("/{id}")
    public Result<SquadTemplateDetailVO> getDetail(@PathVariable("id") Long id) {
        return Result.ok(templateService.getDetail(id));
    }

    @PostMapping("/{id}/apply")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "应用小队模板")
    public Result<ApplyResultVO> apply(@PathVariable("id") Long id) {
        return Result.ok(templateService.apply(id, currentWorkspaceId(), currentUserId()));
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
