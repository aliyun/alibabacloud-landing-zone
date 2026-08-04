package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.executor.dto.CreateExecutorRequest;
import com.aliyun.autowonder.executor.dto.ExecutorVO;
import com.aliyun.autowonder.executor.dto.IssuedExecutorVO;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "查看执行器")
public class ExecutorController {

    private final ExecutorService executorService;

    public ExecutorController(ExecutorService executorService) {
        this.executorService = executorService;
    }

    @PostMapping("/agents/{agentId}/executors")
    @RequireOrgAccess(value = OrgAccessLevel.ADMIN, action = "创建执行器")
    public Result<IssuedExecutorVO> create(@PathVariable("agentId") Long agentId,
                                           @RequestBody CreateExecutorRequest req) {
        return Result.ok(executorService.create(agentId, req, currentOrgId(), currentUserId()));
    }

    @GetMapping("/agents/{agentId}/executors")
    public Result<List<ExecutorVO>> list(@PathVariable("agentId") Long agentId) {
        return Result.ok(executorService.listByAgent(agentId, currentOrgId()));
    }

    @GetMapping("/executors")
    public Result<List<ExecutorVO>> listAll() {
        return Result.ok(executorService.listAll(currentOrgId()));
    }

    @GetMapping("/executors/{id}/token")
    @RequireOrgAccess(value = OrgAccessLevel.ADMIN, action = "获取执行器令牌")
    public Result<String> getToken(@PathVariable("id") Long id) {
        return Result.ok(executorService.getToken(id, currentOrgId()));
    }

    @DeleteMapping("/executors/{id}")
    @RequireOrgAccess(value = OrgAccessLevel.ADMIN, action = "删除执行器")
    public Result<Void> delete(@PathVariable("id") Long id) {
        executorService.delete(id, currentOrgId(), currentUserId());
        return Result.ok(null);
    }

    private long currentUserId() {
        Long uid = AutoWonderContext.get().getUserId();
        if (uid == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return uid;
    }

    private long currentOrgId() {
        Long orgId = AutoWonderContext.get().getCurrentOrgId();
        if (orgId == null) {
            throw new BizException(ErrorCode.ORG_NOT_MEMBER);
        }
        return orgId;
    }
}
