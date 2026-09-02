package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.workitem.WorkitemCommentDO;
import com.aliyun.autowonder.workitem.WorkitemCommentDao;
import com.aliyun.autowonder.workitem.dto.CommentVO;
import com.aliyun.autowonder.workitem.WorkitemCommentMentionDao;
import com.aliyun.autowonder.workitem.WorkitemCommentMentionDO;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.im.notification.WorkitemCommentMentionedEvent;
import com.aliyun.autowonder.redis.RedisManager;
import com.alibaba.fastjson.JSON;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.aliyun.autowonder.scheduledtask.compat.RequiresScheduledTaskCapability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Collection;
import java.util.Objects;

/**
 * Comment boundary for one immutable scheduled-task occurrence.  The historic
 * workitem_id column deliberately carries the Run id, but source_type makes
 * that representation unambiguous (and prevents equal numeric ids leaking).
 */
@Service
@RequiresScheduledTaskCapability(entry = "http")
public class ScheduledTaskRunCommentService {
    private final ScheduledTaskRunDao runDao;
    private final WorkitemCommentDao commentDao;
    private WorkitemCommentMentionDao mentionDao;
    private GuidanceService guidanceService;
    private AgentDao agentDao;
    private RedisManager redisManager;
    private ScheduledTaskNotificationService notificationService;
    private UserDao userDao;
    private WorkspaceMemberDao workspaceMemberDao;
    private ScheduledTaskDao scheduledTaskDao;
    private ApplicationEventPublisher eventPublisher;

