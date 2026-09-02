package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.scheduledtask.dto.CreateScheduledTaskRequest;
import com.aliyun.autowonder.scheduledtask.dto.RunNowRequest;
import com.aliyun.autowonder.scheduledtask.dto.ScheduledTaskRunVO;
import com.aliyun.autowonder.scheduledtask.dto.ScheduledTaskVO;
import com.aliyun.autowonder.scheduledtask.dto.UpdateScheduledTaskRequest;
import com.aliyun.autowonder.scheduledtask.dto.ScheduledTaskSummaryVO;
import com.aliyun.autowonder.scheduledtask.compat.RequiresScheduledTaskCapability;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

@RestController
@RequestMapping("/api/scheduled-tasks")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看定时任务")
@RequiresScheduledTaskCapability(entry = "http")
public class ScheduledTaskController {
    private final ScheduledTaskService taskService;
    private final ScheduledTaskRunDao runDao;
    private final ScheduledTaskTriggerService triggerService;

    public ScheduledTaskController(ScheduledTaskService taskService, ScheduledTaskRunDao runDao,
                                   ScheduledTaskTriggerService triggerService) {
        this.taskService = taskService;
        this.runDao = runDao;
        this.triggerService = triggerService;
    }

    @GetMapping
    public Result<com.aliyun.autowonder.common.result.PageResult<ScheduledTaskVO>> list(@RequestParam(required = false) String status,
                                               @RequestParam(required = false) Long creatorId,
                                               @RequestParam(required = false) Long squadId, @RequestParam(required = false) String keyword,
                                               @RequestParam(defaultValue = "20") int size,
                                               @RequestParam(defaultValue = "0") int offset) {
        return Result.ok(taskService.list(workspaceId(), status, creatorId, squadId, keyword, size, offset));
    }

    /** Uses the exact server-side Spring CronExpression evaluator used by the scheduler. */
    @GetMapping("/preview")
    public Result<List<Instant>> preview(@RequestParam String cronExpression,
                                         @RequestParam(defaultValue = "Asia/Shanghai") String timezone,
                                         @RequestParam(defaultValue = "5") int count) {
        workspaceId();
        return Result.ok(taskService.preview(cronExpression, timezone, count));
    }
    @GetMapping("/summary")
    public Result<ScheduledTaskSummaryVO> summary(@RequestParam(required = false) String status, @RequestParam(required = false) Long squadId, @RequestParam(required = false) String keyword) {
        return Result.ok(taskService.summary(workspaceId(), status, squadId, keyword));
    }

    @PostMapping
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "创建定时任务")
    public Result<ScheduledTaskVO> create(@RequestBody CreateScheduledTaskRequest request) {
        return Result.ok(taskService.create(request, workspaceId(), userId()));
    }

    @GetMapping("/{id}")
    public Result<ScheduledTaskVO> get(@PathVariable long id) { return Result.ok(taskService.get(id, workspaceId())); }

    @PutMapping("/{id}")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "更新定时任务")
    public Result<ScheduledTaskVO> update(@PathVariable long id, @RequestBody UpdateScheduledTaskRequest request) {
        requireOwnerOrAdmin(taskService.get(id, workspaceId()).getCreatorId());
        return Result.ok(taskService.update(id, request, workspaceId(), userId()));
    }

    @PostMapping("/{id}/enable")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "启用定时任务")
    public Result<ScheduledTaskVO> enable(@PathVariable long id, @RequestParam Integer version) {
        requireOwnerOrAdmin(taskService.get(id, workspaceId()).getCreatorId());
        return Result.ok(taskService.enable(id, version, workspaceId(), userId()));
    }

    @PostMapping("/{id}/pause")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "暂停定时任务")
    public Result<ScheduledTaskVO> pause(@PathVariable long id, @RequestParam Integer version) {
        requireOwnerOrAdmin(taskService.get(id, workspaceId()).getCreatorId());
        return Result.ok(taskService.pause(id, version, workspaceId(), userId()));
    }

    @PostMapping("/{id}/archive")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "归档定时任务")
    public Result<ScheduledTaskVO> archive(@PathVariable long id, @RequestParam Integer version) {
        requireOwnerOrAdmin(taskService.get(id, workspaceId()).getCreatorId());
        return Result.ok(taskService.archive(id, version, workspaceId(), userId()));
    }

    @PostMapping("/{id}/run-now")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "立即运行定时任务")
    public Result<ScheduledTaskRunVO> runNow(@PathVariable long id, @RequestBody RunNowRequest request) {
        if (request == null || request.getRequestId() == null || request.getRequestId().isBlank()) {
            throw new BizException(ErrorCode.SCHEDULED_TASK_VALIDATION_FAILED, "requestId 必须提供");
        }
        ScheduledTaskVO task = taskService.get(id, workspaceId());
        requireOwnerOrAdmin(task.getCreatorId());
        if (request.getVersion() == null || !request.getVersion().equals(task.getVersion())) {
            throw new BizException(ErrorCode.SCHEDULED_TASK_VERSION_CONFLICT);
        }
        return Result.ok(ScheduledTaskRunViews.toVO(triggerService.fireManual(workspaceId(), id, request.getRequestId())));
    }

    @GetMapping("/{id}/runs")
    public Result<List<ScheduledTaskRunVO>> runs(@PathVariable long id,
                                                  @RequestParam(defaultValue = "20") int size,
                                                  @RequestParam(defaultValue = "0") int offset) {
        taskService.get(id, workspaceId());
        int limit = Math.min(Math.max(size, 1), 100);
        return Result.ok(runDao.listByTask(workspaceId(), id, limit, Math.max(offset, 0)).stream()
                .map(ScheduledTaskRunViews::toVO).toList());
    }
    @GetMapping("/{id}/health")
    public Result<com.aliyun.autowonder.scheduledtask.dto.ScheduledTaskHealthVO> health(@PathVariable long id) {
        taskService.get(id, workspaceId());
        var value = new com.aliyun.autowonder.scheduledtask.dto.ScheduledTaskHealthVO();
        Date since = Date.from(Instant.now().minus(30, ChronoUnit.DAYS));
        value.setCompleted30d(runDao.countCompletedByTaskSince(workspaceId(), id, since));
        value.setSuccess30d(runDao.countSucceededByTaskSince(workspaceId(), id, since));
        return Result.ok(value);
    }

    private long workspaceId() { return required(AutoWonderContext.get().getCurrentWorkspaceId(), ErrorCode.WORKSPACE_NOT_MEMBER); }
    private long userId() { return required(AutoWonderContext.get().getUserId(), ErrorCode.UNAUTHORIZED); }
    private long required(Long value, ErrorCode code) { if (value == null) throw new BizException(code); return value; }
    private void requireOwnerOrAdmin(Long ownerId) {
        if (!Long.valueOf(userId()).equals(ownerId)
                && !WorkspaceAccessLevel.ADMIN.equals(AutoWonderContext.get().getWorkspaceAccessLevel())) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }
}
