package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.workitem.WorkitemControlEventWriter;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Durable cancellation intent, dispatch fencing and repair of dependent projections. */
@Service
public class DispatchRecoveryService {
    private static final Logger log = LoggerFactory.getLogger(DispatchRecoveryService.class);
    @org.springframework.beans.factory.annotation.Value("${autowonder.dispatch.recovery.package-retries:3}")
    private int packageRetries = 3;
    @org.springframework.beans.factory.annotation.Value("${autowonder.dispatch.recovery.retry-delay-ms:30000}")
    private long retryDelayMs = 30_000L;
    /** A stop raised moments ago may still race the dispatch handshake; only older ones self-heal. */
    private static final long STOP_SELF_HEAL_GRACE_MS = 120_000L;
    /** Callers that need to react to a closed delivery match this key instead of the rendered text. */
    public static final String DELIVERY_CLOSED_MESSAGE_KEY = "dispatch.recovery.closed";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final DispatchDao dispatches;
    private final WorkitemDao workitems;
    private final DispatchControlTransport transport;
    private com.aliyun.autowonder.audit.AuditLogService audit;
    @org.springframework.beans.factory.annotation.Autowired
    public void setAudit(com.aliyun.autowonder.audit.AuditLogService value) { audit = value; }
    private ExecutorRegistry executorRegistry;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setExecutorRegistry(ExecutorRegistry value) { executorRegistry = value; }
    private DispatchRuntimeEventDao runtimeEvents;
    private DispatchCheckpointDao checkpoints;
    @org.springframework.beans.factory.annotation.Autowired
    public void setCompletionEvidence(DispatchRuntimeEventDao runtimeEvents, DispatchCheckpointDao checkpoints) {
        this.runtimeEvents = runtimeEvents;
        this.checkpoints = checkpoints;
    }
    private com.aliyun.autowonder.workitem.DeliveryRestartStore restartStore;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRestartStore(com.aliyun.autowonder.workitem.DeliveryRestartStore value) { restartStore = value; }
    // Resolve the callback only after a stop has committed. The orchestrator itself
    // depends on recovery (and DispatchService), so eager injection forms a bean cycle.
    private ObjectProvider<DeliveryRestartOrchestrator> restartOrchestrator;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setRestartOrchestrator(ObjectProvider<DeliveryRestartOrchestrator> value) { restartOrchestrator = value; }
    private WorkitemControlEventWriter controlEvents;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setControlEvents(WorkitemControlEventWriter value) { controlEvents = value; }
    private void audit(long tenantId, long subjectId, long userId, String action, String targetType) {
        if (audit == null) return;
        var record = new com.aliyun.autowonder.audit.AuditLogRecord();
        record.setTenantId(tenantId); record.setActorId(userId); record.setActorType(userId == 0 ? "SYSTEM" : "HUMAN");
        record.setModule("dispatch"); record.setAction(action); record.setTargetType(targetType); record.setTargetId(subjectId);
        record.setEventType(action); record.setTriggerType("RECOVERY");
        audit.recordRequired(record);
    }


    public DispatchRecoveryService(JdbcTemplate jdbc, org.springframework.transaction.PlatformTransactionManager tm,
            DispatchDao dispatches, WorkitemDao workitems, DispatchControlTransport transport) {
        this.jdbc = jdbc; this.tx = new TransactionTemplate(tm); this.dispatches = dispatches;
        this.workitems = workitems; this.transport = transport;
    }

    private void lockSubject(DispatchDO d) {
        if (d.executionSourceType() != ExecutionSourceType.WORKITEM) return;
        jdbc.update("INSERT IGNORE INTO workitem_execution_control (tenant_id,workitem_id) VALUES (?,?)",
                d.getTenantId(), d.getWorkitemId());
        jdbc.queryForObject("SELECT closed FROM workitem_execution_control WHERE tenant_id=? AND workitem_id=? FOR UPDATE",
                Integer.class, d.getTenantId(), d.getWorkitemId());
    }

    /** Control requests and async confirmations share this lock before touching control state. */
    public void lockWorkitemControl(DispatchDO d) {
        lockSubject(d);
    }

    public boolean closed(long tenantId, long workitemId) {
        return !jdbc.queryForList("SELECT 1 FROM workitem_execution_control WHERE tenant_id=? AND workitem_id=? AND closed=1",
                tenantId, workitemId).isEmpty();
    }

    public long controlEventId(long tenantId, long dispatchId) {
        List<Long> rows = jdbc.queryForList(
                "SELECT control_event_id FROM dispatch_recovery WHERE tenant_id=? AND dispatch_id=?",
                Long.class, tenantId, dispatchId);
        return rows.isEmpty() || rows.get(0) == null ? 0L : rows.get(0);
    }

    public void bindControlEvent(long tenantId, long dispatchId, long eventId) {
        jdbc.update("INSERT IGNORE INTO dispatch_recovery (tenant_id,dispatch_id) VALUES (?,?)", tenantId, dispatchId);
        jdbc.update("UPDATE dispatch_recovery SET control_event_id=?,gmt_modified=NOW() WHERE tenant_id=? AND dispatch_id=?",
                eventId, tenantId, dispatchId);
    }

