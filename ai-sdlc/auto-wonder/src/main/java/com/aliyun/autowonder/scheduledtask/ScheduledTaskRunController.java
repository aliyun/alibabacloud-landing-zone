package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.artifact.ArtifactOwnerRef;
import com.aliyun.autowonder.artifact.ArtifactService;
import com.aliyun.autowonder.artifact.dto.ArtifactVO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDO;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.scheduledtask.dto.ScheduledTaskRunDetailVO;
import com.aliyun.autowonder.scheduledtask.dto.ScheduledTaskRunVO;
import com.aliyun.autowonder.scheduledtask.compat.RequiresScheduledTaskCapability;
import com.aliyun.autowonder.workitem.WorkitemService;
import com.aliyun.autowonder.workitem.dto.CommentVO;
import com.aliyun.autowonder.workitem.dto.DeliveryProgressVO;
import com.aliyun.autowonder.workitem.dto.ParticipantVO;
import com.aliyun.autowonder.workitem.dto.WorkitemVO;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/scheduled-task-runs")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看定时任务运行记录")
@RequiresScheduledTaskCapability(entry = "http")
public class ScheduledTaskRunController {
    private final ScheduledTaskRunDao runDao;
    private final ScheduledTaskRunService runService;
    private final ScheduledTaskRunOrchestrator orchestrator;
    private final ScheduledTaskRunCommentService commentService;
    private final ArtifactService artifactService;
    private final DispatchDao dispatchDao;
    private final DispatchRuntimeEventDao eventDao;
    private final WorkitemService workitemService;
    private final ScheduledTaskRunDispatchControlService dispatchControlService;
    private final ScheduledTaskRunParticipantService participantService;
    private final ScheduledTaskRunDeliveryProgressService deliveryProgressService;

    public ScheduledTaskRunController(ScheduledTaskRunDao runDao, ScheduledTaskRunService runService,
                                      ScheduledTaskRunOrchestrator orchestrator,
                                      ScheduledTaskRunCommentService commentService, ArtifactService artifactService,
                                      DispatchDao dispatchDao, DispatchRuntimeEventDao eventDao,
                                      WorkitemService workitemService, ScheduledTaskRunDispatchControlService dispatchControlService,
                                      ScheduledTaskRunParticipantService participantService,
                                      ScheduledTaskRunDeliveryProgressService deliveryProgressService) {
        this.runDao = runDao; this.runService = runService; this.orchestrator = orchestrator;
        this.commentService = commentService; this.artifactService = artifactService;
        this.dispatchDao = dispatchDao; this.eventDao = eventDao;
        this.workitemService = workitemService;
        this.dispatchControlService = dispatchControlService;
        this.participantService = participantService;
        this.deliveryProgressService = deliveryProgressService;
    }

    @GetMapping("/{runId}")
    public Result<ScheduledTaskRunDetailVO> get(@PathVariable long runId) {
        ScheduledTaskRunDO run = requireRun(runId);
        ScheduledTaskRunDetailVO detail = ScheduledTaskRunViews.toDetail(run);
        List<com.aliyun.autowonder.dispatch.DispatchDO> dispatches = dispatchDao.listBySource(workspaceId(), ExecutionSourceType.SCHEDULED_TASK_RUN.name(), runId);
        if (dispatches != null && !dispatches.isEmpty()) detail.setExecutorId(dispatches.get(dispatches.size() - 1).getExecutorId());
        return Result.ok(detail);
    }

