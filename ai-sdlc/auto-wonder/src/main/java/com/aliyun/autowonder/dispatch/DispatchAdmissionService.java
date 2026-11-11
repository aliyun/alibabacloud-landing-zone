package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.sdlc.SdlcDO;
import com.aliyun.autowonder.sdlc.SdlcDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * Final dispatch admission: the linearization point between management state changes
 * (SDLC disable / agent offline) and a dispatch becoming eligible for delivery.
 *
 * <p>The short transaction locks the workitem control row (workitem sources only), then
 * the agent row, then the SDLC row, reads their current state and performs the
 * {@code PACKAGING -> DISPATCHED} optimistic CAS. Committing this transaction is the
 * authorization to send: a management change that commits first makes this transaction
 * reject the dispatch, while an admission that commits first keeps its authorization
 * even if the management request returns later.
 *
 * <p>Packaging and the network send stay outside this transaction, so no database lock
 * is held while talking to the executor.
 */
@Service
public class DispatchAdmissionService {

    private static final Logger log = LoggerFactory.getLogger(DispatchAdmissionService.class);

    public enum Outcome {
        /** CAS committed; the caller may send. */
        ADMITTED,
        /** Management state forbids the execution; the caller must fail the dispatch. */
        REJECTED,
        /** Another writer already moved the row; the caller must not send or fail it. */
        LOST
    }

    /** Admission outcome plus the stable failure reason for a management-state rejection. */
    public record Result(Outcome outcome, String failureReason) {
        public static Result admitted() {
            return new Result(Outcome.ADMITTED, null);
        }

        public static Result rejected(String failureReason) {
            return new Result(Outcome.REJECTED, failureReason);
        }

        public static Result lost() {
            return new Result(Outcome.LOST, null);
        }
    }

    private final DispatchDao dispatchDao;
    private final AgentDao agentDao;
    private final SdlcDao sdlcDao;
    private final TransactionTemplate tx;
    private DispatchRecoveryService recoveryService;

    @Autowired(required = false)
    public void setRecoveryService(DispatchRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Autowired
    public DispatchAdmissionService(DispatchDao dispatchDao, AgentDao agentDao, SdlcDao sdlcDao,
            PlatformTransactionManager transactionManager) {
        this(dispatchDao, agentDao, sdlcDao, transactionManager == null ? null : admissionTemplate(transactionManager));
    }

    private static TransactionTemplate admissionTemplate(PlatformTransactionManager transactionManager) {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        // The admission is its own commit boundary: joining a caller transaction would both
        // hide that caller's uncommitted rows from the CAS and delay the send authorization
        // until the caller commits, so its rollback could still have sent.
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /** Test seam: run without an ambient transaction. */
    DispatchAdmissionService(DispatchDao dispatchDao, AgentDao agentDao, SdlcDao sdlcDao) {
        this(dispatchDao, agentDao, sdlcDao, (TransactionTemplate) null);
    }

    private DispatchAdmissionService(DispatchDao dispatchDao, AgentDao agentDao, SdlcDao sdlcDao,
            TransactionTemplate tx) {
        this.dispatchDao = dispatchDao;
        this.agentDao = agentDao;
        this.sdlcDao = sdlcDao;
        this.tx = tx;
    }

    /**
     * @param expected     dispatch row as packaged by this worker; its version is the CAS owner
     * @param sdlcId       effective SDLC of the execution, or {@code null} for a no-flow execution
     * @param executorId   executor selected for this attempt
     * @param ossRef       task package reference already built outside this transaction
     */
    public Result admit(DispatchDO expected, Long sdlcId, long executorId, String ossRef) {
        if (expected == null || expected.getId() == null || expected.getTenantId() == null) {
            return Result.lost();
        }
        if (tx == null) {
            return admitInTransaction(expected, sdlcId, executorId, ossRef);
        }
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> admitInTransaction(expected, sdlcId, executorId, ossRef));
            } catch (ConcurrencyFailureException transientRace) {
                // Concurrent workers can deadlock while upgrading the shared workitem control
                // lock; MySQL rolls one transaction back. One retry observes the winner's
                // commit and the CAS then reports LOST, so only one worker may send.
                if (attempt >= 2) {
                    log.warn("dispatch admission gave up after a lock race dispatchId={}",
                            expected.getId(), transientRace);
                    return Result.lost();
                }
                log.info("dispatch admission retried after a lock race dispatchId={}", expected.getId());
            }
        }
    }

    private Result admitInTransaction(DispatchDO expected, Long sdlcId, long executorId, String ossRef) {
        // Lock order is fixed: workitem control row (workitem sources only) -> agent -> SDLC.
        // Management operations take the same rows, so whichever side commits first wins.
        if (recoveryService != null && expected.executionSourceType() == ExecutionSourceType.WORKITEM) {
            recoveryService.lockWorkitemControl(expected);
        }
        // The locking read returns the row itself: judging status from a separate consistent
        // read would reuse a snapshot pinned by the caller's earlier reads and miss the change.
        AgentDO agent = expected.getAgentId() == null || expected.getAgentId() <= 0 ? null
                : agentDao.findByIdForUpdate(expected.getTenantId(), expected.getAgentId());
        if (isOffline(agent, expected.getTenantId())) {
            return Result.rejected(DispatchFailureReason.AGENT_OFFLINE);
        }
        if (sdlcId != null && sdlcId > 0) {
            SdlcDO sdlc = sdlcDao.findByIdForUpdate(sdlcId, expected.getTenantId());
            if (isDisabled(sdlc, expected.getTenantId())) {
                return Result.rejected(DispatchFailureReason.SDLC_DISABLED);
            }
        }
        return transitionToDispatched(expected, executorId, ossRef);
    }

    private Result transitionToDispatched(DispatchDO expected, long executorId, String ossRef) {
        int changed = recoveryService != null
                // Terminal/projection side effects stay identical to the pre-existing transition.
                ? recoveryService.transition(expected, DispatchStatus.DISPATCHED,
                        null, executorId, ossRef, null, null)
                : dispatchDao.updateStatus(expected.getId(), expected.getTenantId(),
                        DispatchStatus.DISPATCHED, null, executorId, ossRef, null, null,
                        expected.getVersion(), 0L);
        if (changed != 1) {
            return Result.lost();
        }
        // The caller keeps using this object for the send and any send-failure CAS; it must
        // observe the committed version instead of the pre-admission PACKAGING row.
        expected.setStatus(DispatchStatus.DISPATCHED);
        expected.setVersion(expected.getVersion() + 1);
        return Result.admitted();
    }

    private static boolean isOffline(AgentDO agent, Long tenantId) {
        return agent != null
                && Objects.equals(tenantId, agent.getTenantId())
                && ExecutionAvailabilityService.AGENT_OFFLINE.equalsIgnoreCase(agent.getStatus());
    }

    private static boolean isDisabled(SdlcDO sdlc, Long tenantId) {
        return sdlc != null
                && Objects.equals(tenantId, sdlc.getTenantId())
                && ExecutionAvailabilityService.SDLC_DISABLED.equalsIgnoreCase(sdlc.getStatus());
    }
}