    public Map<Long, ControlState> controlStates(long tenantId, Collection<Long> dispatchIds) {
        if (dispatchIds == null || dispatchIds.isEmpty()) return Map.of();
        List<Long> ids = dispatchIds.stream().filter(Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return Map.of();
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.addAll(ids);
        Map<Long, ControlState> result = new HashMap<>();
        jdbc.query("SELECT dispatch_id,cancel_requested,stop_pending,forced,control_event_id,phase,reason "
                        + "FROM dispatch_recovery WHERE tenant_id=? AND dispatch_id IN (" + placeholders + ")",
                rs -> {
                    result.put(rs.getLong("dispatch_id"), new ControlState(
                            rs.getInt("cancel_requested") == 1,
                            rs.getInt("stop_pending") == 1,
                            rs.getInt("forced") == 1,
                            rs.getLong("control_event_id"),
                            rs.getString("phase"),
                            rs.getString("reason")));
                }, args.toArray());
        return result;
    }

    public record ControlState(boolean cancelRequested, boolean stopPending, boolean forced,
                               long controlEventId, String phase, String reason) {
        public static ControlState none() {
            return new ControlState(false, false, false, 0L, null, null);
        }
    }

    public boolean canReopen(long tenantId, long workitemId) {
        return closed(tenantId, workitemId) && dispatches.listByWorkitem(tenantId, workitemId).stream()
                .allMatch(d -> DispatchStatus.isTerminal(d.getStatus()));
    }

    public void requireOpen(DispatchDO d) {
        if (d.executionSourceType() == ExecutionSourceType.WORKITEM && closed(d.getTenantId(), d.getWorkitemId()))
            throw BizException.keyed(ErrorCode.CONFLICT, DELIVERY_CLOSED_MESSAGE_KEY, new Object[0], "工单已关闭，请先重新打开交付");
    }

    public void insert(DispatchDO d) {
        insert(d, null);
    }

    public void insert(DispatchDO d, WorkitemControlEventWriter.ExecutionControlEvent creationEvent) {
        tx.executeWithoutResult(s -> {
            lockSubject(d); requireOpen(d); dispatches.insert(d);
            if (creationEvent != null && controlEvents != null) {
                controlEvents.recordExecution(d.getTenantId(), d.getWorkitemId(),
                        creationEvent.setDispatchId(d.getId()));
            }
            if ("CANONICAL_INTERACTION".equals(d.getResumeMode()) && d.getIdempotencyKey().startsWith("continue:")) {
                jdbc.update("INSERT INTO workitem_comment_delivery (tenant_id,workitem_id,comment_id,target_agent_id,dispatch_id,status,retry_dispatch_id) "
                        + "SELECT tenant_id,workitem_id,comment_id,target_agent_id,?,'QUEUED',? FROM workitem_comment_delivery "
                        + "WHERE tenant_id=? AND dispatch_id=? AND status IN ('FAILED','CANCELED') AND reply_comment_id IS NULL",
                        d.getId(), d.getId(), d.getTenantId(), d.getResumeFromDispatchId());
            }
        });
    }

    /** CAS and dependent comment projection commit together, including server-side failures. */
    public int transition(DispatchDO d, String status, Long versionId, Long executorId,
            String packageRef, String summary, String error) {
        return tx.execute(s -> {
            lockSubject(d);
            if (!DispatchStatus.isTerminal(status)) {
                requireOpen(d);
                if (cancelRequested(d.getTenantId(), d.getId())) return 0;
            }
            int changed = dispatches.updateStatus(d.getId(), d.getTenantId(), status, versionId, executorId,
                    packageRef, summary, error, d.getVersion(), 0L);
            if (changed == 1 && !DispatchStatus.isTerminal(status))
                jdbc.update("UPDATE dispatch_recovery SET phase=?,reason=NULL,next_retry_at=NULL,gmt_modified=NOW() WHERE tenant_id=? AND dispatch_id=? AND cancel_requested=0",
                        status, d.getTenantId(), d.getId());
            if (changed == 1 && DispatchStatus.isTerminal(status)) {
                projectTerminal(d, status, error);
                if (DispatchStatus.TIMEOUT.equals(status) && d.getExecutorId() != null) {
                    jdbc.update("INSERT INTO dispatch_recovery (tenant_id,dispatch_id,cancel_requested,stop_pending,forced,requested_at,reason) "
                            + "VALUES (?,?,1,1,1,NOW(),?) ON DUPLICATE KEY UPDATE cancel_requested=1,stop_pending=1,forced=1,requested_at=COALESCE(requested_at,NOW()),reason=VALUES(reason)",
                            d.getTenantId(), d.getId(), error);
                    recordTimeoutStopIntent(d, error);
                }
                audit(d.getTenantId(), d.getId(), 0, "DISPATCH_" + status, "DISPATCH");
            }
            return changed;
        });
    }

    private void projectTerminal(DispatchDO d, String status, String error) {
        if (DispatchStatus.SUCCEEDED.equals(status)) return; // ACK/reply may still be in flight.
        String guidanceStatus = DispatchStatus.CANCELED.equals(status) ? "CANCELED" : "FAILED";
        jdbc.update("UPDATE workitem_comment_delivery SET status=?,error=?,gmt_modified=NOW() "
                + "WHERE tenant_id=? AND dispatch_id=? AND status IN ('QUEUED','DELIVERED')",
                guidanceStatus, error == null ? status : error, d.getTenantId(), d.getId());
    }

    public boolean cancelRequested(long tenantId, long dispatchId) {
        return !jdbc.queryForList("SELECT 1 FROM dispatch_recovery WHERE tenant_id=? AND dispatch_id=? AND cancel_requested=1",
                tenantId, dispatchId).isEmpty();
    }

    /** Fencing must also cover authenticated late platform writes, not just TASK_RESULT. */
    public boolean fenced(DispatchDO d) {
        return d != null && (cancelRequested(d.getTenantId(), d.getId())
                || (d.executionSourceType() == ExecutionSourceType.WORKITEM && closed(d.getTenantId(), d.getWorkitemId())));
    }

    private DispatchDO requireWorkitem(long tenantId, long workitemId, long dispatchId) {
        DispatchDO d = dispatches.findById(dispatchId);
        if (d == null || !Objects.equals(d.getTenantId(), tenantId)
                || d.executionSourceType() != ExecutionSourceType.WORKITEM || !Objects.equals(d.getWorkitemId(), workitemId))
            throw new BizException(ErrorCode.DISPATCH_NOT_FOUND);
        return d;
    }

    public Map<String, Object> cancel(long tenantId, long workitemId, long dispatchId, long userId, boolean force) {
        DispatchDO d = requireWorkitem(tenantId, workitemId, dispatchId);
        tx.executeWithoutResult(s -> {
            lockSubject(d);
            CancelOutcome outcome = cancelLocked(dispatches.findById(dispatchId), userId, force);
            if (outcome.changed()) {
                String phase = force && outcome.stopPending() ? "UNCONFIRMED"
                        : (DispatchStatus.PAUSING.equals(outcome.targetStatus()) ? "REQUESTED" : "CONFIRMED");
                recordControlRequest(outcome.dispatch(), "CANCEL", phase, "USER", userId,
                        outcome.dispatch().getError(), outcome.stopPending(), force, null);
            }
        });
        sendStop(dispatches.findById(dispatchId));
        // A forced cancel may have been the last blocker of a waiting restart round.
        advanceWaitingRestarts(tenantId, workitemId);
        return state(tenantId, workitemId);
    }

    /** Scheduled runs use the same durable stop intent and runtime acknowledgement as workitems. */
    public void forceCancelScheduledRun(long tenantId, long runId, long dispatchId, long userId) {
        tx.executeWithoutResult(s -> {
            DispatchDO d = dispatches.findById(dispatchId);
            if (d == null || !Objects.equals(d.getTenantId(), tenantId)
                    || d.executionSourceType() != ExecutionSourceType.SCHEDULED_TASK_RUN
                    || !Objects.equals(d.getWorkitemId(), runId))
                throw new BizException(ErrorCode.DISPATCH_NOT_FOUND);
            cancelLocked(d, userId, true);
        });
        sendStop(dispatches.findById(dispatchId));
    }

    private CancelOutcome cancelLocked(DispatchDO d, long userId, boolean force) {
        String fromStatus = d.getStatus();
        if (DispatchStatus.isTerminal(d.getStatus())) {
            projectTerminal(d, d.getStatus(), d.getError());
            return new CancelOutcome(d, fromStatus, d.getStatus(), false, false, false, 0L);
        }
        if (!force && cancelRequested(d.getTenantId(), d.getId())) {
            return new CancelOutcome(d, fromStatus, d.getStatus(), true, false, false, controlEventId(d.getTenantId(), d.getId()));
        }
        boolean pendingStop = d.getExecutorId() != null && !Set.of("PENDING", "PACKAGING", "WAITING_FOR_PAUSE", "PAUSED").contains(d.getStatus());
        String target = pendingStop && !force ? DispatchStatus.PAUSING : DispatchStatus.CANCELED;
        String reason = pendingStop ? (force ? "CANCELED_STOP_UNCONFIRMED: 平台已结束，执行器停止未确认" : "CANCEL_REQUESTED: 正在取消") : "USER_CANCELED";
        if (dispatches.updateStatus(d.getId(), d.getTenantId(), target, null, null, null, null, reason, d.getVersion(), userId) != 1)
            throw BizException.keyed(ErrorCode.CONFLICT, "dispatch.recovery.cancel_changed", new Object[0], "执行状态已变化，请重试取消");
        jdbc.update("INSERT INTO dispatch_recovery (tenant_id,dispatch_id,cancel_requested,stop_pending,forced,requested_at,modifier_id,reason) "
                + "VALUES (?,?,1,?,?,NOW(),?,?) ON DUPLICATE KEY UPDATE cancel_requested=1,stop_pending=VALUES(stop_pending),"
                + "forced=GREATEST(forced,VALUES(forced)),requested_at=COALESCE(requested_at,NOW()),modifier_id=VALUES(modifier_id),reason=VALUES(reason),gmt_modified=NOW()",
                d.getTenantId(), d.getId(), pendingStop ? 1 : 0, force ? 1 : 0, userId, reason);
        // No new guidance may be delivered after a cancel request.
        projectTerminal(d, DispatchStatus.CANCELED, reason);
        audit(d.getTenantId(), d.getId(), userId, force ? "FORCE_CANCEL_DISPATCH" : "CANCEL_DISPATCH", "DISPATCH");
        d.setStatus(target);
        d.setError(reason);
        return new CancelOutcome(d, fromStatus, target, pendingStop, force, true, 0L);
    }

    private record CancelOutcome(DispatchDO dispatch, String fromStatus, String targetStatus,
                                 boolean stopPending, boolean forced, boolean changed, long controlEventId) {
    }

    /** Writes a new control intent and makes it the intent later confirmations refer to. */
    public long recordControlRequest(DispatchDO d, String action, String phase, String origin, Long requestedById,
                                     String reason, boolean stopPending, boolean forced, String mode) {
        if (controlEvents == null || d == null || d.executionSourceType() != ExecutionSourceType.WORKITEM) return 0L;
        long previous = controlEventId(d.getTenantId(), d.getId());
        WorkitemControlEventWriter.ExecutionControlEvent event = new WorkitemControlEventWriter.ExecutionControlEvent()
                .setAction(action)
                .setPhase(phase)
                .setOrigin(origin)
                .setDispatchId(d.getId())
                .setAgentId(d.getAgentId())
                .setRequestedById(requestedById == null ? 0L : requestedById)
                .setSourceDispatchId(d.getId())
                .setSupersedesRequestEventId(forced || previous <= 0 ? null : previous)
                .setRequestEventId(forced && previous > 0 ? previous : null)
                .setParentRequestEventId(forced && previous > 0 ? previous : null)
                .setStopPending(stopPending)
                .setForced(forced)
                .setReason(reason)
                .setMode(mode)
                .setActorType(requestedById == null || requestedById == 0L ? "SYSTEM" : "HUMAN")
                .setActorRef(requestedById == null ? 0L : requestedById);
        long eventId = controlEvents.recordExecution(d.getTenantId(), d.getWorkitemId(), event);
        bindControlEvent(d.getTenantId(), d.getId(), eventId);
        return eventId;
    }

    /** Writes an async result and links it to the intent that was active in this transaction. */
    public long recordControlOutcome(DispatchDO d, String action, String phase, String origin, Long requestedById,
                                     String reason, boolean stopPending, boolean forced) {
        if (controlEvents == null || d == null || d.executionSourceType() != ExecutionSourceType.WORKITEM) return 0L;
        long pointer = controlEventId(d.getTenantId(), d.getId());
        WorkitemControlEventWriter.ControlIntent intent = pointer > 0
                ? controlEvents.findIntent(d.getTenantId(), pointer).orElse(null) : null;
        WorkitemControlEventWriter.ExecutionControlEvent event = new WorkitemControlEventWriter.ExecutionControlEvent()
                .setAction(action)
                .setPhase(phase)
                .setOrigin(origin)
                .setDispatchId(d.getId())
                .setAgentId(d.getAgentId())
                .setAgentName(intent == null ? null : intent.agentName())
                .setRequestedById(requestedById == null ? 0L : requestedById)
                .setRequestedByName(intent == null ? null : intent.requestedByName())
                .setSourceDispatchId(d.getId())
                .setRequestEventId(pointer > 0 ? pointer : null)
                .setStopPending(stopPending)
                .setForced(forced)
                .setReason(reason)
                .setActorType("SYSTEM")
                .setActorRef(0L);
        return controlEvents.recordExecution(d.getTenantId(), d.getWorkitemId(), event);
    }

    public long recordResumeOutcome(DispatchDO source, DispatchDO created, long userId, String mode) {
        if (controlEvents == null || source == null || created == null
                || source.executionSourceType() != ExecutionSourceType.WORKITEM) return 0L;
        if (!"RESUME".equals(mode) && !"RETRY".equals(mode)) mode = "RETRY";
        WorkitemControlEventWriter.ExecutionControlEvent event = new WorkitemControlEventWriter.ExecutionControlEvent()
                .setAction("RESUME")
                .setPhase("CONFIRMED")
                .setOrigin("USER")
                .setDispatchId(source.getId())
                .setAgentId(source.getAgentId())
                .setRequestedById(userId)
                .setSourceDispatchId(source.getId())
                .setTargetDispatchId(created.getId())
                .setMode(mode)
                .setStopPending(false)
                .setForced(false)
                .setActorType("HUMAN")
                .setActorRef(userId);
        return controlEvents.recordExecution(source.getTenantId(), source.getWorkitemId(), event);
    }

    public long recordPauseOutcome(DispatchDO d, String phase, String reason) {
        if (controlEvents == null || d == null || d.executionSourceType() != ExecutionSourceType.WORKITEM) return 0L;
        long pointer = controlEventId(d.getTenantId(), d.getId());
        WorkitemControlEventWriter.ExecutionControlEvent intent = pointer > 0
                ? controlEvents.findExecution(d.getTenantId(), pointer).orElse(null) : null;
        return recordControlOutcome(d, "PAUSE", phase,
                intent == null ? "LEGACY" : intent.getOrigin(),
                intent == null ? 0L : intent.getRequestedById(),
                reason, false, false);
    }

    private void recordTimeoutStopIntent(DispatchDO d, String reason) {
        // The recovery.forced flag is also the existing system-timeout stop marker; the event
        // keeps origin=TIMEOUT so it is never presented as a user force-end.
        recordControlRequest(d, "STOP", "REQUESTED", "TIMEOUT", 0L, reason, true, false, null);
    }

    /** Persistent intent is the outbox: a failed WebSocket send is retried by sweep. */
    private void sendStop(DispatchDO d) {
        if (d == null || d.getExecutorId() == null || !cancelRequested(d.getTenantId(), d.getId())) return;
        if (jdbc.update("UPDATE dispatch_recovery SET last_sent_at=NOW() WHERE tenant_id=? AND dispatch_id=? AND stop_pending=1 "
                + "AND (last_sent_at IS NULL OR last_sent_at < ?)", d.getTenantId(), d.getId(), new Timestamp(System.currentTimeMillis() - 30_000L)) == 0) return;
        try { transport.pause(d); }
        catch (RuntimeException e) { log.warn("cancel control delivery deferred dispatchId={}", d.getId(), e); }
    }

    /** Both existing TASK_PAUSED and TASK_RESULT prove that this owned execution stopped. */
    public boolean onStopped(long tenantId, long executorId, long dispatchId) {
        long[] stoppedWorkitem = new long[1];
        boolean stopped = tx.execute(s -> {
            DispatchDO d = dispatches.findById(dispatchId);
            if (d == null || !Objects.equals(d.getTenantId(), tenantId) || !Objects.equals(d.getExecutorId(), executorId)) return false;
            lockSubject(d);
            if (!cancelRequested(tenantId, dispatchId)) return false;
            d = dispatches.findById(dispatchId);
            String terminalStatus = d.getStatus();
            boolean timeout = DispatchStatus.TIMEOUT.equals(terminalStatus);
            if ((!DispatchStatus.isTerminal(d.getStatus()) || (DispatchStatus.CANCELED.equals(d.getStatus()) && !"USER_CANCELED".equals(d.getError()))) && dispatches.updateStatus(dispatchId, tenantId, DispatchStatus.CANCELED,
                    null, null, null, null, "USER_CANCELED", d.getVersion(), 0L) != 1) return false;
            int cleared = jdbc.update("UPDATE dispatch_recovery SET stop_pending=0"
                    + (timeout ? "" : ",reason='USER_CANCELED'")
                    + ",gmt_modified=NOW() WHERE tenant_id=? AND dispatch_id=? AND stop_pending=1", tenantId, dispatchId);
            if (DispatchStatus.isTerminal(d.getStatus())) {
                projectTerminal(d, d.getStatus(), d.getError());
            } else {
                projectTerminal(d, DispatchStatus.CANCELED, "USER_CANCELED");
            }
            if (cleared == 1) {
                long pointer = d.executionSourceType() == ExecutionSourceType.WORKITEM ? controlEventId(tenantId, dispatchId) : 0L;
                WorkitemControlEventWriter.ControlIntent intent = pointer > 0 && controlEvents != null
                        ? controlEvents.findIntent(tenantId, pointer).orElse(null) : null;
                String action = intent == null || "PAUSE".equals(intent.action())
                        || "CLOSE".equals(intent.action()) || "REOPEN".equals(intent.action())
                        ? "STOP" : intent.action();
                String origin = intent == null ? "LEGACY" : intent.origin();
                Long requestedBy = intent == null ? 0L : intent.requestedById();
                boolean forced = intent != null && intent.forced();
                if (timeout) action = "STOP";
                if (DispatchStatus.TIMEOUT.equals(terminalStatus) && intent == null) origin = "TIMEOUT";
                recordControlOutcome(d, action, "CONFIRMED", origin, requestedBy,
                        timeout ? d.getError() : "USER_CANCELED", false, forced);
            }
            stoppedWorkitem[0] = d.getWorkitemId() == null ? 0L : d.getWorkitemId();
            return true;
        });
        if (stopped && stoppedWorkitem[0] != 0L) {
            // The confirmed stop may have been the last blocker of a waiting restart round.
            advanceWaitingRestarts(tenantId, stoppedWorkitem[0]);
        }
        return stopped;
    }

    /**
     * Whether a WAITING_FOR_PAUSE rework may be released for this predecessor.
     *
     * <p>A database terminal status is not stop evidence by itself: a late result can
     * terminalize a PAUSING row (RESULT_STATE_CONFLICT), and a forced cancel/timeout can
     * leave the executor quarantine armed. Only an explicit pause fence, or a terminal
     * result that no current authoritative executor session still owns, releases the
     * barrier. Missing, stale or incomplete inventory keeps the waiter blocked.
     */
    public boolean canReleasePauseWaiter(DispatchDO predecessor) {
        if (predecessor == null || predecessor.getId() == null) return false;
        String status = predecessor.getStatus();
        if (DispatchStatus.PAUSED.equals(status)) return true;
        if (DispatchStatus.CANCELED.equals(status) && predecessor.getError() != null
                && predecessor.getError().startsWith(DispatchFailureReason.PAUSE_CONFIRMATION_MISSING + ":")) {
            return true;
        }
        // A user/system cancel or timeout keeps its own terminal semantics; only a
        // normal runtime result can close a pause barrier without a pause receipt.
        if (!DispatchStatus.SUCCEEDED.equals(status) && !DispatchStatus.FAILED.equals(status)) return false;
        try {
            ControlState state = controlStates(predecessor.getTenantId(), List.of(predecessor.getId()))
                    .getOrDefault(predecessor.getId(), ControlState.none());
            if (state.cancelRequested() || state.stopPending()) return false;
            if (predecessor.getExecutorId() == null) return true;
            if (executorRegistry == null) return false;
            return executorRegistry.currentDispatchSnapshot(predecessor.getExecutorId())
                    .filter(snapshot -> snapshot.authoritativeInventory() && snapshot.inventoryReady()
                            && snapshot.inventoryError() == null)
                    .map(snapshot -> !snapshot.ownedDispatchIds().contains(predecessor.getId())
                            && !snapshot.runningDispatchIds().contains(predecessor.getId()))
                    .orElse(false);
        } catch (RuntimeException unavailable) {
            log.warn("pause waiter release facts unavailable dispatchId={}", predecessor.getId(), unavailable);
            return false;
        }
    }

    /** True when a newer delivery-restart round was allocated after the waiting rework was queued. */
    public boolean restartAfter(long tenantId, long workitemId, java.util.Date since) {
        if (restartStore == null || since == null) return false;
        // A failed lookup aborts the release transaction instead of looking like a restart.
        return restartStore.createdAfter(tenantId, workitemId, since);
    }

    /** Fences a PAUSE_FAILED dispatch whose executor inventory proved the task was released. */
    public boolean fencePauseFailed(DispatchDO current, String reason) {
        if (current == null) return false;
        return tx.execute(s -> {
            lockSubject(current);
            DispatchDO d = dispatches.findById(current.getId());
            if (d == null || !DispatchStatus.PAUSE_FAILED.equals(d.getStatus())) return false;
            if (!DispatchStatus.isTerminal(d.getStatus())
                    && dispatches.updateStatus(d.getId(), d.getTenantId(), DispatchStatus.CANCELED,
                    null, null, null, null, reason, d.getVersion(), 0L) != 1) return false;
            projectTerminal(d, DispatchStatus.CANCELED, reason);
            recordControlOutcome(d, "STOP", "CONFIRMED", "SYSTEM_FENCE", null, reason, false, false);
            return true;
        });
    }

    /** Resumes restart rounds that were waiting for this workitem's old executions to stop. */
    private void advanceWaitingRestarts(long tenantId, long workitemId) {
        if (restartOrchestrator == null) {
            return;
        }
        try {
            DeliveryRestartOrchestrator orchestrator = restartOrchestrator.getIfAvailable();
            if (orchestrator != null) {
                orchestrator.advanceWaitingRestarts(tenantId, workitemId);
            }
        } catch (RuntimeException e) {
            log.warn("delivery restart advancement after stop failed workitemId={}", workitemId, e);
        }
    }

    /**
     * Durable stop intent raised by a delivery restart. Same protocol as a user cancel
     * (cancel_requested + stop_pending, delivered through the existing stop sweep) so
     * late results of the old round stay fenced; the executor isolation guard stays
     * armed because stop_pending is never cleared here.
     */
    public void requestRestartStop(long tenantId, long workitemId, long dispatchId, long userId) {
        DispatchDO d = requireWorkitem(tenantId, workitemId, dispatchId);
        tx.executeWithoutResult(s -> {
            lockSubject(d);
            CancelOutcome outcome = cancelLocked(dispatches.findById(dispatchId), userId, false);
            if (outcome.changed()) {
                recordControlRequest(outcome.dispatch(), "STOP",
                        DispatchStatus.PAUSING.equals(outcome.targetStatus()) ? "REQUESTED" : "CONFIRMED",
                        "RESTART", userId, outcome.dispatch().getError(), outcome.stopPending(), false, null);
            }
        });
        sendStop(dispatches.findById(dispatchId));
    }

    public Map<String, Object> close(long tenantId, long workitemId, long userId, boolean force) {
        validateWorkitem(tenantId, workitemId);
        tx.executeWithoutResult(s -> {
            DispatchDO subject = new DispatchDO(); subject.setTenantId(tenantId); subject.setWorkitemId(workitemId);
            subject.setSourceType("WORKITEM"); lockSubject(subject);
            int closedRows = jdbc.update("UPDATE workitem_execution_control SET closed=1,modifier_id=?,gmt_modified=NOW() "
                    + "WHERE tenant_id=? AND workitem_id=? AND closed=0", userId, tenantId, workitemId);
            WorkitemControlEventWriter.DeliveryControlEvent event = new WorkitemControlEventWriter.DeliveryControlEvent()
                    .setAction("CLOSE").setRequestedById(userId).setActorType("HUMAN").setActorRef(userId);
            for (DispatchDO d : dispatches.listByWorkitem(tenantId, workitemId)) {
                CancelOutcome outcome = cancelLocked(d, userId, force);
                if (outcome.changed()) {
                    event.addAffected(new WorkitemControlEventWriter.AffectedDispatch(
                            outcome.dispatch().getId(), outcome.fromStatus(), outcome.targetStatus(), outcome.stopPending()));
                }
            }
            if (closedRows == 1 || !event.getAffectedDispatches().isEmpty()) {
                long eventId = controlEvents == null ? 0L : controlEvents.recordDelivery(tenantId, workitemId, event);
                if (eventId > 0) {
                    for (WorkitemControlEventWriter.AffectedDispatch affected : event.getAffectedDispatches()) {
                        bindControlEvent(tenantId, affected.dispatchId(), eventId);
                    }
                }
            }
            audit(tenantId, workitemId, userId, "CLOSE_DELIVERY", "WORKITEM");
        });
        for (DispatchDO d : dispatches.listByWorkitem(tenantId, workitemId)) sendStop(d);
        return state(tenantId, workitemId);
    }

    public Map<String, Object> reopen(long tenantId, long workitemId, long userId) {
        validateWorkitem(tenantId, workitemId);
        tx.executeWithoutResult(s -> {
            DispatchDO subject = new DispatchDO(); subject.setTenantId(tenantId); subject.setWorkitemId(workitemId); subject.setSourceType("WORKITEM"); lockSubject(subject);
            if (dispatches.listByWorkitem(tenantId, workitemId).stream().anyMatch(d -> !DispatchStatus.isTerminal(d.getStatus())))
                throw BizException.keyed(ErrorCode.CONFLICT, "dispatch.recovery.cancel_pending", new Object[0], "请先结束未完成的取消操作");
            int rows = jdbc.update("UPDATE workitem_execution_control SET closed=0,modifier_id=?,gmt_modified=NOW() "
                    + "WHERE tenant_id=? AND workitem_id=? AND closed=1", userId, tenantId, workitemId);
            if (rows == 1 && controlEvents != null) {
                controlEvents.recordDelivery(tenantId, workitemId, new WorkitemControlEventWriter.DeliveryControlEvent()
                        .setAction("REOPEN").setRequestedById(userId).setActorType("HUMAN").setActorRef(userId));
            }
        });
        audit(tenantId, workitemId, userId, "REOPEN_DELIVERY", "WORKITEM");
        return state(tenantId, workitemId);
    }

    private void validateWorkitem(long tenantId, long workitemId) {
        var w = workitems.findById(workitemId);
        if (w == null || !Objects.equals(w.getTenantId(), tenantId)) throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
    }

    /** One read for all queued dispatches in a progress response; historical reasons are not current status. */
    public Map<Long, String> waitingReasons(long tenantId, List<Long> dispatchIds) {
        if (dispatchIds == null || dispatchIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(dispatchIds.size(), "?"));
        java.util.List<Object> args = new java.util.ArrayList<>(); args.add(tenantId); args.addAll(dispatchIds);
        Map<Long, String> reasons = new java.util.HashMap<>();
        jdbc.query("SELECT dispatch_id,reason FROM dispatch_recovery WHERE tenant_id=? AND phase='PENDING' AND dispatch_id IN ("
                + placeholders + ")", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            String reason = rs.getString("reason");
            if (reason != null) reasons.put(rs.getLong("dispatch_id"), reason);
        }, args.toArray());
        return reasons;
    }

