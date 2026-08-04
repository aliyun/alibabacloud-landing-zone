package com.aliyun.autowonder.guidance;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchService;
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
    public void createForComment(long tenantId, long workitemId, long commentId, String contentMd,
            List<Long> explicitTargetAgentIds, long creatorId) {
        List<Long> targetAgentIds = explicitTargetAgentIds == null ? List.of()
                : explicitTargetAgentIds.stream().filter(Objects::nonNull).distinct().toList();
        if (targetAgentIds.isEmpty()) {
            targetAgentIds = resolveLeadingMention(tenantId, workitemId, contentMd);
        }
        for (Long targetAgentId : targetAgentIds) {
            create(tenantId, workitemId, commentId, targetAgentId, creatorId, contentMd);
        }
    }

    private List<Long> resolveLeadingMention(long tenantId, long workitemId, String contentMd) {
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
        List<Long> matches = workitemService.getParticipants(workitemId, tenantId).stream()
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
        List<AgentDO> tenantMatches = safeList(agentDao.findByExactName(tenantId, mentionName)).stream()
                .filter(agent -> agent != null && Objects.equals(agent.getTenantId(), tenantId)
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
    public GuidanceDO create(long tenantId, long workitemId, long commentId, long targetAgentId,
            long creatorId) {
        return create(tenantId, workitemId, commentId, targetAgentId, creatorId, null);
    }

    private GuidanceDO create(long tenantId, long workitemId, long commentId, long targetAgentId,
            long creatorId, String commentContentMd) {
        WorkitemDO workitem = workitemDao.findById(workitemId);
        if (workitem == null || !Objects.equals(workitem.getTenantId(), tenantId)) {
            throw new IllegalArgumentException("workitem does not belong to tenant");
        }
        AgentDO agent = agentDao.findById(targetAgentId);
        if (agent == null || !Objects.equals(agent.getTenantId(), tenantId)) {
            throw new IllegalArgumentException("target agent does not belong to tenant");
        }
        requireComment(tenantId, workitemId, commentId);

        GuidanceDO guidance = new GuidanceDO();
        guidance.setTenantId(tenantId);
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
        List<DispatchDO> dispatches = dispatchDao.listByWorkitem(tenantId, workitemId);
        DispatchDO prior = latestForAgent(dispatches, targetAgentId);
        Long sdlcId = sdlcResolver.resolveSdlcId(tenantId, targetAgentId);
        SdlcStepDO firstStep = sdlcId == null ? null : sdlcResolver.firstStep(tenantId, sdlcId);
        if (prior == null && !hasWorkerHistory(dispatches, targetAgentId)
                && isMentionOnly(commentContentMd, agent.getName())
                && sdlcId != null && firstStep != null && firstStep.getId() != null) {
            // A worker with no delivery history on this workitem cannot resume a
            // conversation. Treat its first @ mention as a formal SDLC start.
            workitemService.rebindForInteractionRework(tenantId, workitemId, targetAgentId,
                    sdlcId, firstStep.getId(), creatorId);
            var acknowledgement = workitemService.addAgentComment(workitemId,
                    FORMAL_WORKFLOW_ACKNOWLEDGEMENT, tenantId, targetAgentId);
            if (acknowledgement == null || acknowledgement.getId() == null
                    || guidanceDao.bindReplyComment(guidance.getId(), tenantId,
                    acknowledgement.getId()) != 1) {
                throw new IllegalStateException("failed to record formal workflow acknowledgement");
            }
            DispatchDO formal = dispatchService.enqueue(tenantId, workitemId, firstStep.getId(),
                    targetAgentId, 1, creatorId);
            guidance.setDispatchId(formal.getId());
            if (guidanceDao.bindPendingDispatch(guidance.getId(), tenantId, formal.getId()) != 1) {
                throw new IllegalStateException("failed to bind formal worker dispatch");
            }
            guidance.setStatus(GuidanceStatus.APPLIED);
            guidanceDao.updateStatus(guidance.getId(), tenantId, GuidanceStatus.APPLIED, null);
            eventPublisher.publishEvent(new GuidanceDispatchQueuedEvent(tenantId, formal.getId()));
            return guidance;
        }
        boolean forkSourceSession = prior != null
                && ACTIVE_TURN_STATUSES.contains(prior.getStatus())
                && dispatchService.hasResumableSession(tenantId, prior.getId());
        DispatchDO interaction = dispatchService.enqueueCommentInteraction(
                tenantId, workitemId, targetAgentId, prior == null ? null : prior.getId(),
                forkSourceSession, firstStep == null ? null : firstStep.getId(), guidance.getId(), creatorId);
        if (interaction != null) {
            guidance.setDispatchId(interaction.getId());
            if (guidanceDao.bindPendingDispatch(guidance.getId(), tenantId, interaction.getId()) != 1) {
                throw new IllegalStateException("failed to bind comment interaction dispatch");
            }
            eventPublisher.publishEvent(new GuidanceDispatchQueuedEvent(tenantId, interaction.getId()));
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
    public void deliverQueuedForDispatch(long tenantId, long dispatchId) {
        DispatchDO dispatch = dispatchDao.findById(dispatchId);
        if (dispatch != null && Objects.equals(dispatch.getTenantId(), tenantId)) {
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
            WorkitemCommentDO comment = requireComment(dispatch.getTenantId(), dispatch.getWorkitemId(),
                    guidance.getCommentId());
            transport.send(guidance, comment.getContentMd());
        }
    }

    public void redeliverUnacknowledged(long tenantId, long executorId) {
        for (GuidanceDO guidance : safeList(guidanceDao.listDeliveredForExecutor(tenantId, executorId))) {
            WorkitemCommentDO comment = requireComment(tenantId, guidance.getWorkitemId(),
                    guidance.getCommentId());
            transport.send(guidance, comment.getContentMd());
        }
    }

    @Transactional
    public void acknowledge(long tenantId, long executorId, long guidanceId, String status, String error,
            String replyMarkdown) {
        if (!Set.of(GuidanceStatus.APPLIED, GuidanceStatus.FAILED).contains(status)) {
            throw new IllegalArgumentException("invalid guidance status");
        }
        int updated = guidanceDao.acknowledge(guidanceId, tenantId, executorId, status, error);
        if (updated != 1) {
            return;
        }
        GuidanceDO guidance = guidanceDao.findById(guidanceId);
        if (guidance == null || !Objects.equals(guidance.getTenantId(), tenantId)) {
            throw new IllegalStateException("acknowledged guidance is missing");
        }
        if (GuidanceStatus.FAILED.equals(status)) {
            DispatchDO dispatch = guidance.getDispatchId() == null
                    ? null : dispatchDao.findById(guidance.getDispatchId());
            if (dispatch == null
                    || !Objects.equals(dispatch.getTenantId(), tenantId)
                    || !Set.of("SIDE_INTERACTION", "CANONICAL_INTERACTION").contains(dispatch.getResumeMode())) {
                throw new IllegalStateException("guidance interaction dispatch is missing");
            }
            if (!dispatchService.onResult(tenantId, executorId, dispatch.getId(), false,
                    null, error, false)) {
                throw new IllegalStateException("failed to terminate guidance interaction dispatch");
            }
            return;
        }
        if (replyMarkdown != null && !replyMarkdown.isBlank()) {
            var reply = workitemService.addAgentComment(guidance.getWorkitemId(), replyMarkdown, tenantId,
                    guidance.getTargetAgentId());
            if (reply == null || reply.getId() == null
                    || guidanceDao.bindReplyComment(guidanceId, tenantId, reply.getId()) != 1) {
                throw new IllegalStateException("failed to bind side interaction reply comment");
            }
        }
    }

    @Transactional
    public void requeueDeliveredForDispatch(long tenantId, long dispatchId) {
        guidanceDao.requeueDeliveredForDispatch(tenantId, dispatchId);
    }

    @Transactional
    public void requeueForExecutorFailover(long tenantId, long dispatchId) {
        guidanceDao.requeueForExecutorFailover(tenantId, dispatchId);
    }

    @Transactional
    public void failForDispatch(long tenantId, long dispatchId, String error) {
        guidanceDao.failForDispatch(tenantId, dispatchId, error);
    }

    public void attachInteractionStatuses(long tenantId, long workitemId, List<TimelineItemVO> timeline) {
        Map<Long, TimelineItemVO> comments = new LinkedHashMap<>();
        for (TimelineItemVO item : timeline) {
            if (item != null && "comment".equals(item.getType())) {
                comments.put(item.getId(), item);
            }
        }
        List<GuidanceDO> rows = guidanceDao.listByWorkitem(tenantId, workitemId);
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

    private WorkitemCommentDO requireComment(long tenantId, long workitemId, long commentId) {
        WorkitemCommentDO comment = commentDao.findById(tenantId, commentId);
        if (comment == null || !Objects.equals(comment.getWorkitemId(), workitemId)) {
            throw new IllegalArgumentException("guidance comment does not belong to workitem");
        }
        return comment;
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
