package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.executor.dto.CreateExecutorRequest;
import com.aliyun.autowonder.executor.dto.ExecutorLaunchCommandRequest;
import com.aliyun.autowonder.executor.dto.ExecutorLaunchCommandVO;
import com.aliyun.autowonder.executor.dto.ExecutorLaunchConfigVO;
import com.aliyun.autowonder.executor.dto.ExecutorVO;
import com.aliyun.autowonder.executor.dto.IssuedExecutorVO;
import com.aliyun.autowonder.executor.dto.ProviderModelCatalogVO;
import com.aliyun.autowonder.executor.dto.ExecutorUpdateAllResultVO;
import com.aliyun.autowonder.executor.dto.ExecutorUpdateVO;
import com.aliyun.autowonder.executor.dto.UpdateExecutorLaunchConfigRequest;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看执行器")
public class ExecutorController {

    private ExecutorRestartService restartService;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRestartService(ExecutorRestartService service) { this.restartService = service; }

    private ExecutorUpdateService updateService;
    @org.springframework.beans.factory.annotation.Autowired
    public void setUpdateService(ExecutorUpdateService service) { this.updateService = service; }

    @PostMapping("/executors/{id}/restart")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "重启执行器")
    public Result<com.alibaba.fastjson.JSONObject> restart(@PathVariable("id") Long id,
            @RequestBody(required = false) RestartRequest request) {
        return Result.ok(restartService.request(id, currentWorkspaceId(), currentUserId(), request != null && request.update()));
    }
    public record RestartRequest(boolean update) {}

    @PostMapping("/executors/{id}/update")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "升级执行器")
    public Result<ExecutorUpdateVO> update(@PathVariable("id") Long id) {
        return Result.ok(updateService.updateOne(id, currentWorkspaceId(), currentUserId()));
    }

    /**
     * 一键全量更新. Honours the same {@code squadIds} filter as {@link #listAll} so "全量" means every
     * executor the operator can currently see, and reports skips instead of failing the whole batch.
     */
    @PostMapping("/executors/update-all")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "批量升级执行器")
    public Result<ExecutorUpdateAllResultVO> updateAll(
            @RequestParam(value = "squadIds", required = false) List<Long> squadIds) {
        return Result.ok(updateService.updateAll(currentWorkspaceId(), squadIds, currentUserId()));
    }

    private final ExecutorService executorService;
    private final ProviderModelCatalogService providerModelCatalogService;
    private final ExecutorLaunchConfigService executorLaunchConfigService;
    private final ExecutorLaunchCommandService executorLaunchCommandService;

    public ExecutorController(ExecutorService executorService, ProviderModelCatalogService providerModelCatalogService,
                              ExecutorLaunchConfigService executorLaunchConfigService,
                              ExecutorLaunchCommandService executorLaunchCommandService) {
        this.executorService = executorService;
        this.providerModelCatalogService = providerModelCatalogService;
        this.executorLaunchConfigService = executorLaunchConfigService;
        this.executorLaunchCommandService = executorLaunchCommandService;
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
    public Result<List<ExecutorVO>> listAll(
            @RequestParam(value = "squadIds", required = false) List<Long> squadIds) {
        return Result.ok(executorService.listAll(currentWorkspaceId(), squadIds));
    }

    @GetMapping("/executor-model-catalogs/{provider}")
    public Result<ProviderModelCatalogVO> getModelCatalog(@PathVariable String provider) {
        return Result.ok(providerModelCatalogService.read(provider));
    }

    @GetMapping("/executors/{id}/token")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "获取执行器令牌")
    public Result<String> getToken(@PathVariable("id") Long id) {
        return Result.ok(executorService.getToken(id, currentWorkspaceId()));
    }

    @GetMapping("/executors/{id}/launch-config")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "查看执行器启动配置")
    public Result<ExecutorLaunchConfigVO> getLaunchConfig(@PathVariable("id") Long id) {
        return Result.ok(executorLaunchConfigService.getConfig(id, currentWorkspaceId(), currentUserId()));
    }

    @PutMapping("/executors/{id}/launch-config")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "更新执行器启动配置")
    public Result<ExecutorLaunchConfigVO> updateLaunchConfig(@PathVariable("id") Long id,
                                                             @RequestBody UpdateExecutorLaunchConfigRequest req) {
        return Result.ok(executorLaunchConfigService.updateConfig(id, currentWorkspaceId(), req, currentUserId()));
    }

    /**
     * Generates the startup command from the persisted config. Only the output format is per-request, so the page
     * copies exactly what autowonder.build_executor_launch_command returns for the same executor.
     */
    @PostMapping("/executors/{id}/launch-command")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "生成执行器启动命令")
    public Result<ExecutorLaunchCommandVO> buildLaunchCommand(@PathVariable("id") Long id,
                                                             @RequestBody(required = false)
                                                             ExecutorLaunchCommandRequest req) {
        ExecutorLaunchCommandRequest request = req == null ? new ExecutorLaunchCommandRequest() : req;
        return Result.ok(executorLaunchCommandService.buildForExecutor(id, currentWorkspaceId(), request.getOs(),
                Boolean.TRUE.equals(request.getDebug()), request.getShell()));
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
