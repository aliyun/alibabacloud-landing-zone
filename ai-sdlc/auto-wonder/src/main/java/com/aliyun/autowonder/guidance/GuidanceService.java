package com.aliyun.autowonder.guidance;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.artifact.ArtifactOwnerRef;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDao;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDO;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunCommentService;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.dispatch.AgentSdlcResolver;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemCommentDO;
import com.aliyun.autowonder.workitem.WorkitemCommentDao;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemService;
import com.aliyun.autowonder.workitem.dto.CommentInteractionVO;
import com.aliyun.autowonder.workitem.dto.ParticipantVO;
import com.aliyun.autowonder.workitem.dto.TimelineItemVO;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.HashSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class GuidanceService {
    private static final String FORMAL_WORKFLOW_ACKNOWLEDGEMENT = "收到，已转入正式工作流程。";
    private static final Set<String> ACTIVE_TURN_STATUSES = Set.of(
            "PENDING", "PACKAGING", "DISPATCHED", "ACKED", "RUNNING", "PAUSING", "PAUSE_FAILED");

    private final GuidanceDao guidanceDao;
    private final WorkitemDao workitemDao;
    private final WorkitemCommentDao commentDao;
    private final AgentDao agentDao;
    private final DispatchDao dispatchDao;
    private final GuidanceTransport transport;
    private final WorkitemService workitemService;
    private final DispatchService dispatchService;
    private final AgentSdlcResolver sdlcResolver;
    private final ApplicationEventPublisher eventPublisher;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ScheduledTaskRunDao scheduledTaskRunDao;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    @org.springframework.context.annotation.Lazy
    private ScheduledTaskRunCommentService scheduledTaskRunCommentService;

    public GuidanceService(GuidanceDao guidanceDao, WorkitemDao workitemDao, WorkitemCommentDao commentDao,
            AgentDao agentDao,
            DispatchDao dispatchDao, GuidanceTransport transport, WorkitemService workitemService,
            DispatchService dispatchService, AgentSdlcResolver sdlcResolver,
            ApplicationEventPublisher eventPublisher) {
        this.guidanceDao = guidanceDao;
        this.workitemDao = workitemDao;
        this.commentDao = commentDao;
        this.agentDao = agentDao;
        this.dispatchDao = dispatchDao;
        this.transport = transport;
        this.workitemService = workitemService;
        this.dispatchService = dispatchService;
        this.sdlcResolver = sdlcResolver;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public void createForComment(long workspaceId, long workitemId, long commentId, String contentMd,
            List<Long> explicitTargetAgentIds, long creatorId) {
        List<Long> targetAgentIds = explicitTargetAgentIds == null ? List.of()
                : explicitTargetAgentIds.stream().filter(Objects::nonNull).distinct().toList();
        if (targetAgentIds.isEmpty()) {
            targetAgentIds = resolveLeadingMention(workspaceId, workitemId, contentMd);
        }
        for (Long targetAgentId : targetAgentIds) {
            create(workspaceId, workitemId, commentId, targetAgentId, creatorId, contentMd);
        }
    }

    /** Creates a guidance delivery bound to a Run, never the similarly numbered Workitem. */
    @Transactional
    public GuidanceDO createForScheduledRunComment(long workspaceId, long runId, long commentId,
            long targetAgentId, long creatorId) {
        if (scheduledTaskRunDao == null) throw new IllegalStateException("scheduled run guidance unavailable");
        ScheduledTaskRunDO run = scheduledTaskRunDao.findById(workspaceId, runId);
        if (run == null || !Objects.equals(run.getWorkspaceId(), workspaceId)) {
            throw new IllegalArgumentException("scheduled run does not belong to tenant");
        }
        AgentDO agent = agentDao.findById(targetAgentId);
        if (agent == null || !Objects.equals(agent.getTenantId(), workspaceId)) {
            throw new IllegalArgumentException("target agent does not belong to tenant");
        }
        WorkitemCommentDO comment = commentDao.findBySourceAndId(workspaceId,
                ExecutionSourceType.SCHEDULED_TASK_RUN.name(), runId, commentId);
        if (comment == null) throw new IllegalArgumentException("guidance comment does not belong to scheduled run");
        GuidanceDO guidance = new GuidanceDO();
        guidance.setTenantId(workspaceId);
        guidance.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        guidance.setWorkitemId(runId);
        guidance.setCommentId(commentId);
        guidance.setTargetAgentId(targetAgentId);
        guidance.setStatus(GuidanceStatus.QUEUED);
        guidanceDao.insert(guidance);
        DispatchDO interaction = dispatchService.enqueueScheduledRunCommentInteraction(workspaceId, runId,
                targetAgentId, null, guidance.getId(), creatorId);
        dispatchService.pinScheduledAgentVersion(interaction.getId(), workspaceId,
                frozenAgentVersion(run, targetAgentId));
        guidance.setDispatchId(interaction.getId());
        if (guidanceDao.bindPendingDispatch(guidance.getId(), workspaceId, interaction.getId()) != 1) {
            throw new IllegalStateException("failed to bind scheduled-run guidance dispatch");
        }
        eventPublisher.publishEvent(new GuidanceDispatchQueuedEvent(workspaceId, interaction.getId()));
        return guidance;
    }

    private List<Long> resolveLeadingMention(long workspaceId, long workitemId, String contentMd) {
        if (contentMd == null) {
            return List.of();
        }
        String content = contentMd.stripLeading();
        if (!content.startsWith("@")) {
            return List.of();
        }
        String mentionName = leadingMentionName(content);
        if (mentionName == null || mentionName.isBlank()) {
            return List.of();
        }
        List<Long> matches = workitemService.getParticipants(workitemId, workspaceId).stream()
                .filter(Objects::nonNull)
                .filter(ParticipantVO::isAgent)
                .filter(participant -> participant.getUserId() != null && participant.getName() != null)
                .filter(participant -> mentionName.equals(participant.getName().trim()))
                .map(ParticipantVO::getUserId)
                .distinct()
                .toList();
        if (matches.size() == 1) {
            return matches;
        }
        List<AgentDO> tenantMatches = safeList(agentDao.findByExactName(workspaceId, mentionName)).stream()
                .filter(agent -> agent != null && Objects.equals(agent.getTenantId(), workspaceId)
                        && agent.getId() != null)
                .toList();
        return tenantMatches.size() == 1 ? List.of(tenantMatches.get(0).getId()) : List.of();
    }

    private String leadingMentionName(String content) {
        if (content == null || !content.startsWith("@")) {
            return null;
        }
        int start = 1;
        int end = start;
        while (end < content.length()) {
            char ch = content.charAt(end);
            if (Character.isWhitespace(ch)) {
                break;
            }
            end++;
        }
        return end > start ? content.substring(start, end).trim() : null;
    }

    private <T> List<T> safeList(List<T> rows) {
        return rows == null ? List.of() : rows;
    }

    @Transactional
    public GuidanceDO create(long workspaceId, long workitemId, long commentId, long targetAgentId,
            long creatorId) {
        return create(workspaceId, workitemId, commentId, targetAgentId, creatorId, null);
    }

    private GuidanceDO create(long workspaceId, long workitemId, long commentId, long targetAgentId,
            long creatorId, String commentContentMd) {
        WorkitemDO workitem = workitemDao.findById(workitemId);
        if (workitem == null || !Objects.equals(workitem.getTenantId(), workspaceId)) {
            throw new IllegalArgumentException("workitem does not belong to tenant");
        }
        AgentDO agent = agentDao.findById(targetAgentId);
        if (agent == null || !Objects.equals(agent.getTenantId(), workspaceId)) {
            throw new IllegalArgumentException("target agent does not belong to tenant");
        }
        requireComment(workspaceId, ExecutionSourceType.WORKITEM, workitemId, commentId);

        GuidanceDO guidance = new GuidanceDO();
        guidance.setTenantId(workspaceId);
        guidance.setWorkitemId(workitemId);
        guidance.setCommentId(commentId);
        guidance.setTargetAgentId(targetAgentId);
        if (agent.getOnlineVersionId() == null) {
            guidance.setStatus(GuidanceStatus.FAILED);
            guidance.setError("目标数字员工未发布在线版本，无法启动会话");
            guidanceDao.insert(guidance);
            return guidance;
        }
        guidance.setStatus(GuidanceStatus.QUEUED);
        guidanceDao.insert(guidance);
        List<DispatchDO> dispatches = dispatchDao.listByWorkitem(workspaceId, workitemId);
        DispatchDO prior = latestForAgent(dispatches, targetAgentId);
        Long sdlcId = sdlcResolver.resolveSdlcId(workspaceId, targetAgentId);
        SdlcStepDO firstStep = sdlcId == null ? null : sdlcResolver.firstStep(workspaceId, sdlcId);
        if (prior == null && !hasWorkerHistory(dispatches, targetAgentId)
                && isMentionOnly(commentContentMd, agent.getName())
                && sdlcId != null && firstStep != null && firstStep.getId() != null) {
            // A worker with no delivery history on this workitem cannot resume a
            // conversation. Treat its first @ mention as a formal SDLC start.
            workitemService.rebindForInteractionRework(workspaceId, workitemId, targetAgentId,
                    sdlcId, firstStep.getId(), creatorId);
            var acknowledgement = workitemService.addAgentComment(workitemId,
                    FORMAL_WORKFLOW_ACKNOWLEDGEMENT, workspaceId, targetAgentId);
            if (acknowledgement == null || acknowledgement.getId() == null
                    || guidanceDao.bindReplyComment(guidance.getId(), workspaceId,
                    acknowledgement.getId()) != 1) {
                throw new IllegalStateException("failed to record formal workflow acknowledgement");
            }
            DispatchDO formal = dispatchService.enqueue(workspaceId, workitemId, firstStep.getId(),
                    targetAgentId, 1, creatorId);
            guidance.setDispatchId(formal.getId());
            if (guidanceDao.bindPendingDispatch(guidance.getId(), workspaceId, formal.getId()) != 1) {
                throw new IllegalStateException("failed to bind formal worker dispatch");
            }
            guidance.setStatus(GuidanceStatus.APPLIED);
            guidanceDao.updateStatus(guidance.getId(), workspaceId, GuidanceStatus.APPLIED, null);
            eventPublisher.publishEvent(new GuidanceDispatchQueuedEvent(workspaceId, formal.getId()));
            return guidance;
        }
        boolean forkSourceSession = prior != null
                && ACTIVE_TURN_STATUSES.contains(prior.getStatus())
                && dispatchService.hasResumableSession(workspaceId, prior.getId());
        DispatchDO interaction = dispatchService.enqueueCommentInteraction(
                workspaceId, workitemId, targetAgentId, prior == null ? null : prior.getId(),
                forkSourceSession, firstStep == null ? null : firstStep.getId(), guidance.getId(), creatorId);
        if (interaction != null) {
            guidance.setDispatchId(interaction.getId());
            if (guidanceDao.bindPendingDispatch(guidance.getId(), workspaceId, interaction.getId()) != 1) {
                throw new IllegalStateException("failed to bind comment interaction dispatch");
            }
            eventPublisher.publishEvent(new GuidanceDispatchQueuedEvent(workspaceId, interaction.getId()));
        }
        return guidance;
    }

    private boolean isMentionOnly(String contentMd, String agentName) {
        if (contentMd == null || agentName == null || agentName.isBlank()) {
            return false;
        }
        return contentMd.strip().equals("@" + agentName.strip());
    }

    private boolean hasWorkerHistory(List<DispatchDO> dispatches, long targetAgentId) {
        return dispatches != null && dispatches.stream()
                .anyMatch(dispatch -> dispatch != null
                        && Objects.equals(dispatch.getAgentId(), targetAgentId));
    }

    @Transactional
    public void deliverQueuedForDispatch(long workspaceId, long dispatchId) {
        DispatchDO dispatch = dispatchDao.findById(dispatchId);
        if (dispatch != null && Objects.equals(dispatch.getTenantId(), workspaceId)) {
            deliverQueued(dispatch);
        }
    }

    @Transactional
    public void deliverQueued(DispatchDO dispatch) {
        if (dispatch == null || dispatch.getExecutorId() == null || dispatch.getAgentId() == null) {
            return;
        }
        if (!"SIDE_INTERACTION".equals(dispatch.getResumeMode())
                && !"CANONICAL_INTERACTION".equals(dispatch.getResumeMode())) {
            return;
        }
        List<GuidanceDO> queued = guidanceDao.listQueuedForDispatch(
                dispatch.getTenantId(), dispatch.getId());
        for (GuidanceDO guidance : queued) {
            if (guidanceDao.bindDispatch(guidance.getId(), dispatch.getTenantId(),
                    dispatch.getId(), dispatch.getExecutorId()) != 1) {
                continue;
            }
            guidance.setDispatchId(dispatch.getId());
            guidance.setExecutorId(dispatch.getExecutorId());
            guidance.setStatus(GuidanceStatus.DELIVERED);
            guidanceDao.updateStatus(guidance.getId(), dispatch.getTenantId(), GuidanceStatus.DELIVERED, null);
            WorkitemCommentDO comment = requireComment(dispatch.getTenantId(), sourceType(guidance),
                    guidance.getWorkitemId(), guidance.getCommentId());
            transport.send(guidance, comment.getContentMd());
        }
    }

    public void redeliverUnacknowledged(long workspaceId, long executorId) {
        for (GuidanceDO guidance : safeList(guidanceDao.listDeliveredForExecutor(workspaceId, executorId))) {
            WorkitemCommentDO comment = requireComment(workspaceId, sourceType(guidance),
                    guidance.getWorkitemId(), guidance.getCommentId());
            transport.send(guidance, comment.getContentMd());
        }
    }

    @Transactional
    public void acknowledge(long workspaceId, long executorId, long guidanceId, String status, String error,
            String replyMarkdown) {
        if (!Set.of(GuidanceStatus.APPLIED, GuidanceStatus.FAILED).contains(status)) {
            throw new IllegalArgumentException("invalid guidance status");
        }
        int updated = guidanceDao.acknowledge(guidanceId, workspaceId, executorId, status, error);
        if (updated != 1) {
            return;
        }
        GuidanceDO guidance = guidanceDao.findById(guidanceId);
        if (guidance == null || !Objects.equals(guidance.getTenantId(), workspaceId)) {
            throw new IllegalStateException("acknowledged guidance is missing");
        }
        if (GuidanceStatus.FAILED.equals(status)) {
            DispatchDO dispatch = guidance.getDispatchId() == null
                    ? null : dispatchDao.findById(guidance.getDispatchId());
            if (dispatch == null
                    || !Objects.equals(dispatch.getTenantId(), workspaceId)
                    || !Set.of("SIDE_INTERACTION", "CANONICAL_INTERACTION").contains(dispatch.getResumeMode())) {
                throw new IllegalStateException("guidance interaction dispatch is missing");
            }
            if (!dispatchService.onResult(workspaceId, executorId, dispatch.getId(), false,
                    null, error, false)) {
                throw new IllegalStateException("failed to terminate guidance interaction dispatch");
            }
            return;
        }
        if (replyMarkdown != null && !replyMarkdown.isBlank()) {
            Long replyId;
            if (sourceType(guidance) == ExecutionSourceType.SCHEDULED_TASK_RUN) {
                if (scheduledTaskRunCommentService == null) {
                    throw new IllegalStateException("scheduled run comment service unavailable");
                }
                replyId = scheduledTaskRunCommentService.addAgentComment(workspaceId,
                        guidance.getWorkitemId(), guidance.getTargetAgentId(), replyMarkdown).getId();
            } else {
                var reply = workitemService.addAgentComment(guidance.getWorkitemId(), replyMarkdown, workspaceId,
                        guidance.getTargetAgentId());
                replyId = reply == null ? null : reply.getId();
            }
            if (replyId == null || guidanceDao.bindReplyComment(guidanceId, workspaceId, replyId) != 1) {
                throw new IllegalStateException("failed to bind side interaction reply comment");
            }
        }
    }

    /** Resolves the durable dispatch binding of a daemon guidance acknowledgement without mutating it. */
    public InboundAcknowledgementBinding bindingForInboundAcknowledgement(
            long workspaceId, long executorId, long guidanceId) {
        GuidanceDO guidance = guidanceDao.findById(guidanceId);
        if (guidance == null
                || !Objects.equals(guidance.getTenantId(), workspaceId)
                || !Objects.equals(guidance.getExecutorId(), executorId)
                || guidance.getWorkitemId() == null
                || guidance.getWorkitemId() <= 0
                || guidance.getDispatchId() == null
                || guidance.getDispatchId() <= 0) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        DispatchDO dispatch = dispatchDao.findById(guidance.getDispatchId());
        if (dispatch == null
                || !Objects.equals(dispatch.getTenantId(), workspaceId)
                || !Objects.equals(dispatch.getExecutorId(), executorId)
                || !Objects.equals(dispatch.getExecutorId(), guidance.getExecutorId())
                || dispatch.getWorkitemId() == null
                || dispatch.getWorkitemId() <= 0
                || !Objects.equals(dispatch.getWorkitemId(), guidance.getWorkitemId())
                || (guidance.getTargetAgentId() != null
                && !Objects.equals(dispatch.getAgentId(), guidance.getTargetAgentId()))) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        return new InboundAcknowledgementBinding(dispatch.getId(),
                new ArtifactOwnerRef(dispatch.executionSourceType(), dispatch.getWorkitemId()));
    }

    public record InboundAcknowledgementBinding(long dispatchId, ArtifactOwnerRef owner) {
    }

    @Transactional
    public void requeueDeliveredForDispatch(long workspaceId, long dispatchId) {
        guidanceDao.requeueDeliveredForDispatch(workspaceId, dispatchId);
    }

    @Transactional
    public void requeueForExecutorFailover(long workspaceId, long dispatchId) {
        guidanceDao.requeueForExecutorFailover(workspaceId, dispatchId);
    }

    @Transactional
    public void failForDispatch(long workspaceId, long dispatchId, String error) {
        guidanceDao.failForDispatch(workspaceId, dispatchId, error);
    }

    public void attachInteractionStatuses(long workspaceId, long workitemId, List<TimelineItemVO> timeline) {
        Map<Long, TimelineItemVO> comments = new LinkedHashMap<>();
        for (TimelineItemVO item : timeline) {
            if (item != null && "comment".equals(item.getType())) {
                comments.put(item.getId(), item);
            }
        }
        List<GuidanceDO> rows = guidanceDao.listByWorkitem(workspaceId, workitemId);
        if (rows == null) {
            return;
        }
        Set<Long> nestedReplyCommentIds = new HashSet<>();
        for (GuidanceDO guidance : rows) {
            if (GuidanceStatus.APPLIED.equals(guidance.getStatus()) && guidance.getReplyCommentId() == null) {
                continue;
            }
            TimelineItemVO comment = comments.get(guidance.getCommentId());
            if (comment == null) {
                continue;
            }
            CommentInteractionVO interaction = new CommentInteractionVO();
            interaction.setGuidanceId(guidance.getId());
            interaction.setTargetAgentId(guidance.getTargetAgentId());
            AgentDO target = agentDao.findById(guidance.getTargetAgentId());
            interaction.setTargetAgentName(target == null ? String.valueOf(guidance.getTargetAgentId()) : target.getName());
            interaction.setStatus(guidance.getStatus());
            interaction.setError(guidance.getError());
            if (guidance.getReplyCommentId() != null) {
                TimelineItemVO reply = comments.get(guidance.getReplyCommentId());
                if (reply != null) {
                    interaction.setReplyCommentId(reply.getId());
                    interaction.setReplyContent(reply.getContent());
                    interaction.setRepliedAt(reply.getGmtCreate());
                    nestedReplyCommentIds.add(reply.getId());
                }
            }
            if (comment.getInteractions() == null) {
                comment.setInteractions(new ArrayList<>());
            }
            comment.getInteractions().add(interaction);
        }
        if (!nestedReplyCommentIds.isEmpty()) {
            timeline.removeIf(item -> item != null && "comment".equals(item.getType())
                    && nestedReplyCommentIds.contains(item.getId()));
        }
    }

    private WorkitemCommentDO requireComment(long workspaceId, ExecutionSourceType sourceType,
            long workitemId, long commentId) {
        // The legacy DAO is now explicitly source-filtered to WORKITEM in XML,
        // retaining compatibility for existing callers/tests while the Run path
        // always supplies the complete three-part owner key.
        WorkitemCommentDO comment = sourceType == ExecutionSourceType.WORKITEM
                ? commentDao.findById(workspaceId, commentId)
                : commentDao.findBySourceAndId(workspaceId, sourceType.name(), workitemId, commentId);
        if (comment == null || !Objects.equals(comment.getWorkitemId(), workitemId)) {
            throw new IllegalArgumentException("guidance comment does not belong to source");
        }
        return comment;
    }

    private ExecutionSourceType sourceType(GuidanceDO guidance) {
        return ExecutionSourceType.valueOrWorkitem(guidance == null ? null : guidance.getSourceType());
    }

    private long frozenAgentVersion(ScheduledTaskRunDO run, long agentId) {
        try {
            JSONObject root = JSON.parseObject(run.getExecutionSnapshotJson());
            JSONArray contexts = root == null ? null : root.getJSONArray("agentContexts");
            Long match = null;
            for (int i = 0; contexts != null && i < contexts.size(); i++) {
                JSONObject context = contexts.getJSONObject(i);
                if (context != null && context.getLongValue("agentId") == agentId) {
                    if (match != null || context.getLongValue("agentVersionId") <= 0) break;
                    match = context.getLong("agentVersionId");
                }
            }
            if (match == null || match <= 0) throw new IllegalArgumentException("frozen target agent context is missing");
            return match;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("scheduled run snapshot is invalid for guidance", invalid);
        }
    }

    private DispatchDO latestForAgent(List<DispatchDO> dispatches, long targetAgentId) {
        DispatchDO latest = null;
        if (dispatches == null) {
            return null;
        }
        for (DispatchDO dispatch : dispatches) {
            if (!Objects.equals(dispatch.getAgentId(), targetAgentId)) {
                continue;
            }
            if ("SIDE_INTERACTION".equals(dispatch.getResumeMode())) {
                continue;
            }
            if (latest == null || dispatch.getId() > latest.getId()) {
                latest = dispatch;
            }
        }
        return latest;
    }
}