    public Map<String, Object> state(long tenantId, long workitemId) {
        validateWorkitem(tenantId, workitemId);
        var executions = dispatches.listByWorkitem(tenantId, workitemId).stream().map(d -> {
            var details = jdbc.queryForList("SELECT cancel_requested,stop_pending,forced,retry_count,next_retry_at,phase,reason,requested_at,control_event_id FROM dispatch_recovery WHERE tenant_id=? AND dispatch_id=?", tenantId, d.getId());
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("dispatchId", d.getId()); row.put("status", d.getStatus()); row.put("error", d.getError());
            row.put("agentId", d.getAgentId()); row.put("attempt", d.getAttempt()); row.put("updatedAt", d.getGmtModified());
            row.put("resumeMode", d.getResumeMode());
            var detail = details.isEmpty() ? new java.util.LinkedHashMap<String,Object>() : details.get(0);
            detail.put("max_retries", packageRetries);
            row.put("recovery", detail);
            row.put("phase", d.getStatus());
            String reason = d.getError() != null ? d.getError() : (String) detail.get("reason");
            row.put("errorCode", reason == null ? null : reason.split(":", 2)[0]);
            row.put("retryable", Set.of("FAILED", "TIMEOUT", "CANCELED", "PAUSED").contains(d.getStatus()));
            long controlEventId = detail.get("control_event_id") instanceof Number number ? number.longValue() : 0L;
            if (controlEventId > 0 && controlEvents != null) {
                controlEvents.findIntent(tenantId, controlEventId).ifPresent(intent -> {
                    Map<String, Object> control = new java.util.LinkedHashMap<>();
                    control.put("action", intent.action());
                    control.put("phase", intent.phase());
                    control.put("origin", intent.origin());
                    control.put("requestedById", intent.requestedById());
                    control.put("requestedByName", intent.requestedByName());
                    control.put("stopPending", intent.stopPending());
                    control.put("forced", intent.forced());
                    row.put("control", control);
                });
            }
            return row;
        }).toList();
        var restarts = restartStore == null ? List.<Map<String, Object>>of()
                : restartStore.listRounds(tenantId, workitemId, 10);
        boolean closed = closed(tenantId, workitemId);
        boolean allTerminal = dispatches.listByWorkitem(tenantId, workitemId).stream()
                .allMatch(d -> DispatchStatus.isTerminal(d.getStatus()));
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("closed", closed);
        result.put("executions", executions);
        result.put("restarts", restarts);
        result.put("canReopen", closed && allTerminal);
        result.put("reopenReasonKey", allTerminal ? null : "dispatch.recovery.cancel_pending");
        return result;
    }

