package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.memory.store.dto.MemoryTopicRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Human topic operations; the existing runtime document protocol is unchanged. */
@RestController
@RequestMapping("/api/memory-stores")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "维护记忆")
public class MemoryTopicController {
    private final MemoryTopicService topics;

    public MemoryTopicController(MemoryTopicService topics) { this.topics = topics; }

    public record CreateTopic(String scope, long ownerRef, MemoryTopicRequest topic) {}
    public record DeleteTopic(String path, int expectedVersion, String idempotencyKey) {}

    @ExceptionHandler(MemoryDocumentService.MemoryConflictException.class)
    public ResponseEntity<Result<Void>> conflict(MemoryDocumentService.MemoryConflictException error) {
        return ResponseEntity.status(409).body(Result.fail(ErrorCode.CONFLICT.getCode(), "记忆已被修改，请刷新后重试"));
    }

    @ExceptionHandler(MemoryStoreApplicationService.MemoryAccessDeniedException.class)
    public ResponseEntity<Result<Void>> accessDenied() {
        return ResponseEntity.status(403).body(Result.fail(ErrorCode.NO_PERMISSION));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Result<Void>> invalid(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Result.fail(ErrorCode.PARAM_INVALID.getCode(), error.getMessage()));
    }

    @PostMapping("/topics")
    public Result<MemoryDocumentDO> create(@RequestBody CreateTopic request) {
        return Result.ok(topics.createForUser(tenantId(), userId(), administrator(),
                request.scope(), request.ownerRef(), request.topic()));
    }

    @PutMapping("/{storeId}/topics")
    public Result<MemoryDocumentDO> update(@PathVariable long storeId, @RequestBody MemoryTopicRequest request) {
        return Result.ok(topics.updateForUser(tenantId(), userId(), administrator(), storeId, request));
    }

    @DeleteMapping("/{storeId}/topics")
    public Result<Void> delete(@PathVariable long storeId, @RequestBody DeleteTopic request) {
        topics.deleteForUser(tenantId(), userId(), administrator(), storeId,
                request.path(), request.expectedVersion(), request.idempotencyKey());
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
