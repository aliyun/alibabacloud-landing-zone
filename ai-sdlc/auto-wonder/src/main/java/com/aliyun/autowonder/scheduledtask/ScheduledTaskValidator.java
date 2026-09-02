package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.squad.SquadDO;
import com.aliyun.autowonder.squad.SquadDao;
import com.aliyun.autowonder.squad.SquadMemberDO;
import com.aliyun.autowonder.squad.SquadMemberDao;

import java.nio.charset.StandardCharsets;
import java.util.Set;

public class ScheduledTaskValidator {

    private static final int MAX_NAME_CHARS = 256;
    private static final int MAX_INSTRUCTION_BYTES = 16_777_215;
    private static final Set<String> SESSION_MODES = Set.of("ISOLATED", "CONTINUOUS");
    private static final Set<String> OVERLAP_POLICIES = Set.of("SKIP", "QUEUE", "ALLOW");
    private static final Set<String> MISFIRE_POLICIES = Set.of("FIRE_LATEST", "FIRE_ALL", "SKIP_ALL");

    private final SquadDao squadDao;
    private final SquadMemberDao memberDao;
    private final AgentDao agentDao;

    public ScheduledTaskValidator(SquadDao squadDao, SquadMemberDao memberDao, AgentDao agentDao) {
        this.squadDao = squadDao;
        this.memberDao = memberDao;
        this.agentDao = agentDao;
    }

    public void validate(ScheduledTaskDO task, long workspaceId, ScheduledTaskSchedule schedule) {
        validateDefinition(task, schedule);
        validateReferences(task, workspaceId);
    }

    public static void validateDefinition(ScheduledTaskDO task, ScheduledTaskSchedule schedule) {
        if (task == null || schedule == null) {
            fail("任务定义不能为空");
        }
        if (isBlank(task.getName()) || task.getName().length() > MAX_NAME_CHARS) {
            fail("任务名称不能为空且不能超过 256 个字符");
        }
        if (isBlank(task.getInstructionMd())
                || task.getInstructionMd().getBytes(StandardCharsets.UTF_8).length > MAX_INSTRUCTION_BYTES) {
            fail("任务指令不能为空或过长");
        }
        if (!positive(task.getSquadId()) || !positive(task.getInitialAgentId())) {
            fail("小队和初始 Agent ID 必须为正数");
        }
        validateSchedule(task, schedule);
        validateModes(task.getSessionMode(), task.getOverlapPolicy());
        if (!MISFIRE_POLICIES.contains(task.getMisfirePolicy())) {
            fail("misfirePolicy 不合法");
        }
        if (task.getStartDeadlineSeconds() == null || task.getStartDeadlineSeconds() <= 0) {
            fail("startDeadlineSeconds 必须为正数");
        }
        if (task.getAffinityTimeoutSeconds() == null) {
            fail("affinityTimeoutSeconds 不能为空");
        }
        if ("CONTINUOUS".equals(task.getSessionMode())
                && task.getAffinityTimeoutSeconds() <= 0) {
            fail("CONTINUOUS 模式的 affinityTimeoutSeconds 必须为正数");
        }
    }

    public static void validateModes(String sessionMode, String overlapPolicy) {
        if (!SESSION_MODES.contains(sessionMode)) {
            fail("sessionMode 不合法");
        }
        if (!OVERLAP_POLICIES.contains(overlapPolicy)) {
            fail("overlapPolicy 不合法");
        }
        if ("CONTINUOUS".equals(sessionMode) && "ALLOW".equals(overlapPolicy)) {
            fail("CONTINUOUS 模式不能并行执行");
        }
    }

    private static void validateSchedule(ScheduledTaskDO task, ScheduledTaskSchedule schedule) {
        if (isBlank(task.getTimezone())) {
            fail("timezone 不能为空");
        }
        if ("ONCE".equals(task.getScheduleType())) {
            if (task.getRunAt() == null || !isBlank(task.getCronExpression())) {
                fail("ONCE 必须且只能设置 runAt");
            }
            schedule.validate("0 0 0 * * *", task.getTimezone());
            return;
        }
        if ("CRON".equals(task.getScheduleType())) {
            if (isBlank(task.getCronExpression()) || task.getRunAt() != null) {
                fail("CRON 必须且只能设置 cronExpression");
            }
            schedule.validate(task.getCronExpression(), task.getTimezone());
            return;
        }
        fail("scheduleType 仅支持 ONCE/CRON");
    }

    private void validateReferences(ScheduledTaskDO task, long workspaceId) {
        SquadDO squad = squadDao.findById(task.getSquadId());
        if (squad == null || !sameWorkspace(squad.getTenantId(), workspaceId)
                || Integer.valueOf(1).equals(squad.getIsDeleted())) {
            fail("小队不存在或不属于当前工作空间");
        }

        AgentDO agent = agentDao.findById(task.getInitialAgentId());
        if (agent == null || !sameWorkspace(agent.getTenantId(), workspaceId)
                || Integer.valueOf(1).equals(agent.getIsDeleted()) || agent.getOnlineVersionId() == null) {
            fail("初始 Agent 不存在、未上线或不属于当前工作空间");
        }

        SquadMemberDO member = memberDao.findBySquadAndAgent(task.getSquadId(), task.getInitialAgentId());
        if (member == null || !sameWorkspace(member.getTenantId(), workspaceId)
                || !task.getSquadId().equals(member.getSquadId())
                || !task.getInitialAgentId().equals(member.getAgentId())) {
            fail("初始 Agent 不是该小队成员");
        }
    }

    private static boolean positive(Long value) {
        return value != null && value > 0;
    }

    private static boolean sameWorkspace(Long actual, long expected) {
        return actual != null && actual == expected;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static void fail(String message) {
        throw new BizException(ErrorCode.SCHEDULED_TASK_VALIDATION_FAILED, message);
    }
}
