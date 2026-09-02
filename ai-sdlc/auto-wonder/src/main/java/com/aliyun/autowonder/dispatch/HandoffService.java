package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.filter.BizLoggerFilter;
import com.aliyun.autowonder.im.notification.WorkitemHumanAssignedEvent;
import com.aliyun.autowonder.workspace.WorkspaceDO;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import com.aliyun.autowonder.sdlc.SdlcStepDO;
import com.aliyun.autowonder.workitem.AssignmentActor;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemEventDO;
import com.aliyun.autowonder.workitem.WorkitemEventType;
import com.aliyun.autowonder.workitem.WorkitemEventDao;
import com.aliyun.autowonder.workitem.WorkitemService;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunOrchestrator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Handles a workitem hand-off. Routing is server-authoritative: the requested
 * target {@code to} is first resolved against online agents for the tenant. If it
 * resolves to an online agent, the workitem is rebound onto that agent's OWN SDLC
 * (its min-stepOrder first step), a dispatch is enqueued there, and the assignee is
 * synced to the target agent. An unresolved AGENT request is assigned only to the
 * workitem's configured human operator, so a completed digital-worker step cannot
 * strand a workitem or unexpectedly escalate to an workspace owner. Explicit
 * HUMAN requests use the regular fallback chain:
 * concrete numeric user id, then the workitem's
 * assign-operator, then the tenant admin (workspace owner).
 */
@Service
public class HandoffService {

    private static final Logger log = LoggerFactory.getLogger(HandoffService.class);
    private static final long SYSTEM_USER_ID = 0L;
    private static final int MAX_AUTOMATIC_HANDOFF_REPEATS = 5;

    private final WorkitemDao workitemDao;
    private final DispatchService dispatchService;
    private final AgentRoleResolver roleResolver;
    private final AgentSdlcResolver sdlcResolver;
    private final WorkspaceDao workspaceDao;
    private final WorkitemEventDao eventDao;
    private final DispatchDao dispatchDao;
    private final AgentDao agentDao;
    private final ApplicationEventPublisher eventPublisher;
    private ScheduledTaskRunOrchestrator scheduledTaskRunOrchestrator;

    @Autowired
    public void setScheduledTaskRunOrchestrator(ScheduledTaskRunOrchestrator scheduledTaskRunOrchestrator) {
        this.scheduledTaskRunOrchestrator = scheduledTaskRunOrchestrator;
    }

    public HandoffService(WorkitemDao workitemDao, DispatchService dispatchService,
            AgentRoleResolver roleResolver, AgentSdlcResolver sdlcResolver, WorkspaceDao workspaceDao,
            WorkitemEventDao eventDao) {
        this(workitemDao, dispatchService, roleResolver, sdlcResolver, workspaceDao, eventDao,
                null, null, null);
    }

    @Autowired
    public HandoffService(WorkitemDao workitemDao, DispatchService dispatchService,
            AgentRoleResolver roleResolver, AgentSdlcResolver sdlcResolver, WorkspaceDao workspaceDao,
            WorkitemEventDao eventDao, DispatchDao dispatchDao, AgentDao agentDao,
            ApplicationEventPublisher eventPublisher) {
        this.workitemDao = workitemDao;
        this.dispatchService = dispatchService;
        this.roleResolver = roleResolver;
        this.sdlcResolver = sdlcResolver;
        this.workspaceDao = workspaceDao;
        this.eventDao = eventDao;
        this.dispatchDao = dispatchDao;
        this.agentDao = agentDao;
        this.eventPublisher = eventPublisher;
    }

