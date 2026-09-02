package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.scheduledtask.dto.CreateScheduledTaskRequest;
import com.aliyun.autowonder.scheduledtask.dto.ScheduledTaskVO;
import com.aliyun.autowonder.scheduledtask.dto.UpdateScheduledTaskRequest;
import com.aliyun.autowonder.scheduledtask.dto.ScheduledTaskSummaryVO;
import com.aliyun.autowonder.squad.SquadDao;
import com.aliyun.autowonder.squad.SquadMemberDao;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;

@Service
public class ScheduledTaskService {

    private static final int DEFAULT_START_DEADLINE_SECONDS = 21_600;
    private static final int DEFAULT_AFFINITY_TIMEOUT_SECONDS = 1_800;
    private static final int MAX_PAGE_SIZE = 100;
    private static final Set<String> LIST_STATUSES = Set.of(
            "ACTIVE", "PAUSED", "EXHAUSTED", "ARCHIVED");

    private final ScheduledTaskDao taskDao;
    private final AuditLogService auditLogService;
    private final ScheduledTaskSchedule schedule;
    private final ScheduledTaskValidator validator;
    private final Clock clock;

    @Autowired
    public ScheduledTaskService(ScheduledTaskDao taskDao, SquadDao squadDao,
                                SquadMemberDao memberDao, AgentDao agentDao,
                                AuditLogService auditLogService, ScheduledTaskSchedule schedule) {
        this(taskDao, squadDao, memberDao, agentDao, auditLogService, schedule, Clock.systemUTC());
    }

    public ScheduledTaskService(ScheduledTaskDao taskDao, SquadDao squadDao,
                                SquadMemberDao memberDao, AgentDao agentDao,
                                AuditLogService auditLogService, ScheduledTaskSchedule schedule,
                                Clock clock) {
        this.taskDao = taskDao;
        this.auditLogService = auditLogService;
        this.schedule = schedule;
        this.validator = new ScheduledTaskValidator(squadDao, memberDao, agentDao);
        this.clock = clock;
    }

    @Transactional
    public ScheduledTaskVO create(CreateScheduledTaskRequest request, long workspaceId, long userId) {
        requireMutationContext(workspaceId, userId);
        if (request == null) {
            throw validation("请求不能为空");
        }
        ScheduledTaskDO task = fromCreate(request);
        task.setWorkspaceId(workspaceId);
        task.setCreatorId(userId);
        task.setModifierId(userId);
        task.setStatus(initialStatus(request.getInitialStatus()));
        task.setNextFireAt(nextFire(task, clock.instant()));
        task.setIsDeleted(0);
        task.setVersion(0);

        validator.validate(task, workspaceId, schedule);
        taskDao.insert(task);
        audit(task, userId, "CREATE", null);
        return toVO(task);
    }

    @Transactional
    public ScheduledTaskVO update(long id, UpdateScheduledTaskRequest request,
                                  long workspaceId, long userId) {
        requireMutationContext(id, workspaceId, userId);
        if (request == null || request.getVersion() == null || request.getVersion() < 0) {
            throw validation("version 必须提供且不能为负数");
        }
        ScheduledTaskDO existing = requireTask(workspaceId, id);
        requireExpectedVersion(existing, request.getVersion());
        if (ScheduledTaskStatus.ARCHIVED.name().equals(existing.getStatus())) {
            throw invalidState("归档任务不可修改");
        }

        applyUpdate(existing, request);
        existing.setWorkspaceId(workspaceId);
        existing.setId(id);
        existing.setVersion(request.getVersion());
        existing.setModifierId(userId);
        existing.setNextFireAt(nextFire(existing, clock.instant()));
        validator.validate(existing, workspaceId, schedule);

        if (taskDao.update(existing) != 1) {
            throw versionConflict();
        }
        existing.setVersion(request.getVersion() + 1);
        audit(existing, userId, "UPDATE", existing.getStatus());
        return toVO(existing);
    }

    public ScheduledTaskVO get(long id, long workspaceId) {
        return toVO(requireTask(workspaceId, id));
    }