    public boolean ready(DispatchDO d) {
        return jdbc.queryForList("SELECT 1 FROM dispatch_recovery WHERE tenant_id=? AND dispatch_id=? AND next_retry_at>NOW()", d.getTenantId(), d.getId()).isEmpty();
    }

    public void waiting(DispatchDO d, String reason) {
        waiting(d, reason, 0L);
    }

    public void waiting(DispatchDO d, String reason, long retryDelayMillis) {
        jdbc.update("INSERT INTO dispatch_recovery (tenant_id,dispatch_id,phase,reason,next_retry_at) VALUES (?,?,'PENDING',?,?) "
                + "ON DUPLICATE KEY UPDATE phase='PENDING',reason=IF(cancel_requested=1,reason,VALUES(reason)),"
                + "next_retry_at=IF(cancel_requested=1,next_retry_at,VALUES(next_retry_at))", d.getTenantId(), d.getId(), reason,
                retryDelayMillis > 0 ? new Timestamp(System.currentTimeMillis() + retryDelayMillis) : null);
    }

    /** A fresh complete heartbeat is stronger than a stale TASK_BUSY backoff. */
    public void wakeCapacityWaits(long agentId) {
        jdbc.update("UPDATE dispatch_recovery r JOIN dispatch d ON d.tenant_id=r.tenant_id AND d.id=r.dispatch_id "
                + "SET r.next_retry_at=NULL,r.gmt_modified=NOW() WHERE d.agent_id=? AND d.status='PENDING' "
                + "AND r.cancel_requested=0 AND r.reason='EXECUTOR_AT_CAPACITY'", agentId);
    }

