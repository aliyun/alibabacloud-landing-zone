package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.executor.dto.CreateExecutorRequest;
import com.aliyun.autowonder.executor.dto.ExecutorVO;
import com.aliyun.autowonder.executor.dto.IssuedExecutorVO;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看执行器")
public class ExecutorController {

    private final ExecutorService executorService;

    public ExecutorController(ExecutorService executorService) {
        this.executorService = executorService;
    }

    @PostMapping("/agents/{agentId}/executors")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "创建执行器")
    public Result<IssuedExecutorVO> create(@PathVariable("agentId") Long agentId,
                                           @RequestBody CreateExecutorRequest req) {
        return Result.ok(executorService.create(agentId, req, currentWorkspaceId(), currentUserId()));
    }

    @GetMapping("/agents/{agentId}/executors")
    public Result<List<ExecutorVO>> list(@PathVariable("agentId") Long agentId) {
        return Result.ok(executorService.listByAgent(agentId, currentWorkspaceId()));
    }

    @GetMapping("/executors")
    public Result<List<ExecutorVO>> listAll() {
        return Result.ok(executorService.listAll(currentWorkspaceId()));
    }

    @GetMapping("/executors/{id}/token")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "获取执行器令牌")
    public Result<String> getToken(@PathVariable("id") Long id) {
        return Result.ok(executorService.getToken(id, currentWorkspaceId()));
    }

    @DeleteMapping("/executors/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "删除执行器")
    public Result<Void> delete(@PathVariable("id") Long id) {
        executorService.delete(id, currentWorkspaceId(), currentUserId());
        return Result.ok(null);
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