    public ScheduledTaskRunCommentService(ScheduledTaskRunDao runDao, WorkitemCommentDao commentDao) {
        this.runDao = runDao;
        this.commentDao = commentDao;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public void configureInteractions(WorkitemCommentMentionDao mentionDao, GuidanceService guidanceService,
                                      AgentDao agentDao, RedisManager redisManager) {
        this.mentionDao = mentionDao;
        this.guidanceService = guidanceService;
        this.agentDao = agentDao;
        this.redisManager = redisManager;
    }
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setNotificationService(ScheduledTaskNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void configureHumanMentions(UserDao userDao, WorkspaceMemberDao workspaceMemberDao,
                                       ScheduledTaskDao scheduledTaskDao, ApplicationEventPublisher eventPublisher) {
        this.userDao = userDao;
        this.workspaceMemberDao = workspaceMemberDao;
        this.scheduledTaskDao = scheduledTaskDao;
        this.eventPublisher = eventPublisher;
    }

    @RequiresScheduledTaskCapability(entry = "http")
    @Transactional
    public CommentVO addAgentComment(long workspaceId, long runId, long agentId, String contentMd) {
        return addAgentComment(workspaceId, runId, agentId, contentMd, List.of(), List.of());
    }

    @RequiresScheduledTaskCapability(entry = "http")
    @Transactional
    public CommentVO addAgentComment(long workspaceId, long runId, long agentId, String contentMd,
                                     Collection<Long> explicitTargetAgentIds) {
        return addAgentComment(workspaceId, runId, agentId, contentMd, explicitTargetAgentIds, List.of());
    }

    @RequiresScheduledTaskCapability(entry = "http")
    @Transactional
    public CommentVO addAgentComment(long workspaceId, long runId, long agentId, String contentMd,
                                     Collection<Long> explicitTargetAgentIds, Collection<Long> explicitTargetHumanIds) {
        requireRun(workspaceId, runId);
        if (contentMd == null || contentMd.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        WorkitemCommentDO comment = new WorkitemCommentDO();
        comment.setTenantId(workspaceId);
        comment.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        comment.setWorkitemId(runId);
        comment.setAuthorType("AGENT");
        comment.setAuthorRef(agentId);
        comment.setContentMd(contentMd);
        commentDao.insert(comment);
        createMentionsAndGuidance(workspaceId, runId, comment, agentId, explicitTargetAgentIds, explicitTargetHumanIds);
        publish(runId, comment);
        if (notificationService != null) notificationService.comment(requireRun(workspaceId, runId));
        return toVO(comment);
    }

    @RequiresScheduledTaskCapability(entry = "http")
    @Transactional
    public CommentVO addHumanComment(long workspaceId, long runId, long userId, String contentMd) {
        return addHumanComment(workspaceId, runId, userId, contentMd, List.of());
    }

    @RequiresScheduledTaskCapability(entry = "http")
    @Transactional
    public CommentVO addHumanComment(long workspaceId, long runId, long userId, String contentMd,
                                     Collection<Long> explicitTargetHumanIds) {
        requireRun(workspaceId, runId);
        if (contentMd == null || contentMd.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        WorkitemCommentDO comment = new WorkitemCommentDO();
        comment.setTenantId(workspaceId);
        comment.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        comment.setWorkitemId(runId);
        comment.setAuthorType("HUMAN");
        comment.setAuthorRef(userId);
        comment.setContentMd(contentMd);
        commentDao.insert(comment);
        createMentionsAndGuidance(workspaceId, runId, comment, userId, List.of(), explicitTargetHumanIds);
        publish(runId, comment);
        if (notificationService != null) notificationService.comment(requireRun(workspaceId, runId));
        return toVO(comment);
    }

    @RequiresScheduledTaskCapability(entry = "http")
    public List<CommentVO> list(long workspaceId, long runId) {
        requireRun(workspaceId, runId);
        List<CommentVO> result = new ArrayList<>();
        for (WorkitemCommentDO comment : commentDao.listBySource(workspaceId,
                ExecutionSourceType.SCHEDULED_TASK_RUN.name(), runId)) {
            result.add(toVO(comment));
        }
        return result;
    }

    private ScheduledTaskRunDO requireRun(long workspaceId, long runId) {
        ScheduledTaskRunDO run = runDao.findById(workspaceId, runId);
        if (run == null || !Objects.equals(run.getWorkspaceId(), workspaceId)) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
        return run;
    }

    private CommentVO toVO(WorkitemCommentDO comment) {
        CommentVO vo = new CommentVO();
        vo.setId(comment.getId());
        vo.setWorkitemId(comment.getWorkitemId());
        vo.setAuthorType(comment.getAuthorType());
        vo.setAuthorRef(comment.getAuthorRef());
        vo.setContentMd(comment.getContentMd());
        vo.setGmtCreate(comment.getGmtCreate());
        return vo;
    }

    private void createMentionsAndGuidance(long workspaceId, long runId, WorkitemCommentDO comment, long creatorId,
            Collection<Long> explicitTargetAgentIds, Collection<Long> explicitTargetHumanIds) {
        if (comment.getId() == null || comment.getContentMd() == null) return;
        List<Long> agentTargets = explicitTargetAgentIds == null ? List.of() : explicitTargetAgentIds.stream()
                .filter(Objects::nonNull).distinct().toList();
        List<Long> humanTargets = explicitTargetHumanIds == null ? List.of() : explicitTargetHumanIds.stream()
                .filter(Objects::nonNull).distinct().toList();
        if (!agentTargets.isEmpty()) {
            if (mentionDao == null || agentDao == null) return;
            for (Long targetId : agentTargets) {
                AgentDO target = agentDao.findById(targetId);
                if (target == null || !Objects.equals(target.getTenantId(), workspaceId)
                        || !isFrozenParticipant(workspaceId, runId, targetId)) {
                    throw new IllegalArgumentException("guidance target is not a frozen scheduled-run participant");
                }
                persistMentionAndGuidance(workspaceId, runId, comment, creatorId, target);
            }
        }
        if (!humanTargets.isEmpty()) {
            if (mentionDao == null || userDao == null) return;
            for (Long userId : humanTargets) {
                persistHumanMention(workspaceId, runId, comment, creatorId, requireWorkspaceHuman(workspaceId, userId));
            }
        }
        if (!agentTargets.isEmpty() || !humanTargets.isEmpty()) return;
        String text = comment.getContentMd().stripLeading();
        if (!text.startsWith("@")) return;
        int end = 1;
        while (end < text.length() && !Character.isWhitespace(text.charAt(end))) end++;
        String name = text.substring(1, end).trim();
        if (mentionDao == null) return;
        List<AgentDO> matches = agentDao == null ? null : agentDao.findByExactName(workspaceId, name);
        if (matches != null && matches.size() == 1 && matches.get(0).getId() != null) {
            AgentDO agent = matches.get(0);
            if (isFrozenParticipant(workspaceId, runId, agent.getId())) {
                persistMentionAndGuidance(workspaceId, runId, comment, creatorId, agent);
                return;
            }
        }
        UserDO human = matchWorkspaceHuman(workspaceId, name);
        if (human != null) {
            persistHumanMention(workspaceId, runId, comment, creatorId, human);
        }
    }

    private UserDO requireWorkspaceHuman(long workspaceId, long userId) {
        if (userDao == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        if (workspaceMemberDao != null) {
            WorkspaceMemberDO member = workspaceMemberDao.findByWorkspaceAndUser(workspaceId, userId);
            if (member == null || member.getStatus() == null || member.getStatus() != 0) {
                throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
            }
        }
        UserDO user = userDao.findById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return user;
    }

    private UserDO matchWorkspaceHuman(long workspaceId, String name) {
        if (userDao == null || name == null || name.isBlank()) return null;
        List<UserDO> matches = userDao.findByUsernameOrNickname(name);
        if (matches == null || matches.size() != 1 || matches.get(0).getId() == null) return null;
        UserDO user = matches.get(0);
        if (workspaceMemberDao != null) {
            WorkspaceMemberDO member = workspaceMemberDao.findByWorkspaceAndUser(workspaceId, user.getId());
            if (member == null || member.getStatus() == null || member.getStatus() != 0) return null;
        }
        return user;
    }

    private void persistHumanMention(long workspaceId, long runId, WorkitemCommentDO comment,
            long creatorId, UserDO user) {
        WorkitemCommentMentionDO mention = new WorkitemCommentMentionDO();
        mention.setTenantId(workspaceId);
        mention.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        mention.setWorkitemId(runId);
        mention.setCommentId(comment.getId());
        mention.setTargetType("HUMAN");
        mention.setTargetRef(user.getId());
        mention.setDisplayNameSnapshot(resolveHumanDisplayName(user));
        mentionDao.insert(mention);
        if (eventPublisher != null && !Objects.equals(user.getId(), creatorId)) {
            eventPublisher.publishEvent(new WorkitemCommentMentionedEvent(workspaceId, runId,
                    resolveRunTitle(workspaceId, runId), comment.getId(), user.getId(),
                    comment.getAuthorType(), comment.getAuthorRef() == null ? 0L : comment.getAuthorRef(),
                    resolveActorDisplayName(comment), null, comment.getContentMd(),
                    WorkitemCommentMentionedEvent.SOURCE_SCHEDULED_TASK_RUN));
        }
    }

    private String resolveHumanDisplayName(UserDO user) {
        if (user.getNickname() != null && !user.getNickname().isBlank()) {
            return user.getNickname();
        }
        return user.getUsername();
    }

    private String resolveRunTitle(long workspaceId, long runId) {
        if (scheduledTaskDao != null) {
            ScheduledTaskRunDO run = runDao.findById(workspaceId, runId);
            if (run != null && run.getScheduledTaskId() != null) {
                ScheduledTaskDO task = scheduledTaskDao.findById(workspaceId, run.getScheduledTaskId());
                if (task != null && task.getName() != null && !task.getName().isBlank()) {
                    return task.getName();
                }
            }
        }
        return "定时任务运行 #" + runId;
    }

    private String resolveActorDisplayName(WorkitemCommentDO comment) {
        if ("AGENT".equals(comment.getAuthorType()) && agentDao != null && comment.getAuthorRef() != null) {
            AgentDO agent = agentDao.findById(comment.getAuthorRef());
            if (agent != null && agent.getName() != null && !agent.getName().isBlank()) {
                return agent.getName();
            }
        }
        if ("HUMAN".equals(comment.getAuthorType()) && userDao != null && comment.getAuthorRef() != null) {
            UserDO user = userDao.findById(comment.getAuthorRef());
            if (user != null) {
                return resolveHumanDisplayName(user);
            }
        }
        return comment.getAuthorType();
    }

    private void persistMentionAndGuidance(long workspaceId, long runId, WorkitemCommentDO comment,
            long creatorId, AgentDO agent) {
        WorkitemCommentMentionDO mention = new WorkitemCommentMentionDO();
        mention.setTenantId(workspaceId);
        mention.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        mention.setWorkitemId(runId);
        mention.setCommentId(comment.getId());
        mention.setTargetType("AGENT");
        mention.setTargetRef(agent.getId());
        mention.setDisplayNameSnapshot(agent.getName());
        mentionDao.insert(mention);
        if (guidanceService != null) {
            guidanceService.createForScheduledRunComment(workspaceId, runId, comment.getId(), agent.getId(), creatorId);
        }
    }

    private boolean isFrozenParticipant(long workspaceId, long runId, long agentId) {
        ScheduledTaskRunDO run = runDao.findById(workspaceId, runId);
        try {
            var root = com.alibaba.fastjson.JSON.parseObject(run == null ? null : run.getExecutionSnapshotJson());
            var contexts = root == null ? null : root.getJSONArray("agentContexts");
            for (int i = 0; contexts != null && i < contexts.size(); i++) {
                if (contexts.getJSONObject(i) != null && contexts.getJSONObject(i).getLongValue("agentId") == agentId) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    private void publish(long runId, WorkitemCommentDO comment) {
        if (redisManager != null) {
            redisManager.publish("scheduled-run:" + runId, JSON.toJSONString(Map.of(
                    "type", "comment", "runId", runId, "commentId", comment.getId())));
        }
    }
}
