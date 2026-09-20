package com.aliyun.autowonder.environment;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/environment-variables")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看环境变量")
public class EnvironmentVariableController {
    private final EnvironmentVariableService service;

    public EnvironmentVariableController(EnvironmentVariableService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<EnvironmentVariableVO>> list() {
        return Result.ok(service.list(currentWorkspaceId()));
    }

    @GetMapping("/{id}/value")
    public ResponseEntity<Result<EnvironmentVariableValueVO>> reveal(@PathVariable("id") Long id) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(Result.ok(service.reveal(currentWorkspaceId(), currentUserId(), id)));
    }

    @PostMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "创建环境变量")
    public Result<EnvironmentVariableVO> create(@RequestBody CreateEnvironmentVariableRequest request) {
        return Result.ok(service.create(currentWorkspaceId(), currentUserId(), request));
    }

    @PutMapping("/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "更新环境变量")
    public Result<EnvironmentVariableVO> update(@PathVariable("id") Long id,
                                                @Valid @RequestBody UpdateEnvironmentVariableRequest request) {
        return Result.ok(service.update(currentWorkspaceId(), currentUserId(), id, request));
    }

    @DeleteMapping("/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "删除环境变量")
    public Result<Void> delete(@PathVariable("id") Long id) {
        service.delete(currentWorkspaceId(), currentUserId(), id);
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