    /** Only pre-delivery packaging is automatically retried; never duplicate unknown runtime work. */
    public boolean retryPackaging(DispatchDO d, String reason) {
        return tx.execute(s -> {
            lockSubject(d);
            DispatchDO current = dispatches.findById(d.getId());
            if (current == null || !DispatchStatus.PACKAGING.equals(current.getStatus()) || !Objects.equals(current.getVersion(), d.getVersion()) || fenced(current)) return false;
            jdbc.update("INSERT IGNORE INTO dispatch_recovery (tenant_id,dispatch_id) VALUES (?,?)", d.getTenantId(), d.getId());
            int count = jdbc.queryForObject("SELECT retry_count FROM dispatch_recovery WHERE tenant_id=? AND dispatch_id=? FOR UPDATE", Integer.class, d.getTenantId(), d.getId());
            if (count >= Math.max(0, packageRetries)) return false;
            jdbc.update("UPDATE dispatch_recovery SET retry_count=retry_count+1,phase='PACKAGING',reason=?,next_retry_at=?,gmt_modified=NOW() WHERE tenant_id=? AND dispatch_id=?",
                    reason == null ? "TASK_PACKAGE_TRANSIENT_ERROR" : reason.substring(0, Math.min(512, reason.length())),
                    new Timestamp(System.currentTimeMillis() + (Math.max(1000, retryDelayMs) * (1L << Math.min(count, 8)))), d.getTenantId(), d.getId());
            return dispatches.returnPackagingToPending(d.getId(), d.getTenantId(), d.getVersion(), 0L) == 1;
        });
    }