    @Transactional
    public HandoffResult handle(long tenantId, long workitemId, long dispatchId, String to, String toType) {
        DispatchDO source = dispatchDao == null ? null : dispatchDao.findById(dispatchId);
        if (source != null && source.executionSourceType() == ExecutionSourceType.SCHEDULED_TASK_RUN) {
            if (!Long.valueOf(tenantId).equals(source.getTenantId())
                    || source.getWorkitemId() == null || scheduledTaskRunOrchestrator == null) {
                return HandoffResult.rejected("DISPATCH_NOT_FOUND", "source scheduled dispatch not found");
            }
            // Do not trust the legacy frame's workitemId. The durable source dispatch owns the Run id.
            return scheduledTaskRunOrchestrator.handoff(source, to);
        }
        try {
            dispatchService.requireWorkitemDispatchBoundary(tenantId, workitemId, dispatchId);
        } catch (BizException invalidSource) {
            log.info("handoff source dispatch not found or not a workitem dispatch dispatchId={}", dispatchId);
            return HandoffResult.rejected("DISPATCH_NOT_FOUND", "source dispatch not found for work item");
        }
        // Serialize handoff against comment-triggered rework on the same workitem.
        // Without this row lock an old dispatch can pass the superseded check,
        // then overwrite the newer rework assignee after the rework transaction commits.
        WorkitemDO w = workitemDao.findByIdForUpdate(workitemId, tenantId);
        if (w == null || tenantId != w.getTenantId()) {
            log.info("handoff workitem not found or cross-tenant workitemId={}", workitemId);
            return HandoffResult.rejected("WORKITEM_NOT_FOUND", "work item not found in tenant");
        }
        if (dispatchService.isSupersededByInteractionRework(tenantId, workitemId, dispatchId)) {
            log.info("handoff rejected because source was superseded workitemId={} dispatchId={}",
                    workitemId, dispatchId);
            return HandoffResult.rejected("SOURCE_SUPERSEDED", "source dispatch was superseded by comment rework");
        }

        DispatchDO existing = dispatchService.findHandoffBySource(tenantId, dispatchId);
        if (existing != null) {
            // A prior delivery of the same durable handoff may have created the
            // downstream row while its first scheduling attempt found no capacity.
            // Replaying the source result must actively drain that PENDING row;
            // merely returning the id leaves a valid handoff stranded forever.
            if (DispatchStatus.PENDING.equals(existing.getStatus())) {
                dispatchService.runPending(existing.getId());
            }
            return HandoffResult.agent(existing.getAgentId(), existing.getId());
        }

        Long targetAgentId = null;
        if (to != null && !to.isBlank()) {
            targetAgentId = roleResolver.resolveOnlineAgentId(tenantId, to);
        }
        if (targetAgentId != null) {
            if (dispatchService.hasReachedAutomaticHandoffLimit(tenantId, workitemId,
                    dispatchId, targetAgentId, MAX_AUTOMATIC_HANDOFF_REPEATS)) {
                log.warn("automatic handoff limit reached workitemId={} sourceDispatchId={} targetAgentId={} limit={}",
                        workitemId, dispatchId, targetAgentId, MAX_AUTOMATIC_HANDOFF_REPEATS);
            return handleHumanHandoff(tenantId, workitemId, dispatchId, null, w, "AUTOMATIC_HANDOFF_LIMIT", true);
            }
            return handleAgentHandoff(tenantId, workitemId, dispatchId, to, targetAgentId, w);
        }
        if (!"HUMAN".equalsIgnoreCase(toType)) {
            log.info("handoff agent target unavailable; falling back to human tenantId={} workitemId={} target={}",
                    tenantId, workitemId, to);
            return handleHumanHandoff(tenantId, workitemId, dispatchId, null, w, "UNKNOWN_AGENT_FALLBACK_HUMAN", false);
        }
        return handleHumanHandoff(tenantId, workitemId, dispatchId, to, w, "REQUESTED_HUMAN", true);
    }

    private HandoffResult handleAgentHandoff(long tenantId, long workitemId, long dispatchId,
            String to, Long targetAgentId, WorkitemDO w) {
        Long targetSdlcId = sdlcResolver.resolveSdlcId(tenantId, targetAgentId);
        if (targetSdlcId == null) {
            log.info("handoff target agent has no sdlc tenantId={} agentId={}", tenantId, targetAgentId);
            return HandoffResult.rejected("TARGET_AGENT_HAS_NO_SDLC", "target agent has no SDLC");
        }
        SdlcStepDO first = sdlcResolver.firstStep(tenantId, targetSdlcId);
        if (first == null) {
            log.info("handoff target sdlc has no steps sdlcId={}", targetSdlcId);
            return HandoffResult.rejected("TARGET_SDLC_HAS_NO_STEPS", "target SDLC has no steps");
        }

        workitemDao.updateSdlcAndStep(workitemId, tenantId, targetSdlcId, first.getId(),
                w.getVersion(), SYSTEM_USER_ID);
        WorkitemDO reloaded = workitemDao.findById(workitemId);
        Integer nextVersion = reloaded != null ? reloaded.getVersion() : w.getVersion();
        workitemDao.updateAssignee(workitemId, tenantId, "AGENT", targetAgentId, nextVersion, SYSTEM_USER_ID);
        AssignmentActor sourceActor = resolveSourceActor(tenantId, workitemId, dispatchId, "AGENT_HANDOFF");
        writeAssignEvent(tenantId, workitemId, w.getAssigneeRef(), targetAgentId, sourceActor,
                w.getAssigneeType(), "AGENT");
        log.info("handoff to AGENT workitemId={} target={} targetAgentId={} sdlcId={} firstStepId={}",
                workitemId, to, targetAgentId, targetSdlcId, first.getId());
        DispatchDO d = dispatchService.enqueueHandoff(tenantId, workitemId,
                first.getId(), targetAgentId, dispatchId, SYSTEM_USER_ID);
        dispatchService.runPending(d.getId());
        return HandoffResult.agent(targetAgentId, d.getId());
    }

