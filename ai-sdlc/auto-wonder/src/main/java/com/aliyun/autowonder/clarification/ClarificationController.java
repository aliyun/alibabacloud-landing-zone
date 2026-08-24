package com.aliyun.autowonder.clarification;

import com.aliyun.autowonder.clarification.dto.ClarificationVO;
import com.aliyun.autowonder.clarification.dto.PutClarificationRequest;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workitems/{workitemId}/clarification")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看澄清信息")
public class ClarificationController {

    private final ClarificationService clarificationService;

    public ClarificationController(ClarificationService clarificationService) {
        this.clarificationService = clarificationService;
    }

    @GetMapping
    public Result<ClarificationVO> get(@PathVariable("workitemId") Long workitemId) {
        return Result.ok(clarificationService.get(workitemId));
    }

    @PutMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "更新澄清信息")
    public Result<ClarificationVO> put(@PathVariable("workitemId") Long workitemId,
                                       @RequestBody PutClarificationRequest req) {
        return Result.ok(clarificationService.put(workitemId, req.getContentMd(),
                currentWorkspaceId(), currentUserId()));
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