    public PageResult<ScheduledTaskVO> list(long workspaceId, String status, Long creatorId, Long squadId, String keyword,
                                      int limit, int offset) {
        if (workspaceId <= 0 || (status != null && !LIST_STATUSES.contains(status))
                || (creatorId != null && creatorId <= 0)) {
            throw validation("查询条件不合法");
        }
        int boundedLimit = Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
        int boundedOffset = Math.max(offset, 0);
        List<ScheduledTaskVO> result = new ArrayList<>();
        for (ScheduledTaskDO task : taskDao.listByWorkspace(
                workspaceId, status, creatorId, squadId, keyword, boundedLimit, boundedOffset)) {
            if (task != null && Long.valueOf(workspaceId).equals(task.getWorkspaceId())
                    && !Integer.valueOf(1).equals(task.getIsDeleted())) {
                result.add(toVO(task));
            }
        }
        return new PageResult<>(result, taskDao.countByWorkspace(workspaceId, status, creatorId, squadId, keyword), boundedOffset / boundedLimit + 1, boundedLimit);
    }

    public List<Instant> preview(String cronExpression, String timezone, int count) {
        return schedule.preview(normalizeCron(cronExpression), normalizeTimezone(timezone), clock.instant(), count);
    }
    public ScheduledTaskSummaryVO summary(long workspaceId, String status, Long squadId, String keyword) {
        ScheduledTaskSummaryVO result = taskDao.summarizeRuns(workspaceId, status, squadId, keyword);
        return result == null ? new ScheduledTaskSummaryVO() : result;
    }

    @Transactional
    public ScheduledTaskVO enable(long id, Integer version, long workspaceId, long userId) {
        requireMutationContext(id, workspaceId, userId);
        ScheduledTaskDO task = requireTask(workspaceId, id);
        requireExpectedVersion(task, version);
        if (!ScheduledTaskStatus.PAUSED.name().equals(task.getStatus())) {
            throw invalidState("只有暂停任务可以启用");
        }

        validator.validate(task, workspaceId, schedule);
        task.setNextFireAt(nextFire(task, clock.instant()));
        task.setModifierId(userId);
        task.setVersion(version);
        if (taskDao.update(task) != 1) {
            throw versionConflict();
        }
        int statusVersion = version + 1;
        if (taskDao.updateStatus(workspaceId, id, ScheduledTaskStatus.PAUSED.name(),
                ScheduledTaskStatus.ACTIVE.name(), statusVersion, userId) != 1) {
            throw versionConflict();
        }
        task.setStatus(ScheduledTaskStatus.ACTIVE.name());
        task.setVersion(statusVersion + 1);
        audit(task, userId, "ENABLE", ScheduledTaskStatus.PAUSED.name());
        return toVO(task);
    }

    @Transactional
    public ScheduledTaskVO pause(long id, Integer version, long workspaceId, long userId) {
        requireMutationContext(id, workspaceId, userId);
        return transition(id, version, workspaceId, userId,
                ScheduledTaskStatus.ACTIVE, ScheduledTaskStatus.PAUSED, "PAUSE");
    }

    @Transactional
    public ScheduledTaskVO archive(long id, Integer version, long workspaceId, long userId) {
        requireMutationContext(id, workspaceId, userId);
        ScheduledTaskDO task = requireTask(workspaceId, id);
        requireExpectedVersion(task, version);
        ScheduledTaskStatus source = statusOf(task.getStatus());
        if (source != ScheduledTaskStatus.PAUSED && source != ScheduledTaskStatus.EXHAUSTED) {
            throw invalidState("只有暂停或已耗尽任务可以归档");
        }
        return transition(task, version, workspaceId, userId,
                source, ScheduledTaskStatus.ARCHIVED, "ARCHIVE");
    }

    private ScheduledTaskVO transition(long id, Integer version, long workspaceId, long userId,
                                       ScheduledTaskStatus source, ScheduledTaskStatus target,
                                       String action) {
        ScheduledTaskDO task = requireTask(workspaceId, id);
        requireExpectedVersion(task, version);
        if (!source.name().equals(task.getStatus())) {
            throw invalidState("任务来源状态不允许该操作");
        }
        return transition(task, version, workspaceId, userId, source, target, action);
    }

