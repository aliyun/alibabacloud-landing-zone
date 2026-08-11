package com.aliyun.autowonder.workitem;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.dispatch.AgentSdlcResolver;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDO;
import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao;
import com.aliyun.autowonder.dispatch.DispatchService;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import com.aliyun.autowonder.dispatch.WorkitemAssignedEvent;
import com.aliyun.autowonder.executor.ExecutorDO;
import com.aliyun.autowonder.executor.ExecutorDao;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.filter.BizLoggerFilter;
import com.aliyun.autowonder.guidance.GuidanceDO;
import com.aliyun.autowonder.guidance.GuidanceDao;
import com.aliyun.autowonder.im.notification.WorkitemCommentMentionedEvent;
import com.aliyun.autowonder.im.notification.WorkitemHumanAssignedEvent;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao;
import com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDO;
import com.aliyun.autowonder.integration.event.WorkitemCommentCreatedEvent;
import com.aliyun.autowonder.integration.event.WorkitemContentUpdatedEvent;
import com.aliyun.autowonder.integration.event.WorkitemStatusChangedEvent;
import com.aliyun.autowonder.org.OrgMemberDO;
import com.aliyun.autowonder.org.OrgMemberDao;
import com.aliyun.autowonder.sdlc.SdlcDO;
import com.aliyun.autowonder.sdlc.SdlcDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.squad.SquadMemberDO;
import com.aliyun.autowonder.squad.SquadMemberDao;
import com.aliyun.autowonder.statemachine.StatusNodeDO;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.statemachine.StatusTemplateDO;
import com.aliyun.autowonder.statemachine.StatusTemplateDao;
import com.aliyun.autowonder.statemachine.StatusTransitionDao;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.websocket.PresenceManager;
import com.aliyun.autowonder.workitem.dto.CommentVO;
import com.aliyun.autowonder.workitem.dto.AgentDeliveryProgressVO;
import com.aliyun.autowonder.workitem.dto.CreateWorkitemRequest;
import com.aliyun.autowonder.workitem.dto.DeliveryProgressVO;
import com.aliyun.autowonder.workitem.dto.DeliveryStepVO;
import com.aliyun.autowonder.workitem.dto.DispatchAttemptVO;
import com.aliyun.autowonder.workitem.dto.EventVO;
import com.aliyun.autowonder.workitem.dto.ParticipantVO;
import com.aliyun.autowonder.workitem.dto.ProcessGraphEdgeVO;
import com.aliyun.autowonder.workitem.dto.ProcessGraphNodeVO;
import com.aliyun.autowonder.workitem.dto.ProcessGraphVO;
import com.aliyun.autowonder.workitem.dto.SubStepVO;
import com.aliyun.autowonder.workitem.dto.TimelineItemVO;
import com.aliyun.autowonder.workitem.dto.WorkitemVO;
import com.aliyun.autowonder.workitem.dto.WorkflowPlanStepVO;
import com.aliyun.autowonder.workitem.dto.WorkflowPlanVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class WorkitemService {

    private static final Set<String> WORK_TYPES = Set.of("REQ", "TASK", "BUG");
    private static final Set<String> ACTIVE_DISPATCH_STATUSES = Set.of(
            DispatchStatus.PACKAGING, DispatchStatus.DISPATCHED, DispatchStatus.ACKED, DispatchStatus.RUNNING);
    private static final String SOURCE_TYPE_NATIVE = "NATIVE";
    private static final String SOURCE_TYPE_EXTERNAL = "EXTERNAL";
    private static final long SYSTEM_USER_ID = 0L;

    private final WorkitemDao workitemDao;
    private final WorkitemCommentDao commentDao;
    private final WorkitemCommentMentionDao commentMentionDao;
    private final WorkitemEventDao eventDao;
    private final StatusTemplateDao templateDao;
    private final StatusNodeDao nodeDao;
    private final StatusTransitionDao transitionDao;
    private final SdlcDao sdlcDao;
    private final SdlcStepDao stepDao;
    private final DispatchDao dispatchDao;
    private final DispatchRuntimeEventDao runtimeEventDao;
    private final AgentDao agentDao;
    private final AgentSdlcResolver sdlcResolver;
    private final SquadMemberDao squadMemberDao;
    private final ExecutorDao executorDao;
    private final UserDao userDao;
    private final OrgMemberDao orgMemberDao;
    private final GuidanceDao guidanceDao;
    private final PresenceManager presenceManager;
    private final ExternalWorkitemLinkDao externalWorkitemLinkDao;
    private final ApplicationEventPublisher eventPublisher;

    /** A non-terminal (running) dispatch idle longer than this is treated as stalled. Default 60m. */
    @Value("${autowonder.workitem.stuck-threshold-ms:3600000}")
    private long stuckThresholdMs = 3600000L;

    @Autowired
    public WorkitemService(WorkitemDao workitemDao, WorkitemCommentDao commentDao,
                           WorkitemCommentMentionDao commentMentionDao,
                           WorkitemEventDao eventDao, StatusTemplateDao templateDao,
                           StatusNodeDao nodeDao, StatusTransitionDao transitionDao,
                           SdlcDao sdlcDao, SdlcStepDao stepDao,
                           DispatchDao dispatchDao, DispatchRuntimeEventDao runtimeEventDao, AgentDao agentDao,
                           AgentSdlcResolver sdlcResolver,
                           SquadMemberDao squadMemberDao,
                           ExecutorDao executorDao,
                           UserDao userDao,
                           OrgMemberDao orgMemberDao,
                           GuidanceDao guidanceDao,
                           PresenceManager presenceManager,
                           ExternalWorkitemLinkDao externalWorkitemLinkDao,
                           ApplicationEventPublisher eventPublisher) {
        this.workitemDao = workitemDao;
        this.commentDao = commentDao;
        this.commentMentionDao = commentMentionDao;
        this.eventDao = eventDao;
        this.templateDao = templateDao;
        this.nodeDao = nodeDao;
        this.transitionDao = transitionDao;
        this.sdlcDao = sdlcDao;
        this.stepDao = stepDao;
        this.dispatchDao = dispatchDao;
        this.runtimeEventDao = runtimeEventDao;
        this.agentDao = agentDao;
        this.sdlcResolver = sdlcResolver;
        this.squadMemberDao = squadMemberDao;
        this.executorDao = executorDao;
        this.userDao = userDao;
        this.orgMemberDao = orgMemberDao;
        this.guidanceDao = guidanceDao;
        this.presenceManager = presenceManager;
        this.externalWorkitemLinkDao = externalWorkitemLinkDao;
        this.eventPublisher = eventPublisher;
    }

    WorkitemService(WorkitemDao workitemDao, WorkitemCommentDao commentDao,
                    WorkitemEventDao eventDao, StatusTemplateDao templateDao,
                    StatusNodeDao nodeDao, StatusTransitionDao transitionDao,
                    SdlcDao sdlcDao, SdlcStepDao stepDao,
                    DispatchDao dispatchDao, DispatchRuntimeEventDao runtimeEventDao, AgentDao agentDao,
                    AgentSdlcResolver sdlcResolver,
                    SquadMemberDao squadMemberDao,
                    ExecutorDao executorDao,
                    UserDao userDao,
                    GuidanceDao guidanceDao,
                    PresenceManager presenceManager,
                    ExternalWorkitemLinkDao externalWorkitemLinkDao,
                    ApplicationEventPublisher eventPublisher) {
        this(workitemDao, commentDao, null, eventDao, templateDao, nodeDao, transitionDao,
                sdlcDao, stepDao, dispatchDao, runtimeEventDao, agentDao, sdlcResolver,
                squadMemberDao, executorDao, userDao, null, guidanceDao, presenceManager,
                externalWorkitemLinkDao, eventPublisher);
    }

    @Transactional
    public WorkitemVO create(CreateWorkitemRequest req, long tenantId, long userId) {
        if (req.getWorkType() == null || !WORK_TYPES.contains(req.getWorkType())) {
            throw new BizException(ErrorCode.WORK_TYPE_INVALID);
        }
        StatusTemplateDO template = templateDao.findDefaultByType(req.getWorkType());
        if (template == null) {
            throw new BizException(ErrorCode.STATUS_TEMPLATE_NOT_FOUND);
        }
        StatusNodeDO init = nodeDao.findInitNode(template.getId());
        if (init == null) {
            throw new BizException(ErrorCode.STATUS_TEMPLATE_NOT_FOUND);
        }
        WorkitemDO w = new WorkitemDO();
        w.setTenantId(tenantId);
        w.setWorkType(req.getWorkType());
        w.setTitle(req.getTitle());
        w.setContentMd(req.getContentMd());
        w.setTemplateId(template.getId());
        w.setStatusNodeId(init.getId());
        // Default the assignee to the creator; an explicit assignee on the request is
        // applied below via assign(...) so delivery start (squad validation, SDLC
        // binding, assign-operator, ASSIGN event, WorkitemAssignedEvent) matches a
        // separate post-create assignment exactly.
        w.setAssigneeType("HUMAN");
        w.setAssigneeRef(userId);
        w.setPriority(req.getPriority() == null ? 2 : req.getPriority());
        w.setCreatorId(userId);
        w.setVersion(0);
        workitemDao.insert(w);

        writeEvent(tenantId, w.getId(), "CREATE", null, init.getCode(), "HUMAN", userId);
        if (req.getAssigneeType() != null) {
            return assign(w.getId(), req.getAssigneeType(), req.getAssigneeRef(),
                    req.getSdlcId(), req.getSquadId(), tenantId, userId);
        }
        return toVO(w);
    }

    public WorkitemVO get(long id) {
        WorkitemDO w = workitemDao.findById(id);
        if (w == null) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
        return toVO(w);
    }

    public PageResult<WorkitemVO> list(String workType, Long statusNodeId,
                                      String assigneeType, Long assigneeRef, boolean pendingDecisionOnly,
                                      String mineScope,
                                      long tenantId, long currentUserId, String keyword, int page, int size) {
        int p = page < 1 ? 1 : page;
        int s = size < 1 ? 20 : Math.min(size, 200);
        int offset = (p - 1) * s;
        String trimmed = keyword == null ? null : keyword.trim();
        String effectiveKeyword = (trimmed != null && !trimmed.isEmpty()) ? trimmed : null;
        Long keywordId = null;
        if (effectiveKeyword != null && effectiveKeyword.matches("\\d+")) {
            try {
                keywordId = Long.parseLong(effectiveKeyword);
            } catch (NumberFormatException ignored) {
            }
        }
        long total = workitemDao.count(tenantId, workType, statusNodeId, assigneeType, assigneeRef,
                pendingDecisionOnly, mineScope, currentUserId, effectiveKeyword, keywordId);
        List<WorkitemDO> rows = workitemDao.list(tenantId, workType, statusNodeId, assigneeType, assigneeRef,
                pendingDecisionOnly, mineScope, currentUserId, effectiveKeyword, keywordId, offset, s);
        Map<Long, DispatchDO> latestByWorkitem = loadLatestDispatches(rows);

        Set<Long> humanIds = new HashSet<>();
        Set<Long> agentIds = new HashSet<>();
        Set<Long> nodeIds = new HashSet<>();
        Set<Long> sdlcIds = new HashSet<>();
        List<Long> workitemIds = new ArrayList<>();
        Long tenantIdObj = null;
        for (WorkitemDO w : rows) {
            if (w.getId() != null) {
                workitemIds.add(w.getId());
            }
            if (tenantIdObj == null && w.getTenantId() != null) {
                tenantIdObj = w.getTenantId();
            }
            if ("HUMAN".equals(w.getAssigneeType()) && w.getAssigneeRef() != null) {
                humanIds.add(w.getAssigneeRef());
            } else if ("AGENT".equals(w.getAssigneeType()) && w.getAssigneeRef() != null) {
                agentIds.add(w.getAssigneeRef());
            }
            if (w.getCreatorId() != null) {
                humanIds.add(w.getCreatorId());
            }
            if (w.getStatusNodeId() != null) {
                nodeIds.add(w.getStatusNodeId());
            }
            if (w.getSdlcId() != null) {
                sdlcIds.add(w.getSdlcId());
            }
        }

        Map<Long, UserDO> userMap = humanIds.isEmpty() ? Map.of()
                : safeList(userDao.listByIds(humanIds)).stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(UserDO::getId, u -> u, (a, b) -> a));
        Map<Long, AgentDO> agentMap = agentIds.isEmpty() || tenantIdObj == null ? Map.of()
                : safeList(agentDao.listByIds(tenantIdObj, agentIds)).stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(AgentDO::getId, a -> a, (a, b) -> a));
        Map<Long, StatusNodeDO> nodeMap = nodeIds.isEmpty() ? Map.of()
                : safeList(nodeDao.listByIds(nodeIds)).stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(StatusNodeDO::getId, n -> n, (a, b) -> a));
        Map<Long, SdlcDO> sdlcMap = sdlcIds.isEmpty() ? Map.of()
                : safeList(sdlcDao.listByIds(sdlcIds)).stream()
                    .filter(Objects::nonNull)
                    .collect(Collectors.toMap(SdlcDO::getId, s2 -> s2, (a, b) -> a));
        Map<Long, List<ExternalWorkitemLinkDO>> extLinksMap = workitemIds.isEmpty() || tenantIdObj == null
                ? Map.of()
                : safeList(externalWorkitemLinkDao.listByWorkitemIds(tenantIdObj, workitemIds)).stream()
                    .filter(Objects::nonNull)
                    .filter(link -> link.getWorkitemId() != null)
                    .collect(Collectors.groupingBy(ExternalWorkitemLinkDO::getWorkitemId));
        Map<Long, List<DispatchDO>> allDispatchesByWorkitem = loadAllDispatches(workitemIds);

        long now = System.currentTimeMillis();
        List<WorkitemVO> result = new ArrayList<>();
        for (WorkitemDO w : rows) {
            WorkitemVO vo = toVO(w, userMap, agentMap, nodeMap, sdlcMap);
            DispatchDO latest = latestByWorkitem.get(w.getId());
            applyPendingDecision(vo, w, latest, nodeMap);
            applyHealth(vo, w, latest, now, nodeMap);
            applyDeleteEligibility(vo, w, extLinksMap, allDispatchesByWorkitem);
            result.add(vo);
        }
        return new PageResult<>(result, total, p, s);
    }

    private Map<Long, DispatchDO> loadLatestDispatches(List<WorkitemDO> rows) {
        List<Long> ids = rows.stream()
                .map(WorkitemDO::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<Long, DispatchDO> byWorkitem = new HashMap<>();
        for (DispatchDO d : safeList(dispatchDao.listLatestByWorkitemIds(ids))) {
            if (d != null && d.getWorkitemId() != null) {
                byWorkitem.put(d.getWorkitemId(), d);
            }
        }
        return byWorkitem;
    }

    private Map<Long, List<DispatchDO>> loadAllDispatches(List<Long> workitemIds) {
        if (workitemIds.isEmpty()) {
            return Map.of();
        }
        return safeList(dispatchDao.listByWorkitemIds(workitemIds)).stream()
                .filter(Objects::nonNull)
                .filter(d -> d.getWorkitemId() != null)
                .collect(Collectors.groupingBy(DispatchDO::getWorkitemId));
    }

    private void applyHealth(WorkitemVO vo, WorkitemDO w, DispatchDO latest, long now) {
        // No dispatch => nothing could have stalled/failed; treat as healthy without an extra status lookup.
        if (latest == null || w.getStatusNodeId() == null) {
            vo.setHealth(WorkitemHealthEvaluator.OK);
            return;
        }
        StatusNodeDO node = nodeDao.findById(w.getStatusNodeId());
        String category = node == null ? null : node.getCategory();
        WorkitemHealthEvaluator.Result r =
                WorkitemHealthEvaluator.evaluate(category, latest, now, stuckThresholdMs);
        vo.setHealth(r.health());
        vo.setHealthReason(r.reason());
    }

    private void applyHealth(WorkitemVO vo, WorkitemDO w, DispatchDO latest, long now,
                             Map<Long, StatusNodeDO> nodeMap) {
        if (latest == null || w.getStatusNodeId() == null) {
            vo.setHealth(WorkitemHealthEvaluator.OK);
            return;
        }
        StatusNodeDO node = nodeMap.get(w.getStatusNodeId());
        String category = node == null ? null : node.getCategory();
        WorkitemHealthEvaluator.Result r =
                WorkitemHealthEvaluator.evaluate(category, latest, now, stuckThresholdMs);
        vo.setHealth(r.health());
        vo.setHealthReason(r.reason());
    }

    private void applyPendingDecision(WorkitemVO vo, WorkitemDO w, DispatchDO latest) {
        boolean successfulHumanHandoff = "HUMAN".equals(w.getAssigneeType())
                && latest != null
                && DispatchStatus.SUCCEEDED.equals(latest.getStatus());
        vo.setPendingDecision(successfulHumanHandoff && !isDoneStatus(w.getStatusNodeId(), vo.getStatusName()));
    }

    private void applyPendingDecision(WorkitemVO vo, WorkitemDO w, DispatchDO latest,
                                      Map<Long, StatusNodeDO> nodeMap) {
        boolean successfulHumanHandoff = "HUMAN".equals(w.getAssigneeType())
                && latest != null
                && DispatchStatus.SUCCEEDED.equals(latest.getStatus());
        vo.setPendingDecision(successfulHumanHandoff
                && !isDoneStatus(w.getStatusNodeId(), vo.getStatusName(), nodeMap));
    }

    private boolean isDoneStatus(Long statusNodeId, String statusName) {
        StatusNodeDO node = statusNodeId == null ? null : nodeDao.findById(statusNodeId);
        if (isDoneNode(node)) {
            return true;
        }
        return containsDoneToken(statusName);
    }

    private boolean isDoneStatus(Long statusNodeId, String statusName, Map<Long, StatusNodeDO> nodeMap) {
        StatusNodeDO node = statusNodeId == null ? null : nodeMap.get(statusNodeId);
        if (isDoneNode(node)) {
            return true;
        }
        return containsDoneToken(statusName);
    }

    private boolean isDoneNode(StatusNodeDO node) {
        return node != null && ("DONE".equalsIgnoreCase(node.getCategory())
                || containsDoneToken(node.getCode()) || containsDoneToken(node.getName()));
    }

    private boolean containsDoneToken(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String s = value.toUpperCase();
        return s.contains("完成") || s.contains("关闭") || s.contains("发布")
                || s.contains("DONE") || s.contains("CLOSED") || s.contains("RELEASED")
                || s.contains("PUBLISHED");
    }

    @Transactional
    public WorkitemVO transition(long id, long toNodeId, long tenantId, long userId) {
        WorkitemDO w = workitemDao.findById(id);
        if (w == null) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
        long fromNodeId = w.getStatusNodeId();
        if (transitionDao.findByTemplateFromTo(w.getTemplateId(), fromNodeId, toNodeId) == null) {
            throw new BizException(ErrorCode.ILLEGAL_TRANSITION);
        }
        StatusNodeDO fromNode = nodeDao.findById(fromNodeId);
        StatusNodeDO toNode = nodeDao.findById(toNodeId);
        int rows = workitemDao.updateStatus(id, tenantId, toNodeId, w.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.WORKITEM_VERSION_CONFLICT);
        }
        writeEvent(tenantId, id, "STATUS_CHANGE",
                fromNode == null ? null : fromNode.getCode(),
                toNode == null ? null : toNode.getCode(), "HUMAN", userId);
        eventPublisher.publishEvent(new WorkitemStatusChangedEvent(tenantId, id, toNodeId, userId));
        return toVO(workitemDao.findById(id));
    }

    @Transactional
    public WorkitemVO agentTransition(long id, String toStatusCode, long tenantId, long agentId) {
        WorkitemDO w = workitemDao.findById(id);
        if (w == null || !Long.valueOf(tenantId).equals(w.getTenantId())) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
        StatusNodeDO toNode = nodeDao.findByTemplateAndCode(w.getTemplateId(), toStatusCode);
        if (toNode == null) {
            throw new BizException(ErrorCode.ILLEGAL_TRANSITION);
        }
        return agentTransition(w, toNode, tenantId, agentId);
    }

    private WorkitemVO agentTransition(WorkitemDO w, StatusNodeDO toNode,
                                       long tenantId, long agentId) {
        long id = w.getId();
        long fromNodeId = w.getStatusNodeId();
        if (transitionDao.findByTemplateFromTo(w.getTemplateId(), fromNodeId, toNode.getId()) == null) {
            throw new BizException(ErrorCode.ILLEGAL_TRANSITION);
        }
        StatusNodeDO fromNode = nodeDao.findById(fromNodeId);
        int rows = workitemDao.updateStatus(id, tenantId, toNode.getId(), w.getVersion(), agentId);
        if (rows == 0) {
            throw new BizException(ErrorCode.WORKITEM_VERSION_CONFLICT);
        }
        writeEvent(tenantId, id, "STATUS_CHANGE",
                fromNode == null ? null : fromNode.getCode(),
                toNode.getCode(), "AGENT", agentId);
        return toVO(workitemDao.findById(id));
    }

    @Transactional
    public WorkitemVO assign(long id, String assigneeType, Long assigneeRef, Long sdlcId, Long squadId,
                             long tenantId, long userId) {
        return assignAs(id, assigneeType, assigneeRef, sdlcId, squadId, tenantId, userId,
                AssignmentActor.human(userId, resolveHumanName(userId)));
    }

    @Transactional
    public WorkitemVO assignAs(long id, String assigneeType, Long assigneeRef, Long sdlcId, Long squadId,
                               long tenantId, long modifierUserId, AssignmentActor actor) {
        WorkitemDO w = workitemDao.findById(id);
        if (w == null || !Long.valueOf(tenantId).equals(w.getTenantId())) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
        if (Objects.equals(w.getAssigneeType(), assigneeType) && Objects.equals(w.getAssigneeRef(), assigneeRef)) {
            return toVO(w);
        }
        AssignmentActor effectiveActor = actor == null
                ? AssignmentActor.system("系统")
                : actor;
        validateSquadMember(assigneeType, assigneeRef, squadId, tenantId);
        int rows = workitemDao.updateAssignee(id, tenantId, assigneeType, assigneeRef,
                w.getVersion(), modifierUserId);
        if (rows == 0) {
            throw new BizException(ErrorCode.WORKITEM_VERSION_CONFLICT);
        }
        String fromVal = w.getAssigneeRef() == null ? null : String.valueOf(w.getAssigneeRef());
        String toVal = assigneeRef == null ? null : String.valueOf(assigneeRef);
        WorkitemEventDO assignEvent = new WorkitemEventDO();
        assignEvent.setTenantId(tenantId);
        assignEvent.setWorkitemId(id);
        assignEvent.setEventType("ASSIGN");
        assignEvent.setFromVal(fromVal);
        assignEvent.setToVal(toVal);
        assignEvent.setActorType(effectiveActor.type());
        assignEvent.setActorRef(effectiveActor.ref());
        assignEvent.setDetailJson(assignDetailJson(w.getAssigneeType(), assigneeType));
        eventDao.insert(assignEvent);

        // First-time delivery start: bind chosen SDLC + its first step.
        // Guard on w.getSdlcId()==null so a mid-flight currentStepId is never reset.
        if ("AGENT".equals(assigneeType) && assigneeRef != null
                && w.getSdlcId() == null && w.getCurrentStepId() == null) {
            Long effectiveSdlcId = sdlcId != null ? sdlcId
                    : resolveAgentSdlcId(assigneeRef, tenantId, w.getWorkType());
            bindSdlc(id, tenantId, effectiveSdlcId, w.getVersion() + 1, modifierUserId);
        }

        WorkitemDO reloaded = workitemDao.findById(id);

        // Record the real human who triggered the assignment (assign-operator) so a later
        // handoff with no resolvable next-hop can fall back to them. SYSTEM-initiated assigns
        // (agent->agent handoffs) must not overwrite the human operator.
        if (effectiveActor.isHuman() && modifierUserId != SYSTEM_USER_ID && reloaded != null
                && !java.util.Objects.equals(reloaded.getAssignOperatorId(), modifierUserId)) {
            workitemDao.updateAssignOperator(id, tenantId, modifierUserId, reloaded.getVersion(), modifierUserId);
        }

        if ("AGENT".equals(assigneeType) && assigneeRef != null) {
            eventPublisher.publishEvent(new WorkitemAssignedEvent(
                    tenantId, id, reloaded.getCurrentStepId(), assigneeRef,
                    reloaded.getVersion(), modifierUserId));
        }
        if ("HUMAN".equals(assigneeType) && assigneeRef != null
                && assignEvent.getId() != null
                && !(effectiveActor.isHuman() && effectiveActor.ref() == assigneeRef)) {
            eventPublisher.publishEvent(new WorkitemHumanAssignedEvent(
                    tenantId,
                    id,
                    reloaded == null ? w.getTitle() : reloaded.getTitle(),
                    assignEvent.getId(),
                    assigneeRef,
                    effectiveActor.type(),
                    effectiveActor.ref(),
                    effectiveActor.displayName(),
                    currentRequestId()));
        }
        return toVO(reloaded);
    }

    /**
     * Atomically moves the authoritative workitem cursor to the worker/step selected by
     * a comment-triggered rework. This intentionally does not publish WorkitemAssignedEvent:
     * the already-created COMMENT_REWORK dispatch is the single dispatch authority.
     */
    @Transactional
    public void rebindForInteractionRework(long tenantId, long workitemId, long targetAgentId,
            long targetSdlcId, long targetStepId, long userId) {
        for (int retry = 0; retry < 3; retry++) {
            WorkitemDO current = workitemDao.findById(workitemId);
            if (current == null || current.getTenantId() != tenantId) {
                throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
            }
            boolean routeMatches = Objects.equals(current.getSdlcId(), targetSdlcId)
                    && Objects.equals(current.getCurrentStepId(), targetStepId);
            if (!routeMatches) {
                if (workitemDao.updateSdlcAndStep(workitemId, tenantId, targetSdlcId, targetStepId,
                        current.getVersion(), userId) == 0) {
                    continue;
                }
                current = workitemDao.findById(workitemId);
                if (current == null) {
                    throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
                }
            }
            if ("AGENT".equals(current.getAssigneeType())
                    && Objects.equals(current.getAssigneeRef(), targetAgentId)) {
                return;
            }
            Long fromRef = current.getAssigneeRef();
            String fromType = current.getAssigneeType();
            if (workitemDao.updateAssignee(workitemId, tenantId, "AGENT", targetAgentId,
                    current.getVersion(), userId) == 0) {
                continue;
            }
            writeEvent(tenantId, workitemId, "ASSIGN",
                    fromRef == null ? null : String.valueOf(fromRef), String.valueOf(targetAgentId),
                    "SYSTEM", userId, assignDetailJson(fromType, "AGENT"));
            return;
        }
        throw new BizException(ErrorCode.WORKITEM_VERSION_CONFLICT);
    }

    private void bindSdlc(long workitemId, long tenantId, long sdlcId, int version, long userId) {
        SdlcDO sdlc = sdlcDao.findById(sdlcId);
        if (sdlc == null || !Long.valueOf(tenantId).equals(sdlc.getTenantId())) {
            throw new BizException(ErrorCode.SDLC_NOT_FOUND);
        }
        SdlcStepDO first = sdlcResolver.firstStep(tenantId, sdlcId);
        if (first == null) {
            throw new BizException(ErrorCode.SDLC_STEP_NOT_FOUND);
        }
        int rows = workitemDao.updateSdlcAndStep(workitemId, tenantId, sdlcId, first.getId(), version, userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.WORKITEM_VERSION_CONFLICT);
        }
    }

    private Long resolveAgentSdlcId(long agentId, long tenantId, String workType) {
        AgentDO agent = agentDao.findById(agentId);
        if (agent == null || !Long.valueOf(tenantId).equals(agent.getTenantId())) {
            throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        }
        Long sdlcId = sdlcResolver.resolveSdlcId(tenantId, agentId);
        if (sdlcId != null) {
            return sdlcId;
        }
        SdlcDO fallback = workType == null || workType.isBlank() ? null : sdlcDao.findDefault(workType);
        if (fallback != null && fallback.getId() != null
                && Long.valueOf(tenantId).equals(fallback.getTenantId())) {
            return fallback.getId();
        }
        throw new BizException(ErrorCode.SDLC_NOT_FOUND);
    }

    private void validateSquadMember(String assigneeType, Long assigneeRef, Long squadId, long tenantId) {
        if (!"AGENT".equals(assigneeType) || assigneeRef == null || squadId == null) {
            return;
        }
        SquadMemberDO member = squadMemberDao.findBySquadAndAgent(squadId, assigneeRef);
        if (member == null || !Long.valueOf(tenantId).equals(member.getTenantId())) {
            throw new BizException(ErrorCode.SQUAD_NOT_FOUND);
        }
    }

    @Transactional
    public WorkitemVO updateContent(long id, String title, String contentMd, long tenantId, long userId) {
        WorkitemDO w = workitemDao.findById(id);
        if (w == null) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
        String effectiveTitle = title == null ? w.getTitle() : title;
        String effectiveContentMd = contentMd == null ? w.getContentMd() : contentMd;
        if (Objects.equals(w.getTitle(), effectiveTitle) && Objects.equals(w.getContentMd(), effectiveContentMd)) {
            return toVO(w);
        }
        int rows = workitemDao.updateContent(id, tenantId, effectiveTitle, effectiveContentMd, w.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.WORKITEM_VERSION_CONFLICT);
        }
        writeEvent(tenantId, id, "EDIT", null, null, "HUMAN", userId);
        eventPublisher.publishEvent(new WorkitemContentUpdatedEvent(tenantId, id, effectiveTitle, effectiveContentMd, userId));
        return toVO(workitemDao.findById(id));
    }

    @Transactional
    public void delete(long id, long tenantId, long userId) {
        WorkitemDO w = workitemDao.findById(id);
        if (w == null || !Long.valueOf(tenantId).equals(w.getTenantId())) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
        if (!externalWorkitemLinkDao.listByWorkitem(tenantId, id).isEmpty()) {
            throw new BizException(ErrorCode.WORKITEM_EXTERNAL_NO_DELETE);
        }
        List<DispatchDO> dispatches = safeList(dispatchDao.listByWorkitem(tenantId, id));
        boolean running = dispatches.stream()
                .anyMatch(d -> d != null && ACTIVE_DISPATCH_STATUSES.contains(d.getStatus()));
        if (running) {
            throw new BizException(ErrorCode.WORKITEM_RUNNING_NO_DELETE);
        }
        int rows = workitemDao.softDelete(id, tenantId, w.getVersion(), userId);
        if (rows == 0) {
            throw new BizException(ErrorCode.WORKITEM_VERSION_CONFLICT);
        }
        writeEvent(tenantId, id, "DELETE", null, null, "HUMAN", userId);
    }

    @Transactional
    public CommentVO addComment(long workitemId, String contentMd, long tenantId, long userId) {
        return addComment(workitemId, contentMd, List.of(), tenantId, userId);
    }

    @Transactional
    public CommentVO addComment(long workitemId, String contentMd, List<Long> targetHumanIds, long tenantId, long userId) {
        WorkitemDO w = workitemDao.findById(workitemId);
        if (w == null || tenantId != w.getTenantId()) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
        LinkedHashMap<Long, UserDO> targetHumans = resolveHumanMentions(tenantId, targetHumanIds);
        if (targetHumans.isEmpty()) {
            targetHumans = resolvePlainTextHumanMentions(workitemId, tenantId, contentMd);
        }
        WorkitemCommentDO c = new WorkitemCommentDO();
        c.setTenantId(tenantId);
        c.setWorkitemId(workitemId);
        c.setAuthorType("HUMAN");
        c.setAuthorRef(userId);
        c.setContentMd(contentMd);
        commentDao.insert(c);
        persistHumanMentions(tenantId, workitemId, c.getId(), targetHumans);
        writeEvent(tenantId, workitemId, "COMMENT", null, null, "HUMAN", userId);
        if (c.getId() != null) {
            eventPublisher.publishEvent(new WorkitemCommentCreatedEvent(tenantId, workitemId, c.getId(),
                    "HUMAN", userId, contentMd));
            publishHumanMentionEvents(tenantId, workitemId, w.getTitle(), c.getId(), targetHumans,
                    "HUMAN", userId, resolveActorDisplayName("HUMAN", userId), userId, contentMd);
        }
        return toCommentVO(c);
    }

    private LinkedHashMap<Long, UserDO> resolveHumanMentions(long tenantId, List<Long> targetHumanIds) {
        LinkedHashMap<Long, UserDO> humans = new LinkedHashMap<>();
        if (targetHumanIds == null) {
            return humans;
        }
        for (Long targetHumanId : targetHumanIds) {
            if (targetHumanId == null || humans.containsKey(targetHumanId)) {
                continue;
            }
            if (orgMemberDao == null) {
                continue;
            }
            OrgMemberDO member = orgMemberDao.findByOrgAndUser(tenantId, targetHumanId);
            if (member == null || member.getStatus() == null || member.getStatus() != 0) {
                throw new BizException(ErrorCode.ORG_NOT_MEMBER);
            }
            UserDO user = userDao.findById(targetHumanId);
            if (user == null) {
                throw new BizException(ErrorCode.ORG_NOT_MEMBER);
            }
            humans.put(targetHumanId, user);
        }
        return humans;
    }

    private void persistHumanMentions(long tenantId, long workitemId, Long commentId, LinkedHashMap<Long, UserDO> humans) {
        if (commentMentionDao == null || commentId == null || humans == null || humans.isEmpty()) {
            return;
        }
        for (Map.Entry<Long, UserDO> entry : humans.entrySet()) {
            WorkitemCommentMentionDO mention = new WorkitemCommentMentionDO();
            mention.setTenantId(tenantId);
            mention.setWorkitemId(workitemId);
            mention.setCommentId(commentId);
            mention.setTargetType("HUMAN");
            mention.setTargetRef(entry.getKey());
            mention.setDisplayNameSnapshot(resolveUserName(entry.getValue()));
            commentMentionDao.insert(mention);
        }
    }

    private void publishHumanMentionEvents(long tenantId, long workitemId, String workitemTitle, long commentId,
            LinkedHashMap<Long, UserDO> humans, String actorType, long actorRef, String actorDisplayName,
            Long skipUserId, String contentMd) {
        if (humans == null || humans.isEmpty()) {
            return;
        }
        for (Long recipientUserId : humans.keySet()) {
            if (recipientUserId == null || Objects.equals(recipientUserId, skipUserId)) {
                continue;
            }
            eventPublisher.publishEvent(new WorkitemCommentMentionedEvent(tenantId, workitemId, workitemTitle,
                    commentId, recipientUserId, actorType, actorRef, actorDisplayName, currentRequestId(), contentMd));
        }
    }

    private LinkedHashMap<Long, UserDO> resolvePlainTextHumanMentions(long workitemId, long tenantId, String contentMd) {
        LinkedHashMap<Long, UserDO> humans = new LinkedHashMap<>();
        if (contentMd == null || contentMd.isBlank()) {
            return humans;
        }
        Map<String, List<ParticipantVO>> byName = new LinkedHashMap<>();
        for (ParticipantVO candidate : getMentionCandidates(workitemId, tenantId, null, 100)) {
            if (candidate == null || !"HUMAN".equals(candidate.getTargetType())
                    || candidate.getUserId() == null || candidate.getName() == null || candidate.getName().isBlank()) {
                continue;
            }
            byName.computeIfAbsent(candidate.getName(), ignored -> new ArrayList<>()).add(candidate);
        }
        for (Map.Entry<String, List<ParticipantVO>> entry : byName.entrySet()) {
            if (entry.getValue().size() != 1 || !containsPlainMention(contentMd, entry.getKey())) {
                continue;
            }
            Long userId = entry.getValue().get(0).getUserId();
            UserDO user = userDao.findById(userId);
            if (user != null) {
                humans.putIfAbsent(userId, user);
            }
        }
        return humans;
    }

    private boolean containsPlainMention(String contentMd, String name) {
        String needle = "@" + name;
        int from = 0;
        while (from < contentMd.length()) {
            int index = contentMd.indexOf(needle, from);
            if (index < 0) {
                return false;
            }
            int end = index + needle.length();
            boolean leftBoundary = index == 0 || isMentionBoundary(contentMd.charAt(index - 1));
            boolean rightBoundary = end == contentMd.length() || isMentionBoundary(contentMd.charAt(end));
            if (leftBoundary && rightBoundary) {
                return true;
            }
            from = index + 1;
        }
        return false;
    }

    private boolean isMentionBoundary(char value) {
        return Character.isWhitespace(value)
                || value == ',' || value == '.' || value == ';' || value == ':' || value == '!'
                || value == '?' || value == ')' || value == ']' || value == '}'
                || value == '，' || value == '。' || value == '；' || value == '：' || value == '！'
                || value == '？' || value == '）' || value == '】' || value == '」';
    }

    private String resolveUserName(UserDO user) {
        if (user == null) {
            return null;
        }
        if (user.getNickname() != null && !user.getNickname().isBlank()) {
            return user.getNickname();
        }
        return user.getUsername();
    }

    private ParticipantVO toHumanParticipant(UserDO user) {
        ParticipantVO vo = new ParticipantVO();
        vo.setUserId(user.getId());
        vo.setTargetType("HUMAN");
        vo.setName(resolveUserName(user));
        vo.setDisplayId(String.valueOf(user.getId()));
        vo.setAgent(false);
        vo.setRole("HUMAN");
        vo.setRoleName("真人");
        vo.setOnline(false);
        vo.setStatus(user.getStatus() == null ? null : String.valueOf(user.getStatus()));
        return vo;
    }

    private boolean matchesMentionQuery(ParticipantVO participant, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String normalized = query.trim().toLowerCase();
        return containsIgnoreCase(participant.getName(), normalized)
                || containsIgnoreCase(participant.getDisplayId(), normalized)
                || containsIgnoreCase(participant.getRoleName(), normalized);
    }

    private boolean containsIgnoreCase(String value, String normalizedQuery) {
        return value != null && value.toLowerCase().contains(normalizedQuery);
    }

    public CommentVO addAgentComment(long workitemId, String contentMd, long tenantId, long agentId) {
        return addAgentComment(workitemId, contentMd, List.of(), tenantId, agentId, null);
    }

    public CommentVO addAgentComment(long workitemId, String contentMd, List<Long> targetHumanIds,
            long tenantId, long agentId) {
        return addAgentComment(workitemId, contentMd, targetHumanIds, tenantId, agentId, null);
    }

    public CommentVO addAgentComment(long workitemId, String contentMd, List<Long> targetHumanIds,
            long tenantId, long agentId, Long initiatorUserId) {
        WorkitemDO w = workitemDao.findById(workitemId);
        if (w == null || tenantId != w.getTenantId()) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
        LinkedHashMap<Long, UserDO> targetHumans = resolveHumanMentions(tenantId, targetHumanIds);
        if (targetHumans.isEmpty()) {
            targetHumans = resolvePlainTextHumanMentions(workitemId, tenantId, contentMd);
        }
        WorkitemCommentDO c = new WorkitemCommentDO();
        c.setTenantId(tenantId);
        c.setWorkitemId(workitemId);
        c.setAuthorType("AGENT");
        c.setAuthorRef(agentId);
        c.setContentMd(contentMd);
        commentDao.insert(c);
        persistHumanMentions(tenantId, workitemId, c.getId(), targetHumans);
        writeEvent(tenantId, workitemId, "COMMENT", null, null, "AGENT", agentId);
        if (c.getId() != null) {
            eventPublisher.publishEvent(new WorkitemCommentCreatedEvent(tenantId, workitemId, c.getId(),
                    "AGENT", agentId, contentMd));
            publishHumanMentionEvents(tenantId, workitemId, w.getTitle(), c.getId(), targetHumans,
                    "AGENT", agentId, resolveActorDisplayName("AGENT", agentId), initiatorUserId, contentMd);
        }
        return toCommentVO(c);
    }

    public List<CommentVO> listComments(long workitemId) {
        List<CommentVO> result = new ArrayList<>();
        for (WorkitemCommentDO c : commentDao.listByWorkitem(workitemId)) {
            result.add(toCommentVO(c));
        }
        return result;
    }

    public List<EventVO> timeline(long workitemId) {
        List<EventVO> result = new ArrayList<>();
        for (WorkitemEventDO e : eventDao.listByWorkitem(workitemId)) {
            EventVO vo = new EventVO();
            vo.setId(e.getId());
            vo.setEventType(e.getEventType());
            vo.setFromVal(e.getFromVal());
            vo.setToVal(e.getToVal());
            vo.setActorType(e.getActorType());
            vo.setActorRef(e.getActorRef());
            vo.setActorName(resolveActorName(e.getActorType(), e.getActorRef()));
            vo.setActorDisplayName(resolveActorDisplayName(e.getActorType(), e.getActorRef()));
            vo.setFromValDisplay(resolveEventValueDisplay(e, e.getFromVal(), "fromType"));
            vo.setToValDisplay(resolveEventValueDisplay(e, e.getToVal(), "toType"));
            vo.setDetailJson(e.getDetailJson());
            vo.setGmtCreate(e.getGmtCreate());
            result.add(vo);
        }
        return result;
    }

    public DeliveryProgressVO getDeliveryProgress(long workitemId, long tenantId) {
        WorkitemDO w = workitemDao.findById(workitemId);
        if (w == null) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
        List<DispatchDO> dispatches = dispatchDao.listByWorkitem(tenantId, workitemId);
        if (dispatches == null) {
            dispatches = List.of();
        }
        List<DispatchRuntimeEventDO> runtimeEvents = safeList(runtimeEventDao.listByWorkitem(tenantId, workitemId));
        List<Long> agentIds = resolveProgressAgentIds(w, tenantId, dispatches);

        List<AgentDeliveryProgressVO> agentProgress = new ArrayList<>();
        for (Long agentId : agentIds) {
            AgentDeliveryProgressVO agentVO = buildAgentProgress(w, tenantId, agentId, dispatches, runtimeEvents);
            if (agentVO != null) {
                agentProgress.add(agentVO);
            }
        }

        WorkflowPlanVO workflowPlan = latestWorkflowPlan(runtimeEvents);
        if (!applyWorkflowPlan(workflowPlan, agentProgress)) {
            workflowPlan = null;
        }

        List<DeliveryStepVO> compatSteps = selectCompatSteps(w, agentProgress);
        if (compatSteps.isEmpty() && w.getSdlcId() != null) {
            compatSteps = buildLegacySteps(w, dispatches);
        }
        DeliveryProgressVO result = new DeliveryProgressVO();
        result.setAgents(agentProgress);
        result.setSteps(compatSteps);
        result.setWorkflowPlan(workflowPlan);
        result.setProcessGraph(buildProcessGraph(w, tenantId, dispatches));
        return result;
    }

    private ProcessGraphVO buildProcessGraph(WorkitemDO workitem, long tenantId,
                                             List<DispatchDO> dispatches) {
        List<DispatchDO> formal = safeList(dispatches).stream()
                .filter(Objects::nonNull)
                .filter(row -> !isInteractionDispatch(row))
                .filter(row -> row.getId() != null)
                .sorted(Comparator.comparingLong(DispatchDO::getId))
                .toList();
        Map<Long, DispatchDO> byId = formal.stream()
                .collect(Collectors.toMap(DispatchDO::getId, row -> row,
                        (left, right) -> left, LinkedHashMap::new));
        Map<Long, GuidanceDO> guidanceByDispatch = safeList(
                guidanceDao.listByWorkitem(tenantId, workitem.getId())).stream()
                .filter(Objects::nonNull)
                .filter(row -> row.getDispatchId() != null)
                .collect(Collectors.toMap(GuidanceDO::getDispatchId, row -> row,
                        (left, right) -> left, LinkedHashMap::new));

        List<ProcessGraphNodeVO> nodes = new ArrayList<>();
        List<ProcessGraphEdgeVO> edges = new ArrayList<>();
        Map<Long, ProcessGraphNodeVO> nodesByDispatch = new LinkedHashMap<>();
        for (DispatchDO row : formal) {
            ProcessGraphNodeVO node = graphNode(row);
            nodes.add(node);
            nodesByDispatch.put(row.getId(), node);
        }

        for (DispatchDO target : formal) {
            if ("COMMENT_REWORK".equals(target.getResumeMode())) {
                Long sideDispatchId = parsePrefixedId(target.getIdempotencyKey(), "interaction-rework:");
                GuidanceDO guidance = sideDispatchId == null ? null : guidanceByDispatch.get(sideDispatchId);
                Long commentId = guidance == null ? null : guidance.getCommentId();
                nodesByDispatch.get(target.getId()).setTriggerCommentId(commentId);
                Long sourceId = parsePrefixedId(target.getResultSummary(), "waitForDispatchId=");
                addGraphEdge(edges, byId, sourceId, target, "COMMENT_REWORK", commentId,
                        commentId == null ? "用户返工" : "用户返工（评论 #" + commentId + "）");
                continue;
            }
            Long handoffSource = parsePrefixedId(target.getIdempotencyKey(), "handoff:");
            if (handoffSource != null) {
                DispatchDO source = byId.get(handoffSource);
                String label = source != null && isFailed(source.getStatus()) ? "失败后交接" : "交接";
                addGraphEdge(edges, byId, handoffSource, target, "HANDOFF", null, label);
                continue;
            }
            if (target.getResumeFromDispatchId() != null) {
                addGraphEdge(edges, byId, target.getResumeFromDispatchId(), target,
                        "CONTINUE", null, "恢复执行");
            }
        }

        appendHumanHandoff(workitem, formal, nodes, edges);
        ProcessGraphVO graph = new ProcessGraphVO();
        graph.setNodes(nodes);
        graph.setEdges(edges);
        return graph;
    }

    private ProcessGraphNodeVO graphNode(DispatchDO row) {
        ProcessGraphNodeVO node = new ProcessGraphNodeVO();
        node.setKey("dispatch:" + row.getId());
        node.setDispatchId(row.getId());
        node.setAgentId(row.getAgentId());
        node.setAgentName(resolveAgentName(row.getAgentId()));
        node.setStepId(row.getSdlcStepId());
        SdlcStepDO step = row.getSdlcStepId() == null ? null : stepDao.findById(row.getSdlcStepId());
        node.setStepName(step == null ? null : step.getName());
        node.setStatus(row.getStatus());
        node.setStartedAt(row.getGmtCreate());
        node.setDurationMs(durationOf(row));
        node.setError(row.getError());
        return node;
    }

    private void addGraphEdge(List<ProcessGraphEdgeVO> edges, Map<Long, DispatchDO> formalById,
                              Long sourceId, DispatchDO target, String type,
                              Long commentId, String label) {
        if (sourceId == null || target == null || target.getId() == null
                || !formalById.containsKey(sourceId)) {
            return;
        }
        ProcessGraphEdgeVO edge = new ProcessGraphEdgeVO();
        edge.setSourceKey("dispatch:" + sourceId);
        edge.setTargetKey("dispatch:" + target.getId());
        edge.setType(type);
        edge.setSourceDispatchId(sourceId);
        edge.setTargetDispatchId(target.getId());
        edge.setCommentId(commentId);
        edge.setLabel(label);
        edges.add(edge);
    }

    private void appendHumanHandoff(WorkitemDO workitem, List<DispatchDO> formal,
                                    List<ProcessGraphNodeVO> nodes,
                                    List<ProcessGraphEdgeVO> edges) {
        if (!"HUMAN".equals(workitem.getAssigneeType()) || workitem.getAssigneeRef() == null
                || formal.isEmpty()) {
            return;
        }
        DispatchDO latest = formal.get(formal.size() - 1);
        if (!DispatchStatus.SUCCEEDED.equals(latest.getStatus())) {
            return;
        }
        UserDO user = userDao.findById(workitem.getAssigneeRef());
        ProcessGraphNodeVO human = new ProcessGraphNodeVO();
        human.setKey("human:" + workitem.getAssigneeRef());
        String humanName = user == null ? null : user.getNickname();
        if (humanName == null || humanName.isBlank()) {
            humanName = user == null ? null : user.getUsername();
        }
        human.setAgentName(humanName == null || humanName.isBlank() ? "真人" : humanName);
        human.setStatus("HUMAN");
        nodes.add(human);

        ProcessGraphEdgeVO edge = new ProcessGraphEdgeVO();
        edge.setSourceKey("dispatch:" + latest.getId());
        edge.setTargetKey(human.getKey());
        edge.setType("HUMAN_HANDOFF");
        edge.setSourceDispatchId(latest.getId());
        edge.setLabel("交接真人");
        edges.add(edge);
    }

    private Long parsePrefixedId(String value, String prefix) {
        if (value == null || !value.startsWith(prefix)) {
            return null;
        }
        try {
            return Long.parseLong(value.substring(prefix.length()));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private List<DeliveryStepVO> buildLegacySteps(WorkitemDO w, List<DispatchDO> dispatches) {
        List<SdlcStepDO> sdlcSteps = new ArrayList<>(safeList(stepDao.listBySdlc(w.getSdlcId())));
        sdlcSteps.sort(Comparator.comparingInt(s -> s.getStepOrder() == null ? 0 : s.getStepOrder()));
        Map<Long, List<DispatchDO>> dispatchesByStep = new LinkedHashMap<>();
        for (DispatchDO d : dispatches) {
            dispatchesByStep.computeIfAbsent(d.getSdlcStepId(), ignored -> new ArrayList<>()).add(d);
        }
        DispatchDO latestWorkitemDispatch = latestDispatch(dispatches);

        List<DeliveryStepVO> stepVOs = new ArrayList<>();
        for (SdlcStepDO step : sdlcSteps) {
            DeliveryStepVO vo = new DeliveryStepVO();
            vo.setStepId(step.getId());
            vo.setStepKey(step.getCode());
            vo.setName(step.getName());
            List<DispatchDO> stepDispatches = dispatchesByStep.get(step.getId());
            DispatchDO latestDispatch = latestDispatch(stepDispatches);
            vo.setAttempts(buildAttempts(stepDispatches,
                    latestWorkitemDispatch != null ? latestWorkitemDispatch.getId() : null,
                    List.of()));
            if (latestDispatch != null) {
                vo.setDurationMs(durationOf(latestDispatch));
                vo.setError(latestDispatch.getError());
                vo.setExecutorName(resolveAgentName(latestDispatch.getAgentId()));
                vo.setSubSteps(dispatchSubSteps(latestDispatch.getStatus()));
            }
            if (latestDispatch != null && isFailed(latestDispatch.getStatus())) {
                vo.setStatus("failed");
            } else if (step.getId().equals(w.getCurrentStepId())) {
                vo.setStatus("active");
            } else {
                boolean hasSucceeded = stepDispatches != null && stepDispatches.stream()
                        .anyMatch(d -> DispatchStatus.SUCCEEDED.equals(d.getStatus()));
                vo.setStatus(hasSucceeded ? "done" : "pending");
            }
            stepVOs.add(vo);
        }
        return stepVOs;
    }

    private List<Long> resolveProgressAgentIds(WorkitemDO w, long tenantId, List<DispatchDO> dispatches) {
        LinkedHashMap<Long, Long> ids = new LinkedHashMap<>();
        for (DispatchDO d : dispatches) {
            if (d.getAgentId() != null) {
                ids.putIfAbsent(d.getAgentId(), d.getAgentId());
            }
        }
        if ("AGENT".equals(w.getAssigneeType()) && w.getAssigneeRef() != null) {
            ids.putIfAbsent(w.getAssigneeRef(), w.getAssigneeRef());
        }
        List<SquadMemberDO> squadMembers = resolveSquadMembers(tenantId, new ArrayList<>(ids.keySet()));
        for (SquadMemberDO member : squadMembers) {
            if (member.getAgentId() != null) {
                ids.putIfAbsent(member.getAgentId(), member.getAgentId());
            }
        }
        return new ArrayList<>(ids.keySet());
    }

    private AgentDeliveryProgressVO buildAgentProgress(WorkitemDO w, long tenantId, Long agentId,
                                                       List<DispatchDO> allDispatches,
                                                       List<DispatchRuntimeEventDO> allRuntimeEvents) {
        Long sdlcId = sdlcResolver.resolveSdlcId(tenantId, agentId);
        if (sdlcId == null) {
            sdlcId = w.getSdlcId();
        }
        if (sdlcId == null) {
            return null;
        }

        List<SdlcStepDO> sdlcSteps = new ArrayList<>(safeList(stepDao.listBySdlc(sdlcId)));
        sdlcSteps.sort(Comparator.comparingInt(s -> s.getStepOrder() == null ? 0 : s.getStepOrder()));
        List<DispatchDO> allAgentDispatches = allDispatches.stream()
                .filter(d -> Objects.equals(agentId, d.getAgentId()))
                .collect(Collectors.toList());
        List<DispatchDO> agentDispatches = allAgentDispatches.stream()
                .filter(d -> !isInteractionDispatch(d))
                .collect(Collectors.toList());
        DispatchDO latestAgentDispatch = latestDispatch(agentDispatches);
        boolean completedAgentWorkflow = completedAgentWorkflow(agentDispatches);
        Map<Long, List<DispatchDO>> dispatchesByStep = new LinkedHashMap<>();
        for (DispatchDO d : agentDispatches) {
            dispatchesByStep.computeIfAbsent(d.getSdlcStepId(), ignored -> new ArrayList<>()).add(d);
        }
        List<DispatchRuntimeEventDO> allAgentRuntimeEvents = runtimeEventsForAgent(
                agentId, allAgentDispatches, allRuntimeEvents);
        List<DispatchRuntimeEventDO> displayRuntimeEvents = latestAgentDispatch == null
                ? List.of()
                : runtimeEventsForDispatch(agentId, latestAgentDispatch, allRuntimeEvents);
        RuntimeStepState runtimeState = RuntimeStepState.from(displayRuntimeEvents, sdlcSteps);

        List<DeliveryStepVO> stepVOs = new ArrayList<>();
        for (SdlcStepDO step : sdlcSteps) {
            DeliveryStepVO vo = new DeliveryStepVO();
            vo.setStepId(step.getId());
            vo.setStepKey(step.getCode());
            vo.setName(step.getName());

            List<DispatchDO> stepDispatches = dispatchesByStep.get(step.getId());
            DispatchDO latestDispatch = latestDispatch(stepDispatches);
            vo.setAttempts(buildAttempts(stepDispatches,
                    latestAgentDispatch != null ? latestAgentDispatch.getId() : null,
                    allRuntimeEvents));
            if (latestDispatch != null) {
                vo.setDurationMs(durationOf(latestDispatch));
                vo.setError(latestDispatch.getError());
                vo.setExecutorName(resolveAgentName(latestDispatch.getAgentId()));
                vo.setSubSteps(dispatchSubSteps(latestDispatch.getStatus()));
            }
            String runtimeStatus = runtimeState.statusOf(step);
            if (runtimeStatus != null && runtimeState.isCurrent(step) && latestAgentDispatch != null) {
                if (DispatchStatus.PAUSED.equals(latestAgentDispatch.getStatus())) {
                    runtimeStatus = "paused";
                } else if (DispatchStatus.PENDING.equals(latestAgentDispatch.getStatus())
                        && "failed".equals(runtimeStatus)) {
                    runtimeStatus = null;
                } else if (isFailed(latestAgentDispatch.getStatus())) {
                    runtimeStatus = "failed";
                }
            }
            List<SubStepVO> runtimeSubSteps = runtimeStatus == null
                    ? List.of() : runtimeState.subStepsOf(step, runtimeStatus);
            DispatchRuntimeEventDO lastEvent = runtimeStatus == null
                    ? null : runtimeState.lastEventOf(step);
            if (runtimeStatus != null && !completedAgentWorkflow) {
                vo.setStatus(runtimeStatus);
            } else if (latestDispatch != null && DispatchStatus.PAUSED.equals(latestDispatch.getStatus())) {
                vo.setStatus("paused");
            } else if (latestDispatch != null && isFailed(latestDispatch.getStatus())) {
                vo.setStatus("failed");
            } else if (latestDispatch != null && !isTerminal(latestDispatch.getStatus())) {
                vo.setStatus("active");
            } else {
                boolean hasSucceeded = stepDispatches != null && stepDispatches.stream()
                        .anyMatch(d -> "SUCCEEDED".equals(d.getStatus()));
                vo.setStatus((hasSucceeded || completedAgentWorkflow) ? "done" : "pending");
            }
            if (!runtimeSubSteps.isEmpty()) {
                vo.setSubSteps(runtimeSubSteps);
            }
            if (lastEvent != null && lastEvent.getError() != null) {
                vo.setError(lastEvent.getError());
            }
            if (runtimeStatus != null && latestDispatch == null && !agentDispatches.isEmpty()) {
                vo.setExecutorName(resolveAgentName(agentId));
            }

            stepVOs.add(vo);
        }

        AgentDeliveryProgressVO agentVO = new AgentDeliveryProgressVO();
        agentVO.setAgentId(agentId);
        agentVO.setAgentName(resolveAgentName(agentId));
        agentVO.setSteps(stepVOs);
        DispatchDO latestAnyDispatch = latestDispatch(allAgentDispatches);
        boolean interactionActive = latestAnyDispatch != null && isInteractionDispatch(latestAnyDispatch)
                && !isTerminal(latestAnyDispatch.getStatus());
        agentVO.setStatus(interactionActive ? "active" : resolveAgentProgressStatus(w, agentId, stepVOs));
        agentVO.setDurationMs(totalDuration(agentDispatches));
        List<DispatchDO> activeAgentDispatches = allAgentDispatches.stream()
                .filter(dispatch -> !isTerminal(dispatch.getStatus()))
                .collect(Collectors.toList());
        List<DispatchRuntimeEventDO> activeRuntimeEvents = runtimeEventsForAgent(
                agentId, activeAgentDispatches, allAgentRuntimeEvents);
        agentVO.setCurrentActivity(activeAgentDispatches.isEmpty()
                ? null : latestAgentActivity(activeRuntimeEvents));
        return agentVO;
    }

    private boolean isInteractionDispatch(DispatchDO dispatch) {
        return dispatch != null && ("SIDE_INTERACTION".equals(dispatch.getResumeMode())
                || "CANONICAL_INTERACTION".equals(dispatch.getResumeMode())
                || "COMMENT_INTERACTION".equals(dispatch.getResumeMode()));
    }

    private String latestAgentActivity(List<DispatchRuntimeEventDO> events) {
        return safeList(events).stream()
                .filter(Objects::nonNull)
                .filter(event -> "agent.progress".equals(event.getEventType()))
                .filter(event -> event.getMessage() != null && !event.getMessage().isBlank())
                .max((left, right) -> {
                    if (left.getId() != null && right.getId() != null) {
                        return left.getId().compareTo(right.getId());
                    }
                    if (left.getEventTime() != null && right.getEventTime() != null) {
                        return left.getEventTime().compareTo(right.getEventTime());
                    }
                    return 0;
                })
                .map(DispatchRuntimeEventDO::getMessage)
                .orElse(null);
    }

    private WorkflowPlanVO latestWorkflowPlan(List<DispatchRuntimeEventDO> events) {
        DispatchRuntimeEventDO latest = events.stream()
                .filter(Objects::nonNull)
                .filter(event -> "workflow.plan_applied".equals(event.getEventType()))
                .max((left, right) -> {
                    if (left.getId() != null && right.getId() != null) {
                        return left.getId().compareTo(right.getId());
                    }
                    if (left.getGmtCreate() != null && right.getGmtCreate() != null) {
                        return left.getGmtCreate().compareTo(right.getGmtCreate());
                    }
                    return 0;
                })
                .orElse(null);
        if (latest == null || latest.getDetailJson() == null || latest.getDetailJson().isBlank()) {
            return null;
        }
        try {
            JSONObject detail = JSON.parseObject(latest.getDetailJson());
            Integer revision = detail.getInteger("revision");
            String targetStepId = detail.getString("targetStepId");
            com.alibaba.fastjson.JSONArray rawSteps = detail.getJSONArray("steps");
            if (revision == null || revision < 1 || targetStepId == null || targetStepId.isBlank()
                    || rawSteps == null || rawSteps.isEmpty()) {
                return null;
            }
            List<WorkflowPlanStepVO> steps = new ArrayList<>();
            Set<String> validStatuses = Set.of("RUN", "REUSED", "SKIPPED");
            for (int i = 0; i < rawSteps.size(); i++) {
                JSONObject raw = rawSteps.getJSONObject(i);
                String stepKey = raw.getString("stepKey");
                String name = raw.getString("name");
                String planStatus = raw.getString("planStatus");
                if ((stepKey == null || stepKey.isBlank()) && (name == null || name.isBlank())) {
                    return null;
                }
                if (!validStatuses.contains(planStatus)) {
                    return null;
                }
                WorkflowPlanStepVO step = new WorkflowPlanStepVO();
                step.setStepKey(stepKey);
                step.setName(name);
                step.setPlanStatus(planStatus);
                step.setSourceAttempt(raw.getInteger("sourceAttempt"));
                steps.add(step);
            }
            WorkflowPlanVO result = new WorkflowPlanVO();
            result.setRevision(revision);
            result.setAgentId(latest.getAgentId());
            result.setAgentName(resolveAgentName(latest.getAgentId()));
            result.setTargetStepId(targetStepId);
            result.setReason(detail.getString("reason"));
            com.alibaba.fastjson.JSONArray rawGuidanceIds = detail.getJSONArray("sourceGuidanceIds");
            List<Long> guidanceIds = new ArrayList<>();
            if (rawGuidanceIds != null) {
                for (int i = 0; i < rawGuidanceIds.size(); i++) {
                    guidanceIds.add(rawGuidanceIds.getLong(i));
                }
            }
            result.setSourceGuidanceIds(guidanceIds);
            result.setSteps(steps);
            return result;
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean applyWorkflowPlan(WorkflowPlanVO plan, List<AgentDeliveryProgressVO> agents) {
        if (plan == null || plan.getSteps() == null) {
            return plan == null;
        }
        List<Map.Entry<DeliveryStepVO, WorkflowPlanStepVO>> matches = new ArrayList<>();
        for (AgentDeliveryProgressVO agent : agents) {
            if (plan.getAgentId() != null && !Objects.equals(plan.getAgentId(), agent.getAgentId())) {
                continue;
            }
            for (WorkflowPlanStepVO planned : plan.getSteps()) {
                DeliveryStepVO step = safeList(agent.getSteps()).stream()
                        .filter(item -> (planned.getStepKey() != null && Objects.equals(planned.getStepKey(), item.getStepKey()))
                                || (planned.getName() != null && Objects.equals(planned.getName(), item.getName())))
                        .findFirst()
                        .orElse(null);
                if (step == null) {
                    return false;
                }
                matches.add(Map.entry(step, planned));
            }
            break;
        }
        if (matches.size() != plan.getSteps().size()) {
            return false;
        }
        for (Map.Entry<DeliveryStepVO, WorkflowPlanStepVO> match : matches) {
            match.getKey().setPlanStatus(match.getValue().getPlanStatus());
            match.getKey().setSourceAttempt(match.getValue().getSourceAttempt());
        }
        return true;
    }

    private List<DispatchRuntimeEventDO> runtimeEventsForAgent(Long agentId, List<DispatchDO> agentDispatches,
                                                               List<DispatchRuntimeEventDO> allRuntimeEvents) {
        if (allRuntimeEvents == null || allRuntimeEvents.isEmpty()) {
            return List.of();
        }
        Set<Long> dispatchIds = agentDispatches.stream()
                .map(DispatchDO::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return allRuntimeEvents.stream()
                .filter(e -> e != null && Objects.equals(agentId, e.getAgentId()))
                .filter(e -> e.getDispatchId() == null || dispatchIds.contains(e.getDispatchId()))
                .collect(Collectors.toList());
    }

    private List<DispatchRuntimeEventDO> runtimeEventsForDispatch(Long agentId, DispatchDO dispatch,
                                                                  List<DispatchRuntimeEventDO> allRuntimeEvents) {
        if (dispatch == null || dispatch.getId() == null || allRuntimeEvents == null || allRuntimeEvents.isEmpty()) {
            return List.of();
        }
        Long dispatchId = dispatch.getId();
        return allRuntimeEvents.stream()
                .filter(e -> e != null && Objects.equals(agentId, e.getAgentId()))
                .filter(e -> Objects.equals(dispatchId, e.getDispatchId()))
                .collect(Collectors.toList());
    }

    private static class RuntimeStepState {
        private final Map<Integer, List<DispatchRuntimeEventDO>> eventsByOrder;
        private final Integer latestOrder;
        private final String latestEventType;

        private RuntimeStepState(Map<Integer, List<DispatchRuntimeEventDO>> eventsByOrder,
                                 Integer latestOrder, String latestEventType) {
            this.eventsByOrder = eventsByOrder;
            this.latestOrder = latestOrder;
            this.latestEventType = latestEventType;
        }

        static RuntimeStepState from(List<DispatchRuntimeEventDO> events, List<SdlcStepDO> steps) {
            Map<Integer, SdlcStepDO> stepsByOrder = steps.stream()
                    .filter(s -> s.getStepOrder() != null)
                    .collect(Collectors.toMap(SdlcStepDO::getStepOrder, s -> s, (a, b) -> a, LinkedHashMap::new));
            Map<Long, Integer> orderByStepId = steps.stream()
                    .filter(s -> s.getId() != null && s.getStepOrder() != null)
                    .collect(Collectors.toMap(SdlcStepDO::getId, SdlcStepDO::getStepOrder, (a, b) -> a));
            Map<String, Integer> orderByName = steps.stream()
                    .filter(s -> s.getName() != null && s.getStepOrder() != null)
                    .collect(Collectors.toMap(s -> s.getName().trim(), SdlcStepDO::getStepOrder, (a, b) -> a));
            Map<String, Integer> orderByCode = steps.stream()
                    .filter(s -> s.getCode() != null && !s.getCode().isBlank() && s.getStepOrder() != null)
                    .collect(Collectors.toMap(s -> s.getCode().trim(), SdlcStepDO::getStepOrder, (a, b) -> a));

            Map<Integer, List<DispatchRuntimeEventDO>> byOrder = new LinkedHashMap<>();
            Integer latestOrder = null;
            String latestType = null;
            for (DispatchRuntimeEventDO event : events) {
                Integer order = resolveOrder(event, stepsByOrder, orderByStepId, orderByName, orderByCode);
                if (order == null) {
                    continue;
                }
                byOrder.computeIfAbsent(order, ignored -> new ArrayList<>()).add(event);
                if (latestOrder == null || compareEvent(event, lastEvent(byOrder.get(latestOrder))) >= 0) {
                    latestOrder = order;
                    latestType = event.getEventType();
                }
            }
            return new RuntimeStepState(byOrder, latestOrder, latestType);
        }

        String statusOf(SdlcStepDO step) {
            if (latestOrder == null || step.getStepOrder() == null) {
                return null;
            }
            int order = step.getStepOrder();
            List<DispatchRuntimeEventDO> events = eventsByOrder.get(order);
            DispatchRuntimeEventDO last = lastEvent(events);
            if (last != null && "step.reused".equals(last.getEventType())) {
                return "done";
            }
            if (last != null && "step.stale".equals(last.getEventType())) {
                return "pending";
            }
            if (last != null && isFailureEvent(last)) {
                return "failed";
            }
            if (order < latestOrder) {
                return "done";
            }
            if (order > latestOrder) {
                return "pending";
            }
            if (isCompletionEvent(latestEventType)) {
                return "done";
            }
            return "active";
        }

        boolean isCurrent(SdlcStepDO step) {
            return latestOrder != null && Objects.equals(latestOrder, step.getStepOrder());
        }

        List<SubStepVO> subStepsOf(SdlcStepDO step, String stepStatus) {
            if (step.getStepOrder() == null) {
                return List.of();
            }
            List<DispatchRuntimeEventDO> events = eventsByOrder.get(step.getStepOrder());
            if (events == null || events.isEmpty()) {
                return List.of();
            }
            List<SubStepVO> result = new ArrayList<>();
            for (int i = 0; i < events.size(); i++) {
                DispatchRuntimeEventDO event = events.get(i);
                SubStepVO vo = new SubStepVO();
                vo.setName(labelOf(event));
                if (isFailureEvent(event)) {
                    vo.setStatus("failed");
                } else if (i == events.size() - 1
                        && ("active".equals(stepStatus) || "paused".equals(stepStatus)
                        || "failed".equals(stepStatus))) {
                    vo.setStatus(stepStatus);
                } else {
                    vo.setStatus("done");
                }
                result.add(vo);
            }
            return result;
        }

        DispatchRuntimeEventDO lastEventOf(SdlcStepDO step) {
            if (step.getStepOrder() == null) {
                return null;
            }
            return lastEvent(eventsByOrder.get(step.getStepOrder()));
        }

        private static Integer resolveOrder(DispatchRuntimeEventDO event, Map<Integer, SdlcStepDO> stepsByOrder,
                                            Map<Long, Integer> orderByStepId, Map<String, Integer> orderByName,
                                            Map<String, Integer> orderByCode) {
            if (event.getStepOrder() != null && stepsByOrder.containsKey(event.getStepOrder())) {
                return event.getStepOrder();
            }
            if (event.getStepId() != null && orderByStepId.containsKey(event.getStepId())) {
                return orderByStepId.get(event.getStepId());
            }
            if (event.getStepKey() != null) {
                Integer order = orderByCode.get(event.getStepKey().trim());
                if (order != null) {
                    return order;
                }
            }
            if (event.getStepName() != null) {
                Integer order = orderByName.get(event.getStepName().trim());
                if (order != null) {
                    return order;
                }
            }
            return null;
        }

        private static int compareEvent(DispatchRuntimeEventDO left, DispatchRuntimeEventDO right) {
            if (right == null) {
                return 1;
            }
            if (left.getId() != null && right.getId() != null) {
                return left.getId().compareTo(right.getId());
            }
            if (left.getGmtCreate() != null && right.getGmtCreate() != null) {
                return left.getGmtCreate().compareTo(right.getGmtCreate());
            }
            return 0;
        }

        private static DispatchRuntimeEventDO lastEvent(List<DispatchRuntimeEventDO> events) {
            if (events == null || events.isEmpty()) {
                return null;
            }
            return events.get(events.size() - 1);
        }

        private static boolean isCompletionEvent(String eventType) {
            return "step.completed".equals(eventType)
                    || "step.completion_requested".equals(eventType)
                    || "completion_requested".equals(eventType)
                    || "dispatch.completed".equals(eventType);
        }

        private static boolean isFailureEvent(DispatchRuntimeEventDO event) {
            String type = event.getEventType();
            return event.getError() != null
                    || "step.failed".equals(type)
                    || "dispatch.failed".equals(type);
        }

        private static String labelOf(DispatchRuntimeEventDO event) {
            if (event.getMessage() != null && !event.getMessage().isBlank()
                    && !looksLikeMojibake(event.getMessage())) {
                return event.getMessage();
            }
            String eventLabel = runtimeEventLabel(event.getEventType());
            if (eventLabel != null) {
                return eventLabel;
            }
            if (event.getEventType() != null) {
                return event.getEventType();
            }
            return "运行进度";
        }

        private static String runtimeEventLabel(String eventType) {
            if (eventType == null) {
                return null;
            }
            if (eventType.startsWith("step.started")) {
                return "开始执行";
            }
            if (isCompletionEvent(eventType)) {
                return "请求完成";
            }
            if ("step.gate_started".equals(eventType)) {
                return "开始校验";
            }
            if ("step.gate_finished".equals(eventType)) {
                return "校验完成";
            }
            if ("step.fix_required".equals(eventType)) {
                return "需要修复";
            }
            if ("step.failed".equals(eventType) || "dispatch.failed".equals(eventType)) {
                return "执行失败";
            }
            return null;
        }

        private static boolean looksLikeMojibake(String text) {
            if (text == null || text.isBlank()) {
                return false;
            }
            if (text.indexOf('\uFFFD') >= 0) {
                return true;
            }
            int suspicious = 0;
            int visible = 0;
            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (!Character.isWhitespace(ch)) {
                    visible++;
                }
                if ((ch >= '\u00C0' && ch <= '\u024F') || ch == '\u00A0') {
                    suspicious++;
                }
            }
            return suspicious >= 3 && suspicious * 2 >= Math.max(1, visible);
        }
    }

    private boolean completedAgentWorkflow(List<DispatchDO> dispatches) {
        if (dispatches == null || dispatches.isEmpty()) {
            return false;
        }
        DispatchDO latest = latestDispatch(dispatches);
        boolean latestSucceeded = latest != null && DispatchStatus.SUCCEEDED.equals(latest.getStatus());
        boolean hasRunningState = dispatches.stream().anyMatch(d -> !isTerminal(d.getStatus()));
        long distinctSteps = dispatches.stream()
                .map(DispatchDO::getSdlcStepId)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        return latestSucceeded && !hasRunningState && distinctSteps <= 1;
    }

    private List<DeliveryStepVO> selectCompatSteps(WorkitemDO w, List<AgentDeliveryProgressVO> agents) {
        if (agents.isEmpty()) {
            return List.of();
        }
        for (AgentDeliveryProgressVO agent : agents) {
            if (hasActiveSdlcStep(agent)) {
                return agent.getSteps();
            }
        }
        for (AgentDeliveryProgressVO agent : agents) {
            if ("active".equals(agent.getStatus())) {
                return agent.getSteps();
            }
        }
        for (AgentDeliveryProgressVO agent : agents) {
            if (Objects.equals(w.getAssigneeRef(), agent.getAgentId())) {
                return agent.getSteps();
            }
        }
        return agents.get(agents.size() - 1).getSteps();
    }

    private boolean hasActiveSdlcStep(AgentDeliveryProgressVO agent) {
        return safeList(agent.getSteps()).stream()
                .anyMatch(step -> "active".equals(step.getStatus())
                        || "paused".equals(step.getStatus())
                        || "failed".equals(step.getStatus()));
    }

    private String resolveAgentProgressStatus(WorkitemDO w, Long agentId, List<DeliveryStepVO> steps) {
        boolean hasPaused = steps.stream().anyMatch(s -> "paused".equals(s.getStatus()));
        if (hasPaused) {
            return "paused";
        }
        boolean hasActive = steps.stream().anyMatch(s -> "active".equals(s.getStatus()));
        if (hasActive) {
            return "active";
        }
        boolean hasFailed = steps.stream().anyMatch(s -> "failed".equals(s.getStatus()));
        boolean hasDone = steps.stream().anyMatch(s -> "done".equals(s.getStatus()));
        if (hasFailed && !hasActive) {
            return "failed";
        }
        if (hasDone && !hasActive) {
            return "finished";
        }
        return "pending";
    }

    private Long totalDuration(List<DispatchDO> dispatches) {
        long total = 0L;
        boolean any = false;
        for (DispatchDO d : dispatches) {
            Long duration = durationOf(d);
            if (duration != null) {
                total += duration;
                any = true;
            }
        }
        return any ? total : null;
    }

    private String resolveAgentName(Long agentId) {
        if (agentId == null) {
            return null;
        }
        AgentDO agent = agentDao.findById(agentId);
        return agent == null ? null : agent.getName();
    }

    private DispatchDO latestDispatch(List<DispatchDO> dispatches) {
        if (dispatches == null || dispatches.isEmpty()) {
            return null;
        }
        return dispatches.get(dispatches.size() - 1);
    }

    private Long durationOf(DispatchDO d) {
        if (d == null || d.getGmtCreate() == null || d.getGmtModified() == null) {
            return null;
        }
        return d.getGmtModified().getTime() - d.getGmtCreate().getTime();
    }

    private List<DispatchAttemptVO> buildAttempts(List<DispatchDO> stepDispatches,
            Long resumableDispatchId, List<DispatchRuntimeEventDO> allRuntimeEvents) {
        List<DispatchAttemptVO> attempts = new ArrayList<>();
        if (stepDispatches == null) {
            return attempts;
        }
        Map<Long, DispatchRuntimeEventDO> latestEventByDispatch = latestRuntimeEventByDispatch(allRuntimeEvents);
        for (int i = 0; i < stepDispatches.size(); i++) {
            DispatchDO d = stepDispatches.get(i);
            DispatchRuntimeEventDO latestEvent = latestEventByDispatch.get(d.getId());
            boolean runtimeFailed = !DispatchStatus.PENDING.equals(d.getStatus())
                    && !DispatchStatus.isTerminal(d.getStatus())
                    && isRuntimeFailureEvent(latestEvent);
            DispatchAttemptVO vo = new DispatchAttemptVO();
            vo.setDispatchId(d.getId());
            vo.setExecutorName(resolveAgentName(d.getAgentId()));
            vo.setStatus(runtimeFailed ? DispatchStatus.FAILED : d.getStatus());
            vo.setResumeMode(d.getResumeMode());
            boolean executorFailover = DispatchStatus.PENDING.equals(d.getStatus())
                    && latestEvent != null
                    && "dispatch.executor_failover".equals(latestEvent.getEventType());
            vo.setError(runtimeFailed || executorFailover
                    ? runtimeFailureMessage(latestEvent)
                    : d.getError());
            vo.setStartedAt(d.getGmtCreate());
            vo.setDurationMs(durationOf(d));
            boolean executorOffline = d.getExecutorId() == null
                    || !presenceManager.isExecutorOnline(d.getExecutorId());
            vo.setCanContinue(Objects.equals(d.getId(), resumableDispatchId)
                    && DispatchService.canContinue(d, System.currentTimeMillis())
                    && (DispatchStatus.PAUSED.equals(d.getStatus())
                            || DispatchStatus.isTerminal(d.getStatus()) || executorOffline));
            vo.setCanPause(Objects.equals(d.getId(), resumableDispatchId)
                    && (DispatchStatus.isPauseable(d.getStatus())
                            || DispatchStatus.PAUSING.equals(d.getStatus())
                            || DispatchStatus.PAUSE_FAILED.equals(d.getStatus()))
                    && !runtimeFailed
                    && !executorOffline);
            attempts.add(vo);
        }
        return attempts;
    }

    private Map<Long, DispatchRuntimeEventDO> latestRuntimeEventByDispatch(List<DispatchRuntimeEventDO> events) {
        Map<Long, DispatchRuntimeEventDO> latest = new HashMap<>();
        for (DispatchRuntimeEventDO event : safeList(events)) {
            if (event == null || event.getDispatchId() == null) {
                continue;
            }
            latest.merge(event.getDispatchId(), event,
                    (left, right) -> compareRuntimeEvent(left, right) >= 0 ? left : right);
        }
        return latest;
    }

    private int compareRuntimeEvent(DispatchRuntimeEventDO left, DispatchRuntimeEventDO right) {
        if (left.getId() != null && right.getId() != null) {
            return left.getId().compareTo(right.getId());
        }
        if (left.getGmtCreate() != null && right.getGmtCreate() != null) {
            return left.getGmtCreate().compareTo(right.getGmtCreate());
        }
        if (left.getEventTime() != null && right.getEventTime() != null) {
            return left.getEventTime().compareTo(right.getEventTime());
        }
        return 0;
    }

    private boolean isRuntimeFailureEvent(DispatchRuntimeEventDO event) {
        if (event == null) {
            return false;
        }
        String type = event.getEventType();
        return event.getError() != null
                || "step.failed".equals(type)
                || "dispatch.failed".equals(type)
                || "runtime.failed".equals(type)
                || "task.failed".equals(type);
    }

    private String runtimeFailureMessage(DispatchRuntimeEventDO event) {
        if (event == null) {
            return null;
        }
        if (event.getError() != null && !event.getError().isBlank()) {
            return event.getError();
        }
        if (event.getMessage() != null && !event.getMessage().isBlank()) {
            return event.getMessage();
        }
        return event.getEventType();
    }

    private List<SubStepVO> dispatchSubSteps(String dispatchStatus) {
        if (DispatchStatus.PENDING.equals(dispatchStatus)) {
            return List.of(
                    subStep("启动交付", "done"),
                    subStep("等待调度执行", "active"),
                    subStep("客户端接单", "pending"),
                    subStep("执行中", "pending")
            );
        }
        if (DispatchStatus.PACKAGING.equals(dispatchStatus)) {
            return List.of(
                    subStep("启动交付", "done"),
                    subStep("准备执行上下文", "active"),
                    subStep("客户端接单", "pending"),
                    subStep("执行中", "pending")
            );
        }
        if (DispatchStatus.DISPATCHED.equals(dispatchStatus)) {
            return List.of(
                    subStep("启动交付", "done"),
                    subStep("准备执行上下文", "done"),
                    subStep("等待客户端接单", "active"),
                    subStep("执行中", "pending")
            );
        }
        if (DispatchStatus.ACKED.equals(dispatchStatus)) {
            return List.of(
                    subStep("启动交付", "done"),
                    subStep("准备执行上下文", "done"),
                    subStep("客户端已接单", "active"),
                    subStep("执行中", "pending")
            );
        }
        if (DispatchStatus.RUNNING.equals(dispatchStatus)) {
            return List.of(
                    subStep("启动交付", "done"),
                    subStep("准备执行上下文", "done"),
                    subStep("客户端已接单", "done"),
                    subStep("正在执行", "active")
            );
        }
        if (DispatchStatus.PAUSING.equals(dispatchStatus)) {
            return List.of(
                    subStep("启动交付", "done"),
                    subStep("客户端已接单", "done"),
                    subStep("等待当前动作安全结束", "active"),
                    subStep("保存恢复检查点", "pending")
            );
        }
        if (DispatchStatus.PAUSED.equals(dispatchStatus)) {
            return List.of(
                    subStep("启动交付", "done"),
                    subStep("客户端已接单", "done"),
                    subStep("当前动作已安全结束", "done"),
                    subStep("已暂停，恢复检查点已保存", "done")
            );
        }
        if (DispatchStatus.SUCCEEDED.equals(dispatchStatus)) {
            return List.of(
                    subStep("启动交付", "done"),
                    subStep("准备执行上下文", "done"),
                    subStep("客户端已接单", "done"),
                    subStep("执行完成", "done")
            );
        }
        if (isFailed(dispatchStatus)) {
            return List.of(
                    subStep("启动交付", "done"),
                    subStep("准备执行上下文", "done"),
                    subStep("客户端已接单", "done"),
                    subStep("执行失败", "failed")
            );
        }
        return null;
    }

    private boolean isTerminal(String status) {
        return DispatchStatus.SUCCEEDED.equals(status) || isFailed(status);
    }

    private boolean isFailed(String status) {
        return DispatchStatus.FAILED.equals(status)
                || DispatchStatus.TIMEOUT.equals(status)
                || DispatchStatus.CANCELED.equals(status)
                || DispatchStatus.PAUSE_FAILED.equals(status);
    }

    private SubStepVO subStep(String name, String status) {
        SubStepVO vo = new SubStepVO();
        vo.setName(name);
        vo.setStatus(status);
        return vo;
    }

    public List<ParticipantVO> getParticipants(long workitemId, long tenantId) {
        WorkitemDO w = workitemDao.findById(workitemId);
        if (w == null) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
        List<DispatchDO> dispatches = dispatchDao.listByWorkitem(tenantId, workitemId);
        if (dispatches == null) {
            dispatches = List.of();
        }
        LinkedHashMap<Long, Long> seedAgentIds = new LinkedHashMap<>();
        for (DispatchDO d : dispatches) {
            if (d.getAgentId() != null) {
                seedAgentIds.putIfAbsent(d.getAgentId(), d.getAgentId());
            }
        }
        if ("AGENT".equals(w.getAssigneeType()) && w.getAssigneeRef() != null) {
            seedAgentIds.putIfAbsent(w.getAssigneeRef(), w.getAssigneeRef());
        }

        List<SquadMemberDO> squadMembers = resolveSquadMembers(tenantId, new ArrayList<>(seedAgentIds.keySet()));
        LinkedHashMap<Long, Long> participantAgentIds = new LinkedHashMap<>();
        if (!squadMembers.isEmpty()) {
            for (SquadMemberDO member : squadMembers) {
                if (member.getAgentId() != null) {
                    participantAgentIds.putIfAbsent(member.getAgentId(), member.getAgentId());
                }
            }
        } else {
            participantAgentIds.putAll(seedAgentIds);
        }

        List<ParticipantVO> participants = new ArrayList<>();
        for (Long humanId : participantHumanIds(tenantId, w)) {
            UserDO user = userDao.findById(humanId);
            if (user != null) {
                participants.add(toHumanParticipant(user));
            }
        }
        for (Long agentId : participantAgentIds.keySet()) {
            participants.add(toAgentParticipant(tenantId, agentId));
        }
        return participants;
    }

    public List<ParticipantVO> getMentionCandidates(long workitemId, long tenantId) {
        return getMentionCandidates(workitemId, tenantId, null, 50);
    }

    public List<ParticipantVO> getMentionCandidates(long workitemId, long tenantId, String query, int limit) {
        int effectiveLimit = limit <= 0 ? 50 : Math.min(limit, 100);
        LinkedHashMap<String, ParticipantVO> candidates = new LinkedHashMap<>();
        for (ParticipantVO participant : getParticipants(workitemId, tenantId)) {
            addMentionCandidate(candidates, participant, query, effectiveLimit);
        }
        for (AgentDO agent : safeList(agentDao.listByTenant(tenantId))) {
            if (agent == null || agent.getId() == null || agent.getOnlineVersionId() == null) {
                continue;
            }
            addMentionCandidate(candidates, toAgentParticipant(tenantId, agent.getId()), query, effectiveLimit);
        }
        if (orgMemberDao != null) {
            for (OrgMemberDO member : safeList(orgMemberDao.listByTenant(tenantId))) {
                if (member == null || member.getUserId() == null
                        || member.getStatus() == null || member.getStatus() != 0) {
                    continue;
                }
                UserDO user = userDao.findById(member.getUserId());
                if (user != null) {
                    addMentionCandidate(candidates, toHumanParticipant(user), query, effectiveLimit);
                }
            }
        }
        return new ArrayList<>(candidates.values());
    }

    private void addMentionCandidate(LinkedHashMap<String, ParticipantVO> candidates,
            ParticipantVO participant, String query, int limit) {
        if (participant == null || participant.getUserId() == null || candidates.size() >= limit) {
            return;
        }
        if (!matchesMentionQuery(participant, query)) {
            return;
        }
        String targetType = participant.getTargetType() != null
                ? participant.getTargetType() : (participant.isAgent() ? "AGENT" : "HUMAN");
        candidates.putIfAbsent(targetType + ":" + participant.getUserId(), participant);
    }

    private List<Long> participantHumanIds(long tenantId, WorkitemDO workitem) {
        LinkedHashMap<Long, Long> ids = new LinkedHashMap<>();
        if (workitem.getCreatorId() != null) {
            ids.putIfAbsent(workitem.getCreatorId(), workitem.getCreatorId());
        }
        if ("HUMAN".equals(workitem.getAssigneeType()) && workitem.getAssigneeRef() != null) {
            ids.putIfAbsent(workitem.getAssigneeRef(), workitem.getAssigneeRef());
        }
        for (WorkitemCommentDO comment : safeList(commentDao.listByWorkitem(workitem.getId()))) {
            if (comment != null && "HUMAN".equals(comment.getAuthorType()) && comment.getAuthorRef() != null) {
                ids.putIfAbsent(comment.getAuthorRef(), comment.getAuthorRef());
            }
        }
        if (commentMentionDao != null) {
            for (WorkitemCommentMentionDO mention : safeList(commentMentionDao.listByWorkitem(tenantId, workitem.getId()))) {
                if (mention != null && "HUMAN".equals(mention.getTargetType()) && mention.getTargetRef() != null) {
                    ids.putIfAbsent(mention.getTargetRef(), mention.getTargetRef());
                }
            }
        }
        return new ArrayList<>(ids.keySet());
    }

    private List<SquadMemberDO> resolveSquadMembers(long tenantId, List<Long> agentIds) {
        if (agentIds.isEmpty()) {
            return List.of();
        }
        Set<Long> seedIds = new HashSet<>(agentIds);
        List<SquadMemberDO> bestMembers = List.of();
        int bestScore = 0;
        Set<Long> visitedSquads = new HashSet<>();
        for (Long agentId : agentIds) {
            for (SquadMemberDO link : safeList(squadMemberDao.listByAgent(agentId))) {
                if (link == null || link.getSquadId() == null || !visitedSquads.add(link.getSquadId())) {
                    continue;
                }
                List<SquadMemberDO> members = safeList(squadMemberDao.listBySquad(link.getSquadId())).stream()
                        .filter(m -> m != null && Long.valueOf(tenantId).equals(m.getTenantId()))
                        .collect(Collectors.toList());
                int score = (int) members.stream()
                        .filter(m -> seedIds.contains(m.getAgentId()))
                        .count();
                if (score > bestScore || (score == bestScore && members.size() > bestMembers.size())) {
                    bestScore = score;
                    bestMembers = members;
                }
            }
        }
        return bestMembers;
    }

    private ParticipantVO toAgentParticipant(long tenantId, Long agentId) {
        ParticipantVO vo = new ParticipantVO();
        vo.setUserId(agentId);
        vo.setTargetType("AGENT");
        vo.setDisplayId(String.valueOf(agentId));
        vo.setAgent(true);
        vo.setRole("AGENT");
        vo.setRoleName("开发小队成员");
        AgentDO agent = agentDao.findById(agentId);
        if (agent != null) {
            vo.setName(agent.getName());
            vo.setStatus(agent.getStatus());
        }
        String executorStatus = resolveExecutorStatus(tenantId, agentId);
        vo.setExecutorStatus(executorStatus);
        vo.setOnline("ONLINE".equals(executorStatus) || "BUSY".equals(executorStatus));
        return vo;
    }

    private String resolveExecutorStatus(long tenantId, Long agentId) {
        List<ExecutorDO> executors = safeList(executorDao.listByAgent(tenantId, agentId));
        List<ExecutorDO> onlineExecutors = executors.stream()
                .filter(e -> e != null && e.getId() != null && presenceManager.isExecutorOnline(e.getId()))
                .toList();
        if (onlineExecutors.stream().anyMatch(e -> "BUSY".equals(e.getStatus()))) {
            return "BUSY";
        }
        if (!onlineExecutors.isEmpty()) {
            return "ONLINE";
        }
        return "OFFLINE";
    }

    private <T> List<T> safeList(List<T> rows) {
        return rows == null ? List.of() : rows;
    }

    public List<TimelineItemVO> getUnifiedTimeline(long workitemId) {
        List<TimelineItemVO> items = new ArrayList<>();

        for (WorkitemCommentDO c : commentDao.listByWorkitem(workitemId)) {
            TimelineItemVO item = new TimelineItemVO();
            item.setId(c.getId());
            item.setType("comment");
            item.setAuthorId(c.getAuthorRef());
            item.setAuthorType(c.getAuthorType());
            item.setAgent("AGENT".equals(c.getAuthorType()));
            item.setContent(c.getContentMd());
            item.setGmtCreate(c.getGmtCreate());
            item.setAuthorName(resolveActorDisplayName(c.getAuthorType(), c.getAuthorRef()));
            items.add(item);
        }

        for (WorkitemEventDO e : eventDao.listByWorkitem(workitemId)) {
            TimelineItemVO item = new TimelineItemVO();
            item.setId(e.getId());
            item.setType("system");
            item.setAuthorId(e.getActorRef());
            item.setAuthorType(e.getActorType());
            item.setAgent("AGENT".equals(e.getActorType()));
            item.setGmtCreate(e.getGmtCreate());
            item.setAuthorName(resolveActorDisplayName(e.getActorType(), e.getActorRef()));
            String fromVal = resolveEventValueDisplay(e, e.getFromVal(), "fromType");
            String toVal = resolveEventValueDisplay(e, e.getToVal(), "toType");
            if (e.getFromVal() != null && e.getToVal() != null) {
                item.setContent(e.getEventType() + ": " + fromVal + " → " + toVal);
            } else if (e.getToVal() != null) {
                item.setContent(e.getEventType() + ": → " + toVal);
            } else {
                item.setContent(e.getEventType());
            }
            items.add(item);
        }

        items.sort(Comparator.comparing(TimelineItemVO::getGmtCreate));
        return items;
    }

    private String resolveActorName(String actorType, Long actorRef) {
        if (actorRef == null) {
            return null;
        }
        if ("AGENT".equals(actorType)) {
            AgentDO agent = agentDao.findById(actorRef);
            return agent == null ? null : agent.getName();
        }
        if ("HUMAN".equals(actorType)) {
            UserDO user = userDao.findById(actorRef);
            if (user == null) {
                return null;
            }
            if (user.getNickname() != null && !user.getNickname().isBlank()) {
                return user.getNickname();
            }
            return user.getUsername();
        }
        return null;
    }

    private String resolveHumanName(long userId) {
        String name = resolveActorName("HUMAN", userId);
        return (name == null || name.isBlank()) ? "用户" : name;
    }

    private String currentRequestId() {
        String requestId = MDC.get(BizLoggerFilter.REQUEST_ID_KEY);
        if (requestId != null && !requestId.isBlank()) {
            return requestId;
        }
        requestId = AutoWonderContext.get().getRequestId();
        return requestId == null || requestId.isBlank() ? null : requestId;
    }

    private String resolveActorDisplayName(String actorType, Long actorRef) {
        String name = resolveActorName(actorType, actorRef);
        if (actorRef == null) {
            return name;
        }
        if (name == null || name.isBlank()) {
            return String.valueOf(actorRef);
        }
        return name + "(" + actorRef + ")";
    }

    private String resolveEventValueDisplay(WorkitemEventDO event, String value, String typeKey) {
        if (value == null || value.isBlank() || event == null || !"ASSIGN".equals(event.getEventType())) {
            return value;
        }
        try {
            long ref = Long.parseLong(value);
            String explicitType = eventDetailType(event.getDetailJson(), typeKey);
            if (explicitType != null) {
                String explicitDisplay = resolveActorDisplayName(explicitType, ref);
                return explicitDisplay == null ? value : explicitDisplay;
            }
            String agentDisplay = resolveActorDisplayName("AGENT", ref);
            if (agentDisplay != null && !agentDisplay.equals(value)) {
                return agentDisplay;
            }
            String humanDisplay = resolveActorDisplayName("HUMAN", ref);
            return humanDisplay == null ? value : humanDisplay;
        } catch (NumberFormatException ignored) {
            return value;
        }
    }

    private String eventDetailType(String detailJson, String key) {
        if (detailJson == null || detailJson.isBlank()) {
            return null;
        }
        try {
            JSONObject detail = JSON.parseObject(detailJson);
            String value = detail == null ? null : detail.getString(key);
            return ("AGENT".equals(value) || "HUMAN".equals(value)) ? value : null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private CommentVO toCommentVO(WorkitemCommentDO c) {
        CommentVO vo = new CommentVO();
        vo.setId(c.getId());
        vo.setWorkitemId(c.getWorkitemId());
        vo.setAuthorType(c.getAuthorType());
        vo.setAuthorRef(c.getAuthorRef());
        vo.setContentMd(c.getContentMd());
        vo.setGmtCreate(c.getGmtCreate());
        return vo;
    }

    void writeEvent(long tenantId, long workitemId, String eventType,
                    String fromVal, String toVal, String actorType, Long actorRef) {
        writeEvent(tenantId, workitemId, eventType, fromVal, toVal, actorType, actorRef, null);
    }

    void writeEvent(long tenantId, long workitemId, String eventType,
                    String fromVal, String toVal, String actorType, Long actorRef, String detailJson) {
        WorkitemEventDO e = new WorkitemEventDO();
        e.setTenantId(tenantId);
        e.setWorkitemId(workitemId);
        e.setEventType(eventType);
        e.setFromVal(fromVal);
        e.setToVal(toVal);
        e.setActorType(actorType);
        e.setActorRef(actorRef);
        e.setDetailJson(detailJson);
        eventDao.insert(e);
    }

    private String assignDetailJson(String fromType, String toType) {
        return assignmentDetailJson(fromType, toType);
    }

    static boolean isAssignmentType(String type) {
        return "HUMAN".equals(type) || "AGENT".equals(type);
    }

    public static String assignmentDetailJson(String fromType, String toType) {
        Map<String, String> detail = new LinkedHashMap<>();
        if (isAssignmentType(fromType)) {
            detail.put("fromType", fromType);
        }
        if (isAssignmentType(toType)) {
            detail.put("toType", toType);
        }
        return detail.isEmpty() ? null : JSON.toJSONString(detail);
    }

    WorkitemVO toVO(WorkitemDO w) {
        WorkitemVO vo = new WorkitemVO();
        vo.setId(w.getId());
        vo.setWorkType(w.getWorkType());
        vo.setTitle(w.getTitle());
        vo.setContentMd(w.getContentMd());
        vo.setTemplateId(w.getTemplateId());
        vo.setStatusNodeId(w.getStatusNodeId());
        vo.setAssigneeType(w.getAssigneeType());
        vo.setAssigneeRef(w.getAssigneeRef());
        vo.setCreatorId(w.getCreatorId());
        vo.setCreatorName(resolveActorName("HUMAN", w.getCreatorId()));
        vo.setCreatorDisplayName(resolveActorDisplayName("HUMAN", w.getCreatorId()));
        vo.setPriority(w.getPriority());
        vo.setVersion(w.getVersion());
        vo.setGmtCreate(w.getGmtCreate());
        vo.setGmtModified(w.getGmtModified());
        vo.setPendingDecision(false);

        // Resolve status node name
        if (w.getStatusNodeId() != null) {
            StatusNodeDO node = nodeDao.findById(w.getStatusNodeId());
            if (node != null) {
                vo.setStatusName(node.getName());
            }
        }

        // Resolve SDLC name
        vo.setSdlcId(w.getSdlcId());
        if (w.getSdlcId() != null) {
            SdlcDO sdlc = sdlcDao.findById(w.getSdlcId());
            if (sdlc != null) {
                vo.setSdlcName(sdlc.getName());
            }
        }

        if (w.getAssigneeRef() != null) {
            vo.setAssigneeName(resolveActorName(w.getAssigneeType(), w.getAssigneeRef()));
            vo.setAssigneeDisplayName(resolveActorDisplayName(w.getAssigneeType(), w.getAssigneeRef()));
        }

        applyDeleteEligibility(vo, w);
        return vo;
    }

    private void applyDeleteEligibility(WorkitemVO vo, WorkitemDO w) {
        vo.setSourceType(SOURCE_TYPE_NATIVE);
        vo.setDeletable(true);
        vo.setDeletableReason(null);
        if (w.getId() == null || w.getTenantId() == null) {
            return;
        }
        if (!safeList(externalWorkitemLinkDao.listByWorkitem(w.getTenantId(), w.getId())).isEmpty()) {
            vo.setSourceType(SOURCE_TYPE_EXTERNAL);
            vo.setDeletable(false);
            vo.setDeletableReason(ErrorCode.WORKITEM_EXTERNAL_NO_DELETE.getMessage());
            return;
        }
        boolean running = safeList(dispatchDao.listByWorkitem(w.getTenantId(), w.getId())).stream()
                .anyMatch(d -> d != null && ACTIVE_DISPATCH_STATUSES.contains(d.getStatus()));
        if (running) {
            vo.setDeletable(false);
            vo.setDeletableReason(ErrorCode.WORKITEM_RUNNING_NO_DELETE.getMessage());
        }
    }

    WorkitemVO toVO(WorkitemDO w, Map<Long, UserDO> users, Map<Long, AgentDO> agents,
                    Map<Long, StatusNodeDO> nodes, Map<Long, SdlcDO> sdlcs) {
        WorkitemVO vo = new WorkitemVO();
        vo.setId(w.getId());
        vo.setWorkType(w.getWorkType());
        vo.setTitle(w.getTitle());
        vo.setContentMd(w.getContentMd());
        vo.setTemplateId(w.getTemplateId());
        vo.setStatusNodeId(w.getStatusNodeId());
        vo.setAssigneeType(w.getAssigneeType());
        vo.setAssigneeRef(w.getAssigneeRef());
        vo.setCreatorId(w.getCreatorId());
        vo.setCreatorName(resolveActorNameFromMaps("HUMAN", w.getCreatorId(), users, agents));
        vo.setCreatorDisplayName(resolveActorDisplayNameFromMaps("HUMAN", w.getCreatorId(), users, agents));
        vo.setPriority(w.getPriority());
        vo.setVersion(w.getVersion());
        vo.setGmtCreate(w.getGmtCreate());
        vo.setGmtModified(w.getGmtModified());
        vo.setPendingDecision(false);

        if (w.getStatusNodeId() != null) {
            StatusNodeDO node = nodes.get(w.getStatusNodeId());
            if (node != null) {
                vo.setStatusName(node.getName());
            }
        }

        vo.setSdlcId(w.getSdlcId());
        if (w.getSdlcId() != null) {
            SdlcDO sdlc = sdlcs.get(w.getSdlcId());
            if (sdlc != null) {
                vo.setSdlcName(sdlc.getName());
            }
        }

        if (w.getAssigneeRef() != null) {
            vo.setAssigneeName(resolveActorNameFromMaps(w.getAssigneeType(), w.getAssigneeRef(), users, agents));
            vo.setAssigneeDisplayName(resolveActorDisplayNameFromMaps(w.getAssigneeType(), w.getAssigneeRef(), users, agents));
        }

        return vo;
    }

    private void applyDeleteEligibility(WorkitemVO vo, WorkitemDO w,
                                        Map<Long, List<ExternalWorkitemLinkDO>> extLinksMap,
                                        Map<Long, List<DispatchDO>> allDispatches) {
        vo.setSourceType(SOURCE_TYPE_NATIVE);
        vo.setDeletable(true);
        vo.setDeletableReason(null);
        if (w.getId() == null || w.getTenantId() == null) {
            return;
        }
        List<ExternalWorkitemLinkDO> links = extLinksMap.getOrDefault(w.getId(), List.of());
        if (!links.isEmpty()) {
            vo.setSourceType(SOURCE_TYPE_EXTERNAL);
            vo.setDeletable(false);
            vo.setDeletableReason(ErrorCode.WORKITEM_EXTERNAL_NO_DELETE.getMessage());
            return;
        }
        boolean running = allDispatches.getOrDefault(w.getId(), List.of()).stream()
                .anyMatch(d -> d != null && ACTIVE_DISPATCH_STATUSES.contains(d.getStatus()));
        if (running) {
            vo.setDeletable(false);
            vo.setDeletableReason(ErrorCode.WORKITEM_RUNNING_NO_DELETE.getMessage());
        }
    }

    private String resolveActorNameFromMaps(String actorType, Long actorRef,
                                            Map<Long, UserDO> users, Map<Long, AgentDO> agents) {
        if (actorRef == null) {
            return null;
        }
        if ("AGENT".equals(actorType)) {
            AgentDO agent = agents.get(actorRef);
            return agent == null ? null : agent.getName();
        }
        if ("HUMAN".equals(actorType)) {
            UserDO user = users.get(actorRef);
            if (user == null) {
                return null;
            }
            if (user.getNickname() != null && !user.getNickname().isBlank()) {
                return user.getNickname();
            }
            return user.getUsername();
        }
        return null;
    }

    private String resolveActorDisplayNameFromMaps(String actorType, Long actorRef,
                                                   Map<Long, UserDO> users, Map<Long, AgentDO> agents) {
        String name = resolveActorNameFromMaps(actorType, actorRef, users, agents);
        if (actorRef == null) {
            return name;
        }
        if (name == null || name.isBlank()) {
            return String.valueOf(actorRef);
        }
        return name + "(" + actorRef + ")";
    }
}
