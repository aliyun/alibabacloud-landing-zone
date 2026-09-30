package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.memory.store.dto.MemoryMutationRequest;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/memory-stores")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看记忆文档")
public class MemoryStoreController {
    private final MemoryStoreApplicationService memories;

    public MemoryStoreController(MemoryStoreApplicationService memories) {
        this.memories = memories;
    }

    @GetMapping
    public Result<List<MemoryStoreDO>> list() {
        return Result.ok(memories.listForUser(tenantId(), userId(), administrator()));
    }

    @PostMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "创建记忆存储")
    public Result<MemoryStoreDO> create(@RequestBody MemoryStoreDO store) {
        return Result.ok(memories.createStore(tenantId(), userId(), administrator(), store));
    }

    @DeleteMapping("/{storeId}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "归档记忆存储")
    public Result<Void> archive(@PathVariable long storeId, @RequestParam int version) {
        memories.archiveStore(tenantId(), userId(), administrator(), storeId, version);
        return Result.ok(null);
    }

    @GetMapping("/{storeId}/documents")
    public Result<List<MemoryDocumentDO>> documents(@PathVariable long storeId) {
        var documents = memories.documentsForUser(tenantId(), userId(), administrator(), storeId);
        documents.forEach(MemoryDirectoryService::describe);
        return Result.ok(documents);
    }

    @PostMapping("/{storeId}/mutations")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "维护记忆文档")
    public Result<MemoryDocumentDO> mutate(@PathVariable long storeId, @RequestBody MemoryMutationRequest request) {
        return Result.ok(memories.mutateForUser(tenantId(), userId(), administrator(), storeId, request));
    }

    @GetMapping("/{storeId}/history")
    public Result<List<MemoryChangeDO>> history(@PathVariable long storeId,
                                               @RequestParam(defaultValue = "0") long afterRevision,
                                               @RequestParam(defaultValue = "100") int limit) {
        return Result.ok(memories.historyForUser(tenantId(), userId(), administrator(), storeId,
                afterRevision, limit));
    }

    @PutMapping("/{storeId}/acls")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "管理记忆权限")
    public Result<Void> putAcl(@PathVariable long storeId, @RequestBody MemoryStoreAclDO acl) {
        memories.putAcl(tenantId(), userId(), administrator(), storeId, acl);
        return Result.ok(null);
    }

    @GetMapping("/{storeId}/acls")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "查看记忆权限")
    public Result<List<MemoryStoreAclDO>> acls(@PathVariable long storeId) {
        return Result.ok(memories.aclsForUser(tenantId(), userId(), administrator(), storeId));
    }

    @DeleteMapping("/{storeId}/acls/{aclId}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "管理记忆权限")
    public Result<Void> deleteAcl(@PathVariable long storeId, @PathVariable long aclId) {
        memories.deleteAcl(tenantId(), userId(), administrator(), storeId, aclId);
        return Result.ok(null);
    }

    private long tenantId() {
        Long value = AutoWonderContext.get().getCurrentWorkspaceId();
        if (value == null) throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        return value;
    }

    private long userId() {
        Long value = AutoWonderContext.get().getUserId();
        if (value == null) throw new BizException(ErrorCode.UNAUTHORIZED);
        return value;
    }

    private boolean administrator() {
        return AutoWonderContext.get().getWorkspaceAccessLevel() == WorkspaceAccessLevel.ADMIN;
    }
}