    private ScheduledTaskVO transition(ScheduledTaskDO task, Integer version,
                                       long workspaceId, long userId,
                                       ScheduledTaskStatus source, ScheduledTaskStatus target,
                                       String action) {
        if (taskDao.updateStatus(workspaceId, task.getId(), source.name(), target.name(),
                version, userId) != 1) {
            throw versionConflict();
        }
        task.setStatus(target.name());
        task.setModifierId(userId);
        task.setVersion(version + 1);
        audit(task, userId, action, source.name());
        return toVO(task);
    }

    private ScheduledTaskDO fromCreate(CreateScheduledTaskRequest request) {
        ScheduledTaskDO task = new ScheduledTaskDO();
        task.setName(request.getName());
        task.setInstructionMd(request.getInstructionMd());
        task.setSquadId(request.getSquadId());
        task.setInitialAgentId(request.getInitialAgentId());
        task.setScheduleType(request.getScheduleType());
        task.setRunAt(request.getRunAt());
        task.setCronExpression(normalizeCron(request.getCronExpression()));
        task.setTimezone(normalizeTimezone(request.getTimezone()));
        task.setSessionMode(defaulted(request.getSessionMode(), "ISOLATED"));
        task.setOverlapPolicy(defaulted(request.getOverlapPolicy(), "SKIP"));
        task.setMisfirePolicy(defaulted(request.getMisfirePolicy(), "FIRE_LATEST"));
        task.setStartDeadlineSeconds(defaulted(
                request.getStartDeadlineSeconds(), DEFAULT_START_DEADLINE_SECONDS));
        task.setAffinityTimeoutSeconds(defaulted(
                request.getAffinityTimeoutSeconds(), DEFAULT_AFFINITY_TIMEOUT_SECONDS));
        return task;
    }

    private void applyUpdate(ScheduledTaskDO task, UpdateScheduledTaskRequest request) {
        task.setName(request.getName());
        task.setInstructionMd(request.getInstructionMd());
        task.setSquadId(request.getSquadId());
        task.setInitialAgentId(request.getInitialAgentId());
        task.setScheduleType(request.getScheduleType());
        task.setRunAt(request.getRunAt());
        task.setCronExpression(normalizeCron(request.getCronExpression()));
        task.setTimezone(normalizeTimezone(request.getTimezone()));
        task.setSessionMode(request.getSessionMode());
        task.setOverlapPolicy(request.getOverlapPolicy());
        task.setMisfirePolicy(request.getMisfirePolicy());
        task.setStartDeadlineSeconds(request.getStartDeadlineSeconds());
        task.setAffinityTimeoutSeconds(request.getAffinityTimeoutSeconds());
    }

    private String initialStatus(String requested) {
        String status = defaulted(requested, ScheduledTaskStatus.ACTIVE.name());
        if (!ScheduledTaskStatus.ACTIVE.name().equals(status)
                && !ScheduledTaskStatus.PAUSED.name().equals(status)) {
            throw validation("initialStatus 仅支持 ACTIVE/PAUSED");
        }
        return status;
    }

    private Date nextFire(ScheduledTaskDO task, Instant after) {
        if ("ONCE".equals(task.getScheduleType())) {
            return task.getRunAt();
        }
        if ("CRON".equals(task.getScheduleType())) {
            return Date.from(schedule.next(task.getCronExpression(), task.getTimezone(), after));
        }
        return null;
    }

    private ScheduledTaskDO requireTask(long workspaceId, long id) {
        if (workspaceId <= 0 || id <= 0) {
            throw new BizException(ErrorCode.SCHEDULED_TASK_NOT_FOUND);
        }
        ScheduledTaskDO task = taskDao.findById(workspaceId, id);
        if (task == null || !Long.valueOf(workspaceId).equals(task.getWorkspaceId())
                || Integer.valueOf(1).equals(task.getIsDeleted())) {
            throw new BizException(ErrorCode.SCHEDULED_TASK_NOT_FOUND);
        }
        return task;
    }

