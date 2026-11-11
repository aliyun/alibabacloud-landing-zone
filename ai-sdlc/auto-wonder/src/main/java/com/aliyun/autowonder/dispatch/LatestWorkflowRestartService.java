package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.workitem.AssignmentActor;
import com.aliyun.autowonder.workitem.DeliveryRestartStore;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemService;
import com.aliyun.autowonder.workitem.WorkitemServiceRestartHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.sdlc.SdlcDao;

import java.util.Comparator;
import java.util.Map;
import java.util.Objects;

@Service
public class LatestWorkflowRestartService {
    private final WorkitemDao workitems;
    private final DispatchDao dispatches;
    private final DispatchRecoveryService recovery;
    private final AgentSdlcResolver resolver;
    private final ExecutionAvailabilityService availability;
    private final WorkitemService workitemService;
    private final WorkitemServiceRestartHandler restarts;
    private final DeliveryRestartStore restartStore;
    private final DispatchWorkflowSnapshotStore snapshots;
    private final AgentDao agents;
    private final SdlcDao flows;

    public LatestWorkflowRestartService(WorkitemDao workitems, DispatchDao dispatches,
            DispatchRecoveryService recovery, AgentSdlcResolver resolver, ExecutionAvailabilityService availability,
            WorkitemService workitemService, WorkitemServiceRestartHandler restarts,
            DeliveryRestartStore restartStore, DispatchWorkflowSnapshotStore snapshots, AgentDao agents, SdlcDao flows) {
        this.workitems = workitems;
        this.dispatches = dispatches;
        this.recovery = recovery;
        this.resolver = resolver;
        this.availability = availability;
        this.workitemService = workitemService;
        this.restarts = restarts;
        this.restartStore = restartStore;
        this.snapshots = snapshots;
        this.agents = agents;
        this.flows = flows;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Map<String, Object> restart(long tenantId, long workitemId, long sourceId, long userId) {
        // The same workitem -> control lock order is used by comment rework activation.
        var workitem = workitems.findByIdForUpdate(workitemId, tenantId);
        if (workitem == null) throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        DispatchDO source = dispatches.findById(sourceId);
        if (source == null || !Objects.equals(source.getTenantId(), tenantId)
                || !Objects.equals(source.getWorkitemId(), workitemId)
                || source.executionSourceType() != ExecutionSourceType.WORKITEM
                || DispatchService.isInteractionDispatchValue(source)) throw new BizException(ErrorCode.DISPATCH_NOT_FOUND);
        recovery.lockWorkitemControl(source);
        String token = "latest-workflow:" + sourceId;
        Integer round = restartStore.roundByToken(tenantId, workitemId, token);
        if (round != null) return result(tenantId, workitemId, sourceId, round);
        recovery.requireOpen(source);
        DispatchDO latest = dispatches.listByWorkitem(tenantId, workitemId).stream()
                .filter(row -> !DispatchService.isInteractionDispatchValue(row))
                .max(Comparator.comparingLong(DispatchDO::getId)).orElse(null);
        if (latest == null || !Objects.equals(latest.getId(), sourceId)
                || !DispatchStatus.PAUSED.equals(source.getStatus()) || snapshots.fenced(source) || recovery.fenced(source)) {
            throw BizException.keyed(ErrorCode.CONFLICT, "dispatch.restart_latest.paused_required", new Object[0],
                    "only the current paused formal execution can restart with the latest workflow");
        }
        var agent = agents.findByIdForUpdate(tenantId, source.getAgentId());
        if (agent == null) throw new BizException(ErrorCode.AGENT_NOT_FOUND);
        Long flowId = resolver.resolveSdlcId(tenantId, source.getAgentId());
        var entry = flowId == null ? null : resolver.firstStep(tenantId, flowId);
        if (entry == null) throw new BizException(ErrorCode.SDLC_STEP_NOT_FOUND);
        if (flows.findByIdForUpdate(flowId, tenantId) == null) throw new BizException(ErrorCode.SDLC_NOT_FOUND);
        availability.requireStartable(tenantId, source.getAgentId(), flowId);
        // All validation and snapshot creation precede AFTER_COMMIT stop/start effects.
        workitemService.rebindForInteractionRework(tenantId, workitemId, source.getAgentId(), flowId, entry.getId(), userId);
        var rebound = workitems.findById(workitemId);
        if (rebound.getScheduledStartAt() != null
                && workitems.clearScheduledStartAt(workitemId, tenantId, rebound.getVersion()) != 1) {
            throw new BizException(ErrorCode.WORKITEM_VERSION_CONFLICT);
        }
        restarts.restartAgentDelivery(workitemId, tenantId, source.getAgentId(), entry.getId(),
                token, null, userId, AssignmentActor.human(userId, null));
        return result(tenantId, workitemId, sourceId, restartStore.roundByToken(tenantId, workitemId, token));
    }

    private Map<String, Object> result(long tenantId, long workitemId, long sourceId, int round) {
        var snapshot = snapshots.restartIntent(tenantId, workitemId,
                DeliveryRestartStore.restartIdempotencyKey(workitemId, round));
        return Map.of("restartRound", round, "sourceDispatchId", sourceId,
                "sdlcId", snapshot.sdlcId(), "sdlcStepId", snapshot.stepId(), "status", restartStore.roundStatus(tenantId, workitemId, round));
    }
}
