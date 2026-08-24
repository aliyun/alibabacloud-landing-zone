package com.aliyun.autowonder.integration.receipt;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.integration.receipt.dto.ManualReceiptConfirmSucceededRequest;
import com.aliyun.autowonder.integration.receipt.dto.ManualReceiptRetryRequest;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/integrations/receipts")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "处理外部操作回执")
public class ExternalOperationReceiptAdminController {

    private final ExternalOperationReceiptAdminService adminService;

    public ExternalOperationReceiptAdminController(ExternalOperationReceiptAdminService adminService) {
        this.adminService = adminService;
    }

    @PostMapping("/{id}/retry")
    public Result<Void> retry(@PathVariable("id") Long id,
                              @RequestBody(required = false) ManualReceiptRetryRequest request) {
        adminService.manualRetry(id == null ? 0L : id, currentWorkspaceId(), currentUserId(),
                request == null ? null : request.getReason());
        return Result.ok(null);
    }

    @PostMapping("/{id}/confirm-succeeded")
    public Result<Void> confirmSucceeded(
            @PathVariable("id") Long id,
            @RequestBody(required = false) ManualReceiptConfirmSucceededRequest request) {
        adminService.manualConfirmSucceeded(id == null ? 0L : id, currentWorkspaceId(), currentUserId(),
                request == null ? null : request.getReason());
        return Result.ok(null);
    }

    private long currentUserId() {
        Long userId = AutoWonderContext.get().getUserId();
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return userId;
    }

    private long currentWorkspaceId() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return workspaceId;
    }
}
