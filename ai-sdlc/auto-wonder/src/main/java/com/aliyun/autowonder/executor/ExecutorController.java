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
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看执行器", actionKey = "access.action.view_executors")
public class ExecutorController {

    private ExecutorRestartService restartService;
    @org.springframework.beans.factory.annotation.Autowired
    public void setRestartService(ExecutorRestartService service) { this.restartService = service; }

    private ExecutorUpdateService updateService;
    @org.springframework.beans.factory.annotation.Autowired
    public void setUpdateService(ExecutorUpdateService service) { this.updateService = service; }

    @PostMapping("/executors/{id}/restart")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "重启执行器", actionKey = "access.action.restart_an_executor")
    public Result<com.aliyun.autowonder.json.JSONObject> restart(@PathVariable("id") Long id,
            @RequestBody(required = false) RestartRequest request) {
        return Result.ok(restartService.request(id, currentWorkspaceId(), currentUserId(), request != null && request.update()));
    }
    public record RestartRequest(boolean update) {}

    @PostMapping("/executors/{id}/update")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "升级执行器", actionKey = "access.action.upgrade_an_executor")
    public Result<ExecutorUpdateVO> update(@PathVariable("id") Long id) {
        return Result.ok(updateService.updateOne(id, currentWorkspaceId(), currentUserId()));
    }

    /**
     * 一键全量更新. Honours the same {@code squadIds} filter as {@link #listAll} so "全量" means every
     * executor the operator can currently see, and reports skips instead of failing the whole batch.
     */
    @PostMapping("/executors/update-all")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "批量升级执行器", actionKey = "access.action.upgrade_executors_in_bulk")
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
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "创建执行器", actionKey = "access.action.create_an_executor")
    public Result<IssuedExecutorVO> create(@PathVariable("agentId") Long agentId,
                                           @RequestBody CreateExecutorRequest req) {
        IssuedExecutorVO issued = executorService.create(agentId, req, currentWorkspaceId(), currentUserId());
        // 令牌等同机器凭据：非管理员创建时只返回执行器记录，凭据由管理员经令牌接口单独获取。
        if (AutoWonderContext.get().getWorkspaceAccessLevel() != WorkspaceAccessLevel.ADMIN) {
            issued.setToken(null);
        }
        return Result.ok(issued);
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
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "获取执行器令牌", actionKey = "access.action.get_an_executor_token")
    public Result<String> getToken(@PathVariable("id") Long id) {
        return Result.ok(executorService.getToken(id, currentWorkspaceId()));
    }

    @GetMapping("/executors/{id}/launch-config")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看执行器启动配置", actionKey = "access.action.view_executor_launch_configuration")
    public Result<ExecutorLaunchConfigVO> getLaunchConfig(@PathVariable("id") Long id) {
        // 读取不得写库：沿用无副作用的存储视图，过期模型由页面提示、保存时经写路径修复。
        return Result.ok(executorLaunchConfigService.readStoredConfig(id, currentWorkspaceId()));
    }

    @GetMapping("/executors/{id}/model-upgrade-policy")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看模型升级策略", actionKey = "access.action.view_model_upgrade_policy")
    public Result<ExecutorLaunchConfigVO> getModelUpgradePolicy(@PathVariable("id") Long id) {
        return Result.ok(executorLaunchConfigService.readStoredConfig(id, currentWorkspaceId()));
    }

    @PutMapping("/executors/{id}/model-upgrade-policy")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "更新模型升级策略", actionKey = "access.action.update_model_upgrade_policy")
    public Result<ExecutorLaunchConfigVO> updateModelUpgradePolicy(@PathVariable("id") Long id,
            @RequestBody UpdateExecutorLaunchConfigRequest request) {
        return Result.ok(executorLaunchConfigService.updateModelUpgradePolicy(id, currentWorkspaceId(), request, currentUserId()));
    }

    @PutMapping("/executors/{id}/launch-config")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "更新执行器启动配置", actionKey = "access.action.update_executor_launch_configuration")
    public Result<ExecutorLaunchConfigVO> updateLaunchConfig(@PathVariable("id") Long id,
                                                             @RequestBody UpdateExecutorLaunchConfigRequest req) {
        return Result.ok(executorLaunchConfigService.updateConfig(id, currentWorkspaceId(), req, currentUserId()));
    }

    /**
     * Generates the startup command from the persisted config. Only the output format is per-request, so the page
     * copies exactly what autowonder.build_executor_launch_command returns for the same executor.
     */
    @PostMapping("/executors/{id}/launch-command")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "生成执行器启动命令", actionKey = "access.action.generate_an_executor_launch_command")
    public Result<ExecutorLaunchCommandVO> buildLaunchCommand(@PathVariable("id") Long id,
                                                             @RequestBody(required = false)
                                                             ExecutorLaunchCommandRequest req) {
        ExecutorLaunchCommandRequest request = req == null ? new ExecutorLaunchCommandRequest() : req;
        return Result.ok(executorLaunchCommandService.buildForExecutor(id, currentWorkspaceId(), request.getOs(),
                Boolean.TRUE.equals(request.getDebug()), request.getShell()));
    }

    @PutMapping("/executors/{id}/launch-command")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "更新执行器启动命令", actionKey = "access.action.update_executor_launch_command")
    public Result<ExecutorLaunchConfigVO> saveLaunchCommand(@PathVariable("id") Long id,
            @RequestBody com.aliyun.autowonder.executor.dto.UpdateExecutorLaunchCommandRequest request) {
        return Result.ok(executorLaunchCommandService.saveCommand(id, currentWorkspaceId(), currentUserId(), request));
    }

    @PostMapping("/executors/{id}/launch-command/preview")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "生成执行器启动命令", actionKey = "access.action.generate_an_executor_launch_command")
    public Result<ExecutorLaunchCommandVO> previewLaunchCommand(@PathVariable("id") Long id,
            @RequestBody com.aliyun.autowonder.executor.dto.ExecutorLaunchCommandPreviewRequest request) {
        return Result.ok(executorLaunchCommandService.previewForExecutor(id, currentWorkspaceId(), request));
    }

    @PostMapping("/executors/{id}/launch-command/apply")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "在线应用执行器启动命令", actionKey = "access.action.apply_executor_launch_command")
    public Result<com.aliyun.autowonder.json.JSONObject> applyLaunchCommand(@PathVariable("id") Long id,
            @RequestBody com.aliyun.autowonder.executor.dto.UpdateExecutorLaunchCommandRequest request) {
        return Result.ok(restartService.applyLaunchCommand(id, currentWorkspaceId(), currentUserId(), request.getVersion(), request.getRestartMode()));
    }

    @DeleteMapping("/executors/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "删除执行器", actionKey = "access.action.delete_an_executor")
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