    @PostMapping("/{runId}/pause")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "暂停定时任务运行")
    public Result<ScheduledTaskRunVO> pause(@PathVariable long runId, @RequestParam Integer version) {
        requireOwnerOrAdmin(requireRun(runId).getOwnerId());
        dispatchControlService.pauseActive(workspaceId(), runId, userId(), false);
        return Result.ok(ScheduledTaskRunViews.toVO(runService.transition(workspaceId(), runId, version, "PAUSED", userId())));
    }

    @PostMapping("/{runId}/resume")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "恢复定时任务运行")
    public Result<ScheduledTaskRunVO> resume(@PathVariable long runId, @RequestParam Integer version) {
        requireOwnerOrAdmin(requireRun(runId).getOwnerId());
        ScheduledTaskRunDO run = runService.transition(workspaceId(), runId, version, "QUEUED", userId());
        if (!orchestrator.resumePaused(workspaceId(), runId, userId())) orchestrator.start(workspaceId(), runId, userId());
        ScheduledTaskRunDO current = runDao.findById(workspaceId(), runId);
        return Result.ok(ScheduledTaskRunViews.toVO(current == null ? run : current));
    }

    @PostMapping("/{runId}/cancel")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "取消定时任务运行")
    public Result<ScheduledTaskRunVO> cancel(@PathVariable long runId, @RequestParam Integer version) {
        ScheduledTaskRunDO existing = requireRun(runId);
        requireOwnerOrAdmin(existing.getOwnerId());
        if (!version.equals(existing.getVersion()) || !runService.markCancelIntent(existing, userId())) {
            throw new BizException(ErrorCode.SCHEDULED_TASK_VERSION_CONFLICT);
        }
        boolean awaitingPause = dispatchControlService.pauseActive(workspaceId(), runId, userId(), true);
        ScheduledTaskRunDO current = runDao.findById(workspaceId(), runId);
        if (current != null && "CANCELED".equals(current.getStatus())) return Result.ok(ScheduledTaskRunViews.toVO(current));
        String target = awaitingPause ? "PAUSED" : "CANCELED";
        ScheduledTaskRunDO run = runService.transition(workspaceId(), runId, existing.getVersion(), target, userId());
        return Result.ok(ScheduledTaskRunViews.toVO(run));
    }

    @GetMapping("/{runId}/comments")
    public Result<List<CommentVO>> comments(@PathVariable long runId) { return Result.ok(commentService.list(workspaceId(), runId)); }

    @PostMapping("/{runId}/comments")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE, action = "评论定时任务运行")
    public Result<CommentVO> comment(@PathVariable long runId, @RequestBody RunCommentRequest request) {
        return Result.ok(commentService.addHumanComment(workspaceId(), runId, userId(),
                request == null ? null : request.contentMd,
                request == null ? null : request.getTargetHumanIds()));
    }

    @GetMapping("/{runId}/artifacts")
    public Result<List<ArtifactVO>> artifacts(@PathVariable long runId) {
        requireRun(runId);
        return Result.ok(artifactService.listByOwner(new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK_RUN, runId), workspaceId()));
    }

    @GetMapping("/{runId}/events")
    public Result<List<DispatchRuntimeEventDO>> events(@PathVariable long runId) {
        requireRun(runId);
        List<DispatchRuntimeEventDO> values = new ArrayList<>();
        for (var dispatch : dispatchDao.listBySource(workspaceId(), ExecutionSourceType.SCHEDULED_TASK_RUN.name(), runId)) {
            values.addAll(eventDao.listByDispatch(workspaceId(), dispatch.getId()));
        }
        return Result.ok(values);
    }

    /** Workitems created by a Run are server-stamped with this exact origin and exposed as IDs here. */
    @GetMapping("/{runId}/derived-workitems")
    public Result<List<WorkitemVO>> derivedWorkitems(@PathVariable long runId) {
        requireRun(runId);
        return Result.ok(workitemService.listByOrigin(workspaceId(), ExecutionSourceType.SCHEDULED_TASK_RUN.name(), runId));
    }

    @GetMapping("/{runId}/participants")
    public Result<List<ParticipantVO>> participants(@PathVariable long runId) {
        ScheduledTaskRunDO run = requireRun(runId);
        return Result.ok(participantService.getParticipants(workspaceId(), run));
    }

    @GetMapping("/{runId}/delivery-progress")
    public Result<DeliveryProgressVO> deliveryProgress(@PathVariable long runId) {
        ScheduledTaskRunDO run = requireRun(runId);
        return Result.ok(deliveryProgressService.getDeliveryProgress(workspaceId(), run));
    }

    private ScheduledTaskRunDO requireRun(long runId) {
        ScheduledTaskRunDO run = runDao.findById(workspaceId(), runId);
        if (run == null || !Long.valueOf(workspaceId()).equals(run.getWorkspaceId())) throw new BizException(ErrorCode.SCHEDULED_TASK_NOT_FOUND);
        return run;
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

    public static class RunCommentRequest {
        public String contentMd;
        public java.util.List<Long> targetHumanIds;
        public String getContentMd() { return contentMd; }
        public void setContentMd(String contentMd) { this.contentMd = contentMd; }
        public java.util.List<Long> getTargetHumanIds() { return targetHumanIds; }
        public void setTargetHumanIds(java.util.List<Long> targetHumanIds) { this.targetHumanIds = targetHumanIds; }
    }
}