    private void requireExpectedVersion(ScheduledTaskDO task, Integer expected) {
        if (expected == null || expected < 0 || !expected.equals(task.getVersion())) {
            throw versionConflict();
        }
    }

    private ScheduledTaskStatus statusOf(String status) {
        try {
            return ScheduledTaskStatus.valueOf(status);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw invalidState("任务状态不合法");
        }
    }

    private void audit(ScheduledTaskDO task, long userId, String action, String previousStatus) {
        AuditLogRecord record = new AuditLogRecord();
        record.setTenantId(task.getWorkspaceId());
        record.setActorId(userId);
        record.setActorType("HUMAN");
        record.setModule("SCHEDULED_TASK");
        record.setAction(action);
        record.setTargetType("SCHEDULED_TASK");
        record.setTargetId(task.getId());
        record.setTriggerType("EVENT");
        record.setTriggerSource("WEB");
        record.setEventType("SCHEDULED_TASK_DEFINITION");
        record.detail("scheduleType", task.getScheduleType())
                .detail("status", task.getStatus())
                .detail("previousStatus", previousStatus)
                .detail("squadId", task.getSquadId())
                .detail("initialAgentId", task.getInitialAgentId())
                .detail("version", task.getVersion());
        auditLogService.recordRequired(record);
    }

    private ScheduledTaskVO toVO(ScheduledTaskDO task) {
        ScheduledTaskVO vo = new ScheduledTaskVO();
        vo.setId(task.getId());
        vo.setName(task.getName());
        vo.setInstructionMd(task.getInstructionMd());
        vo.setSquadId(task.getSquadId());
        vo.setInitialAgentId(task.getInitialAgentId());
        vo.setScheduleType(task.getScheduleType());
        vo.setRunAt(task.getRunAt());
        vo.setCronExpression(task.getCronExpression());
        vo.setTimezone(task.getTimezone());
        vo.setSessionMode(task.getSessionMode());
        vo.setOverlapPolicy(task.getOverlapPolicy());
        vo.setMisfirePolicy(task.getMisfirePolicy());
        vo.setStartDeadlineSeconds(task.getStartDeadlineSeconds());
        vo.setAffinityTimeoutSeconds(task.getAffinityTimeoutSeconds());
        vo.setStatus(task.getStatus());
        vo.setNextFireAt(task.getNextFireAt());
        vo.setLastFireAt(task.getLastFireAt());
        vo.setGmtCreate(task.getGmtCreate());
        vo.setGmtModified(task.getGmtModified());
        vo.setCreatorId(task.getCreatorId());
        vo.setModifierId(task.getModifierId());
        vo.setVersion(task.getVersion());
        return vo;
    }

    private BizException validation(String message) {
        return new BizException(ErrorCode.SCHEDULED_TASK_VALIDATION_FAILED, message);
    }

    private BizException invalidState(String message) {
        return new BizException(ErrorCode.SCHEDULED_TASK_INVALID_STATE, message);
    }

    private BizException versionConflict() {
        return new BizException(ErrorCode.SCHEDULED_TASK_VERSION_CONFLICT);
    }

    private void requireMutationContext(long workspaceId, long userId) {
        if (workspaceId <= 0 || userId <= 0) {
            throw validation("workspaceId 和 userId 必须为正数");
        }
    }

    private void requireMutationContext(long id, long workspaceId, long userId) {
        requireMutationContext(workspaceId, userId);
        if (id <= 0) {
            throw validation("任务 ID 必须为正数");
        }
    }

    private static String normalizeCron(String expression) {
        return expression == null ? null : expression.trim().replaceAll("\\s+", " ");
    }

    private static String normalizeTimezone(String timezone) {
        return timezone == null ? null : timezone.trim();
    }

    private static String defaulted(String value, String defaultValue) {
        return value == null ? defaultValue : value;
    }

    private static Integer defaulted(Integer value, Integer defaultValue) {
        return value == null ? defaultValue : value;
    }
}
