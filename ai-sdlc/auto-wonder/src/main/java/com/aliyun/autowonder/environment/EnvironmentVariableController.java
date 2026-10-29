package com.aliyun.autowonder.environment;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;
import java.util.List;

@RestController
@RequestMapping("/api/environment-variables")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看环境变量", actionKey = "access.action.view_environment_variables")
public class EnvironmentVariableController {
    private final EnvironmentVariableService service;

    public EnvironmentVariableController(EnvironmentVariableService service) {
        this.service = service;
    }

    @GetMapping
    public Result<List<EnvironmentVariableVO>> list() {
        return Result.ok(service.list(currentWorkspaceId()));
    }

    @PostMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "创建环境变量", actionKey = "access.action.create_an_environment_variable")
    public Result<EnvironmentVariableVO> create(@RequestBody CreateEnvironmentVariableRequest request) {
        return Result.ok(service.create(currentWorkspaceId(), currentUserId(), request));
    }

    @PutMapping("/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "更新环境变量", actionKey = "access.action.update_an_environment_variable")
    public Result<EnvironmentVariableVO> update(@PathVariable("id") Long id,
                                                @Valid @RequestBody UpdateEnvironmentVariableRequest request) {
        return Result.ok(service.update(currentWorkspaceId(), currentUserId(), id, request));
    }

    @DeleteMapping("/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "删除环境变量", actionKey = "access.action.delete_an_environment_variable")
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