    private HandoffResult handleHumanHandoff(long tenantId, long workitemId, long dispatchId, String to, WorkitemDO w,
            String fallbackReason, boolean allowTenantOwnerFallback) {
        Long resolved = resolveConcreteHuman(to);
        if (resolved == null) {
            resolved = w.getAssignOperatorId();
        }
        if (resolved == null && allowTenantOwnerFallback) {
            resolved = resolveTenantAdminUserId(tenantId);
        }
        if (resolved == null) {
            log.info("handoff human target unresolved and no fallback tenantId={} workitemId={} to={}",
                    tenantId, workitemId, to);
            return HandoffResult.rejected("TARGET_UNRESOLVED", "no agent or human fallback resolved");
        }
        if ("HUMAN".equalsIgnoreCase(w.getAssigneeType()) && resolved.equals(w.getAssigneeRef())) {
            return HandoffResult.human(resolved, fallbackReason);
        }
        int rows = workitemDao.updateAssignee(workitemId, tenantId, "HUMAN", resolved, w.getVersion(), SYSTEM_USER_ID);
        if (rows == 0) {
            throw new BizException(ErrorCode.WORKITEM_VERSION_CONFLICT);
        }
        AssignmentActor actor = resolveSourceActor(tenantId, workitemId, dispatchId, fallbackReason);
        WorkitemEventDO event = writeAssignEvent(tenantId, workitemId, w.getAssigneeRef(), resolved, actor, w.getAssigneeType(), "HUMAN");
        if (eventPublisher != null && event.getId() != null) {
            eventPublisher.publishEvent(new WorkitemHumanAssignedEvent(
                    tenantId,
                    workitemId,
                    w.getTitle(),
                    event.getId(),
                    resolved,
                    actor.type(),
                    actor.ref(),
                    actor.displayName(),
                    currentRequestId()));
        }
        log.info("handoff to HUMAN workitemId={} target={} resolvedUserId={}", workitemId, to, resolved);
        return HandoffResult.human(resolved, fallbackReason);
    }

    private WorkitemEventDO writeAssignEvent(long tenantId, long workitemId, Long fromRef, Long toRef,
            AssignmentActor actor, String fromType, String toType) {
        WorkitemEventDO e = new WorkitemEventDO();
        e.setTenantId(tenantId);
        e.setWorkitemId(workitemId);
        e.setEventType(WorkitemEventType.ASSIGN.code());
        e.setFromVal(fromRef == null ? null : String.valueOf(fromRef));
        e.setToVal(toRef == null ? null : String.valueOf(toRef));
        e.setActorType(actor.type());
        e.setActorRef(actor.ref());
        e.setDetailJson(WorkitemService.assignmentDetailJson(fromType, toType));
        eventDao.insert(e);
        return e;
    }

    private AssignmentActor resolveSourceActor(long tenantId, long workitemId, long dispatchId,
            String fallbackReason) {
        if (dispatchDao == null || agentDao == null) {
            log.warn("handoff source actor resolution unavailable tenantId={} workitemId={} sourceDispatchId={} reason={}",
                    tenantId, workitemId, dispatchId, fallbackReason);
            return AssignmentActor.system("系统");
        }
        try {
            DispatchDO source = dispatchDao.findById(dispatchId);
            if (source == null
                    || !Long.valueOf(tenantId).equals(source.getTenantId())
                    || source.executionSourceType() != ExecutionSourceType.WORKITEM
                    || !Long.valueOf(workitemId).equals(source.getWorkitemId())
                    || source.getAgentId() == null
                    || source.getAgentId() <= 0) {
                log.warn("handoff source actor resolution failed tenantId={} workitemId={} sourceDispatchId={} "
                                + "sourceTenantId={} sourceWorkitemId={} sourceAgentId={} reason={}",
                        tenantId, workitemId, dispatchId,
                        source == null ? null : source.getTenantId(),
                        source == null ? null : source.getWorkitemId(),
                        source == null ? null : source.getAgentId(),
                        fallbackReason);
                return AssignmentActor.system("系统");
            }
            AgentDO agent = agentDao.findById(source.getAgentId());
            if (agent == null
                    || (agent.getTenantId() != null && !Long.valueOf(tenantId).equals(agent.getTenantId()))
                    || agent.getName() == null
                    || agent.getName().isBlank()) {
                log.warn("handoff source agent resolution failed tenantId={} workitemId={} sourceDispatchId={} "
                                + "sourceAgentId={} sourceAgentTenantId={} reason={}",
                        tenantId, workitemId, dispatchId, source.getAgentId(),
                        agent == null ? null : agent.getTenantId(), fallbackReason);
                return AssignmentActor.system("系统");
            }
            return AssignmentActor.agent(source.getAgentId(), agent.getName());
        } catch (RuntimeException e) {
            log.warn("handoff source actor resolution exception tenantId={} workitemId={} sourceDispatchId={} reason={}",
                    tenantId, workitemId, dispatchId, fallbackReason, e);
            return AssignmentActor.system("系统");
        }
    }

    private Long resolveConcreteHuman(String to) {
        if (to == null || to.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(to.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Long resolveTenantAdminUserId(long tenantId) {
        WorkspaceDO workspace = workspaceDao.findById(tenantId);
        return workspace != null ? workspace.getOwnerId() : null;
    }

    private String currentRequestId() {
        String requestId = MDC.get(BizLoggerFilter.REQUEST_ID_KEY);
        if (requestId != null && !requestId.isBlank()) {
            return requestId;
        }
        requestId = AutoWonderContext.get().getRequestId();
        return requestId == null || requestId.isBlank() ? null : requestId;
    }
}