    /** Bounded repair queries use the same terminal projection as the live transition. */
    public void reconcile() {
        var rows = jdbc.queryForList("SELECT DISTINCT d.id FROM workitem_comment_delivery g JOIN dispatch d ON d.id=g.dispatch_id AND d.tenant_id=g.tenant_id "
                + "WHERE g.status IN ('QUEUED','DELIVERED') AND d.status IN ('FAILED','TIMEOUT','CANCELED') ORDER BY d.id LIMIT 200");
        for (var row : rows) {
            DispatchDO d = dispatches.findById(((Number) row.get("id")).longValue());
            if (d != null) projectTerminal(d, d.getStatus(), d.getError());
        }
        var success = jdbc.queryForList("SELECT g.id,g.dispatch_id FROM workitem_comment_delivery g JOIN dispatch d ON d.id=g.dispatch_id AND d.tenant_id=g.tenant_id "
                + "WHERE g.status IN ('QUEUED','DELIVERED') AND d.status='SUCCEEDED' AND d.gmt_modified<? ORDER BY g.id LIMIT 200",
                new Timestamp(System.currentTimeMillis() - 120_000L));
        for (var row : success) jdbc.update("UPDATE workitem_comment_delivery SET status=CASE WHEN reply_comment_id IS NULL THEN 'FAILED' ELSE 'APPLIED' END,"
                + "error=CASE WHEN reply_comment_id IS NULL THEN 'REPLY_ACK_MISSING: 执行已结束，回复确认缺失' ELSE NULL END,gmt_modified=NOW() "
                + "WHERE id=? AND dispatch_id=? AND status IN ('QUEUED','DELIVERED')", row.get("id"), row.get("dispatch_id"));
        var orphan = jdbc.queryForList("SELECT g.id FROM workitem_comment_delivery g LEFT JOIN dispatch d ON d.id=g.dispatch_id AND d.tenant_id=g.tenant_id "
                + "WHERE g.status IN ('QUEUED','DELIVERED') AND d.id IS NULL AND g.gmt_modified<? ORDER BY g.id LIMIT 200",
                new Timestamp(System.currentTimeMillis() - 30 * 60_000L));
        for (var row : orphan) jdbc.update("UPDATE workitem_comment_delivery SET status='FAILED',error='INTERACTION_DISPATCH_MISSING: 交互未关联有效执行，请重新发起',gmt_modified=NOW() "
                + "WHERE id=? AND status IN ('QUEUED','DELIVERED') AND gmt_modified<? AND NOT EXISTS (SELECT 1 FROM dispatch d WHERE d.id=workitem_comment_delivery.dispatch_id AND d.tenant_id=workitem_comment_delivery.tenant_id)", row.get("id"), new Timestamp(System.currentTimeMillis() - 30 * 60_000L));

        reconcileStops(null);
    }

