package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/memory-imports")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "管理记忆导入")
public class MemoryImportAdminController {
    private final MemoryImportAdminService imports;

    public MemoryImportAdminController(MemoryImportAdminService imports) {
        this.imports = imports;
    }

    @GetMapping
    public Result<List<MemoryImportSourceDO>> listSources() {
        return Result.ok(imports.listSources(tenantId()));
    }

    @GetMapping("/{sourceId}/receipts")
    public Result<List<MemoryImportReceiptDO>> listReceipts(@PathVariable long sourceId) {
        return Result.ok(imports.listReceipts(tenantId(), sourceId));
    }

    @PutMapping("/{sourceId}/status")
    public Result<Void> changeStatus(@PathVariable long sourceId, @RequestBody StatusRequest request) {
        if (request == null) throw new IllegalArgumentException("status request is required");
        imports.changeStatus(tenantId(), sourceId, request.status(), request.version());
        return Result.ok(null);
    }

    private long tenantId() {
        Long value = AutoWonderContext.get().getCurrentWorkspaceId();
        if (value == null) throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        return value;
    }

    public record StatusRequest(String status, int version) {
    }
}