    /** Called after the workitem lock: use a current read even if handoff began before recovery committed. */
    public boolean isHandoffSourceFenced(DispatchDO source) {
        return tx.execute(s -> {
            lockSubject(source);
            var statuses = jdbc.query("SELECT status FROM dispatch WHERE id=? AND tenant_id=? FOR UPDATE",
                    (rs, index) -> rs.getString("status"), source.getId(), source.getTenantId());
            return statuses.isEmpty() || !Set.of(DispatchStatus.DISPATCHED, DispatchStatus.ACKED,
                    DispatchStatus.RUNNING, DispatchStatus.SUCCEEDED).contains(statuses.get(0));
        });
    }

    /**
     * Release an inactive delivery that already handed off, or recover its unfinished step.
     * A recovery claim only touches gmt_modified, so lock the row before testing its age; a
     * version-only CAS would otherwise race a successful runtime claim.
     */
    public DispatchDO recoverAbandonedExecution(DispatchDO candidate, String expectedSessionId) {
        if (executorRegistry == null || candidate == null || candidate.getExecutorId() == null
                || candidate.executionSourceType() != ExecutionSourceType.WORKITEM) return null;
        return tx.execute(s -> {
            // Handoff takes this same lock before updating ownership and enqueuing its child.
            // Keep workitem -> execution_control -> dispatch ordering in both paths.
            var workitem = workitems.findByIdForUpdate(candidate.getWorkitemId(), candidate.getTenantId());
            if (workitem == null) return null;
            lockSubject(candidate);
            var modified = jdbc.query("SELECT gmt_modified FROM dispatch WHERE id=? AND tenant_id=? FOR UPDATE",
                    (rs, index) -> rs.getTimestamp("gmt_modified"), candidate.getId(), candidate.getTenantId());
            if (modified.isEmpty() || modified.get(0) == null) return null;
            DispatchDO current = dispatches.findById(candidate.getId());
            if (current == null || !Set.of(DispatchStatus.ACKED, DispatchStatus.RUNNING).contains(current.getStatus())
                    || !Objects.equals(current.getExecutorId(), candidate.getExecutorId()) || fenced(current)) return null;
            var snapshot = executorRegistry.currentDispatchSnapshot(current.getExecutorId())
                    .filter(value -> value.authoritativeInventory() && value.inventoryReady() && value.inventoryError() == null)
                    .filter(value -> Objects.equals(expectedSessionId, value.sessionId()))
                    .filter(value -> !value.ownedDispatchIds().contains(current.getId()))
                    .filter(value -> !value.runningDispatchIds().contains(current.getId()))
                    .orElse(null);
            // Require evidence reported after the claim/dispatch grace, not a cached pre-claim inventory.
            if (snapshot == null || modified.get(0).getTime() > snapshot.reportedAt() - STOP_SELF_HEAL_GRACE_MS) return null;
            // Never restart an older worker step after a newer formal execution has taken over.
            var interactionModes = Set.of("COMMENT_INTERACTION", "SIDE_INTERACTION", "CANONICAL_INTERACTION");
            boolean interaction = interactionModes.contains(current.getResumeMode() == null ? "" : current.getResumeMode());
            boolean waitingForHuman = !interaction && "HUMAN".equalsIgnoreCase(workitem.getAssigneeType());
            boolean handedOff = dispatches.findByIdempotencyKey(current.getTenantId(), "handoff:" + current.getId()) != null;
            boolean superseded = dispatches.listByWorkitem(current.getTenantId(), current.getWorkitemId()).stream()
                    .filter(row -> Objects.equals(row.getAgentId(), current.getAgentId()) && row.getId() > current.getId())
                    .anyMatch(row -> interaction
                            || !interactionModes.contains(row.getResumeMode() == null ? "" : row.getResumeMode())
                            || !DispatchStatus.isTerminal(row.getStatus()));
            String idem = "continue:" + current.getId();
            boolean continued = dispatches.findByIdempotencyKey(current.getTenantId(), idem) != null;
            boolean replied = interaction && interactionRepliesApplied(current);
            // Not restarting an old delivery must not leave its database capacity lease alive.
            // A reply/handoff plus a durable terminal checkpoint proves completion. Otherwise
            // retain the unfinished execution as PAUSED, without inventing success or canceling
            // the business request. Never re-drive SDLC or overwrite its human/successor owner.
            if (waitingForHuman || handedOff || superseded || continued || replied) {
                boolean completed = (waitingForHuman || handedOff || replied) && hasCompletionEvidence(current);
                String status = completed ? DispatchStatus.SUCCEEDED : DispatchStatus.PAUSED;
                if (dispatches.updateStatus(current.getId(), current.getTenantId(), status,
                        null, null, null, null, completed ? "" : null, current.getVersion(), 0L) == 1) {
                    audit(current.getTenantId(), current.getId(), 0, "DISPATCH_" + status, "DISPATCH");
                    log.info("released inactive dispatch without restarting delivery dispatchId={} status={} "
                                    + "waitingForHuman={} handedOff={} superseded={} continued={} replied={}",
                            current.getId(), status, waitingForHuman, handedOff, superseded, continued, replied);
                }
                return null;
            }
            String holdReason = hasCompletionEvidence(current) ? "COMPLETION_DELIVERY_UNCONFIRMED"
                    : inventoryRecoveryBudgetExhausted(current) ? "RUNTIME_RECOVERY_LIMIT_EXCEEDED" : null;
            if (holdReason != null) {
                if (dispatches.updateStatus(current.getId(), current.getTenantId(), DispatchStatus.PAUSED,
                        null, null, null, null, holdReason, current.getVersion(), 0L) == 1) {
                    audit(current.getTenantId(), current.getId(), 0, "DISPATCH_PAUSED", "DISPATCH");
                    log.warn("automatic recovery held dispatchId={} reason={}", current.getId(), holdReason);
                }
                return null;
            }
            DispatchDO next = new DispatchDO();
            next.setTenantId(current.getTenantId());
            next.setSourceType(ExecutionSourceType.WORKITEM.name());
            next.setWorkitemId(current.getWorkitemId());
            next.setSdlcStepId(current.getSdlcStepId());
            next.setAgentId(current.getAgentId());
            next.setAgentVersionId(current.getAgentVersionId());
            next.setStatus(DispatchStatus.PENDING);
            Integer maxAttempt = dispatches.findMaxAttempt(current.getTenantId(), current.getWorkitemId(), current.getSdlcStepId());
            next.setAttempt(Math.max(maxAttempt == null ? 0 : maxAttempt,
                    current.getAttempt() == null ? 0 : current.getAttempt()) + 1);
            next.setIdempotencyKey(idem);
            next.setResumeFromDispatchId(current.getId());
            next.setResumeMode(interaction ? "CANONICAL_INTERACTION" : "RECOVERY");
            next.setCreatorId(0L); next.setModifierId(0L); next.setVersion(0);
            String reason = "RUNTIME_INVENTORY_LOST";
            if (dispatches.updateStatus(current.getId(), current.getTenantId(), DispatchStatus.CANCELED,
                    null, null, null, null, reason, current.getVersion(), 0L) != 1) return null;
            projectTerminal(current, DispatchStatus.CANCELED, reason);
            insert(next);
            log.info("abandoned runtime execution recovered dispatchId={} recoveryDispatchId={} executorId={} stepId={}",
                    current.getId(), next.getId(), current.getExecutorId(), current.getSdlcStepId());
            return next;
        });
    }

    /** Count only this automatic inventory-loss chain, never the workitem's global attempt. */
    private boolean inventoryRecoveryBudgetExhausted(DispatchDO current) {
        DispatchDO child = current;
        for (int count = 0; count < 3; count++) {
            Long parentId = child.getResumeFromDispatchId();
            if (parentId == null || !Objects.equals(child.getCreatorId(), 0L)
                    || !Objects.equals(child.getIdempotencyKey(), "continue:" + parentId)) return false;
            DispatchDO parent = dispatches.findById(parentId);
            if (parent == null || !Objects.equals(parent.getTenantId(), current.getTenantId())
                    || !Objects.equals(parent.getWorkitemId(), current.getWorkitemId())
                    || !Objects.equals(parent.getSdlcStepId(), current.getSdlcStepId())
                    || !Objects.equals(parent.getAgentId(), current.getAgentId())
                    || !DispatchStatus.CANCELED.equals(parent.getStatus())
                    || !"RUNTIME_INVENTORY_LOST".equals(parent.getError())) return false;
            child = parent;
        }
        return true;
    }

    private boolean interactionRepliesApplied(DispatchDO dispatch) {
        var replies = jdbc.queryForList("SELECT status,reply_comment_id FROM workitem_comment_delivery "
                        + "WHERE tenant_id=? AND dispatch_id=?",
                dispatch.getTenantId(), dispatch.getId());
        return !replies.isEmpty() && replies.stream().allMatch(row ->
                "APPLIED".equals(row.get("status")) && row.get("reply_comment_id") != null);
    }

    /** Telemetry alone cannot replace a successful result: require its stored terminal checkpoint. */
    private boolean hasCompletionEvidence(DispatchDO dispatch) {
        if (runtimeEvents == null || checkpoints == null) return false;
        var completed = runtimeEvents.findLatestByDispatchAndType(
                dispatch.getTenantId(), dispatch.getId(), "dispatch.completed");
        var terminal = runtimeEvents.findLatestByDispatchAndType(
                dispatch.getTenantId(), dispatch.getId(), "runtime.checkpoint_saved");
        if (completed == null || terminal == null || completed.getSeq() == null || terminal.getSeq() == null
                || completed.getSeq() <= terminal.getSeq()) return false;
        try {
            var result = JSON.parseObject(completed.getDetailJson());
            var receipt = JSON.parseObject(terminal.getDetailJson());
            if (result == null || receipt == null || !"completed".equals(result.getString("state"))
                    || !"terminal".equals(receipt.getString("reason"))) return false;
            Long seq = receipt.getLong("checkpointSeq");
            String hash = receipt.getString("sha256");
            if (seq == null || seq <= 0 || hash == null) return false;
            if (hash.startsWith("sha256:")) hash = hash.substring(7);
            if (hash.isBlank()) return false;
            var checkpoint = checkpoints.findByDispatchAndSeq(dispatch.getTenantId(), dispatch.getId(), seq);
            return checkpoint != null && !checkpoint.isGcPending()
                    && Objects.equals(checkpoint.getExecutorId(), dispatch.getExecutorId())
                    && checkpoint.getOssRef() != null && !checkpoint.getOssRef().isBlank()
                    && checkpoint.getSha256() != null && checkpoint.getSha256().equalsIgnoreCase(hash);
        } catch (IllegalArgumentException | com.aliyun.autowonder.json.JSONException malformedEvidence) {
            return false;
        }
    }

    /** Reconciles only one executor after an authoritative heartbeat. */
    public void reconcileExecutor(long executorId) {
        reconcileStops(executorId);
    }

    private void reconcileStops(Long executorId) {
        String executorFilter = executorId == null ? "" : " AND d.executor_id=?";
        String sql = "SELECT r.dispatch_id, r.requested_at FROM dispatch_recovery r "
                + "JOIN dispatch d ON d.id=r.dispatch_id AND d.tenant_id=r.tenant_id "
                + "WHERE r.stop_pending=1" + executorFilter
                + " AND (r.last_sent_at IS NULL OR r.last_sent_at < ?) ORDER BY r.last_sent_at LIMIT 200";
        Object[] args = executorId == null
                ? new Object[]{new Timestamp(System.currentTimeMillis() - 30_000L)}
                : new Object[]{executorId, new Timestamp(System.currentTimeMillis() - 30_000L)};
        // DATETIME getObject results differ across JDBC drivers (LocalDateTime
        // in Connector/J). A typed read keeps conversion at the JDBC boundary.
        for (var stop : jdbc.query(sql, (rs, index) ->
                new PendingStop(rs.getLong("dispatch_id"), rs.getTimestamp("requested_at")), args)) {
            DispatchDO d = dispatches.findById(stop.dispatchId());
            if (d != null && runtimeProvesStopped(d, stop.requestedAt())
                    && onStopped(d.getTenantId(), d.getExecutorId(), d.getId())) {
                log.info("stop intent self-healed from runtime running set dispatchId={} executorId={}",
                        d.getId(), d.getExecutorId());
                continue;
            }
            sendStop(d);
        }
    }

    private record PendingStop(long dispatchId, Timestamp requestedAt) {}

    /**
     * A live runtime that explicitly reports its running dispatch set without this
     * dispatch has proven the owned execution stopped. Without it one stop whose only
     * acknowledgement window fell into a network outage leaves stale recovery state.
     * Young stop requests keep the plain retry path so a dispatch still being handed
     * out cannot be mistaken for a stopped one.
     */
    private boolean runtimeProvesStopped(DispatchDO d, Timestamp requestedAt) {
        if (executorRegistry == null || d == null || d.getExecutorId() == null) return false;
        if (requestedAt != null
                && requestedAt.getTime() > System.currentTimeMillis() - STOP_SELF_HEAL_GRACE_MS) {
            return false;
        }
        Long executorId = d.getExecutorId();
        if (!executorRegistry.isOnline(executorId)) return false;
        return executorRegistry.currentDispatchSnapshot(executorId)
                .filter(snapshot -> snapshot.inventoryReady() && snapshot.inventoryError() == null)
                .map(snapshot -> !snapshot.ownedDispatchIds().contains(d.getId()))
                .orElse(false);
    }
}
