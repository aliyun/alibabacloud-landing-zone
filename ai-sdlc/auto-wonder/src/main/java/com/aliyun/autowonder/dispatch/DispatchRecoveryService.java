package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    public boolean closed(long tenantId, long workitemId) {
        return !jdbc.queryForList("SELECT 1 FROM workitem_execution_control WHERE tenant_id=? AND workitem_id=? AND closed=1",
                tenantId, workitemId).isEmpty();
    }

    public void requireOpen(DispatchDO d) {
        if (d.executionSourceType() == ExecutionSourceType.WORKITEM && closed(d.getTenantId(), d.getWorkitemId()))
            throw new BizException(ErrorCode.CONFLICT, "工单已关闭，请先重新打开交付");
    }

    public void insert(DispatchDO d) {
        tx.executeWithoutResult(s -> {
            lockSubject(d); requireOpen(d); dispatches.insert(d);
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
        tx.executeWithoutResult(s -> { lockSubject(d); cancelLocked(dispatches.findById(dispatchId), userId, force); });
        sendStop(dispatches.findById(dispatchId));
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

    private void cancelLocked(DispatchDO d, long userId, boolean force) {
        if (DispatchStatus.isTerminal(d.getStatus())) {
            projectTerminal(d, d.getStatus(), d.getError());
            return;
        }
        if (!force && cancelRequested(d.getTenantId(), d.getId())) return;
        boolean pendingStop = d.getExecutorId() != null && !Set.of("PENDING", "PACKAGING", "WAITING_FOR_PAUSE", "PAUSED").contains(d.getStatus());
        String target = pendingStop && !force ? DispatchStatus.PAUSING : DispatchStatus.CANCELED;
        String reason = pendingStop ? (force ? "CANCELED_STOP_UNCONFIRMED: 平台已结束，执行器停止未确认" : "CANCEL_REQUESTED: 正在取消") : "USER_CANCELED";
        if (dispatches.updateStatus(d.getId(), d.getTenantId(), target, null, null, null, null, reason, d.getVersion(), userId) != 1)
            throw new BizException(ErrorCode.CONFLICT, "执行状态已变化，请重试取消");
        jdbc.update("INSERT INTO dispatch_recovery (tenant_id,dispatch_id,cancel_requested,stop_pending,forced,requested_at,modifier_id,reason) "
                + "VALUES (?,?,1,?,?,NOW(),?,?) ON DUPLICATE KEY UPDATE cancel_requested=1,stop_pending=VALUES(stop_pending),"
                + "forced=GREATEST(forced,VALUES(forced)),requested_at=COALESCE(requested_at,NOW()),modifier_id=VALUES(modifier_id),reason=VALUES(reason),gmt_modified=NOW()",
                d.getTenantId(), d.getId(), pendingStop ? 1 : 0, force ? 1 : 0, userId, reason);
        // No new guidance may be delivered after a cancel request.
        projectTerminal(d, DispatchStatus.CANCELED, reason);
        audit(d.getTenantId(), d.getId(), userId, force ? "FORCE_CANCEL_DISPATCH" : "CANCEL_DISPATCH", "DISPATCH");
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
        return tx.execute(s -> {
            DispatchDO d = dispatches.findById(dispatchId);
            if (d == null || !Objects.equals(d.getTenantId(), tenantId) || !Objects.equals(d.getExecutorId(), executorId)) return false;
            lockSubject(d);
            if (!cancelRequested(tenantId, dispatchId)) return false;
            d = dispatches.findById(dispatchId);
            if ((!DispatchStatus.isTerminal(d.getStatus()) || (DispatchStatus.CANCELED.equals(d.getStatus()) && !"USER_CANCELED".equals(d.getError()))) && dispatches.updateStatus(dispatchId, tenantId, DispatchStatus.CANCELED,
                    null, null, null, null, "USER_CANCELED", d.getVersion(), 0L) != 1) return false;
            jdbc.update("UPDATE dispatch_recovery SET stop_pending=0,reason='USER_CANCELED',gmt_modified=NOW() WHERE tenant_id=? AND dispatch_id=?", tenantId, dispatchId);
            if (DispatchStatus.isTerminal(d.getStatus())) {
                projectTerminal(d, d.getStatus(), d.getError());
            } else {
                projectTerminal(d, DispatchStatus.CANCELED, "USER_CANCELED");
            }
            return true;
        });
    }

    public Map<String, Object> close(long tenantId, long workitemId, long userId, boolean force) {
        validateWorkitem(tenantId, workitemId);
        tx.executeWithoutResult(s -> {
            DispatchDO subject = new DispatchDO(); subject.setTenantId(tenantId); subject.setWorkitemId(workitemId);
            subject.setSourceType("WORKITEM"); lockSubject(subject);
            jdbc.update("UPDATE workitem_execution_control SET closed=1,modifier_id=?,gmt_modified=NOW() WHERE tenant_id=? AND workitem_id=?", userId, tenantId, workitemId);
            for (DispatchDO d : dispatches.listByWorkitem(tenantId, workitemId)) cancelLocked(d, userId, force);
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
                throw new BizException(ErrorCode.CONFLICT, "请先结束未完成的取消操作");
            jdbc.update("UPDATE workitem_execution_control SET closed=0,modifier_id=?,gmt_modified=NOW() WHERE tenant_id=? AND workitem_id=?", userId, tenantId, workitemId);
        });
        audit(tenantId, workitemId, userId, "REOPEN_DELIVERY", "WORKITEM");
        return state(tenantId, workitemId);
    }

    private void validateWorkitem(long tenantId, long workitemId) {
        var w = workitems.findById(workitemId);
        if (w == null || !Objects.equals(w.getTenantId(), tenantId)) throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
    }

    public Map<String, Object> state(long tenantId, long workitemId) {
        validateWorkitem(tenantId, workitemId);
        var executions = dispatches.listByWorkitem(tenantId, workitemId).stream().map(d -> {
            var details = jdbc.queryForList("SELECT cancel_requested,stop_pending,forced,retry_count,next_retry_at,phase,reason,requested_at FROM dispatch_recovery WHERE tenant_id=? AND dispatch_id=?", tenantId, d.getId());
            Map<String, Object> row = new java.util.LinkedHashMap<>();
            row.put("dispatchId", d.getId()); row.put("status", d.getStatus()); row.put("error", d.getError());
            row.put("agentId", d.getAgentId()); row.put("attempt", d.getAttempt()); row.put("updatedAt", d.getGmtModified());
            var detail = details.isEmpty() ? new java.util.LinkedHashMap<String,Object>() : details.get(0);
            detail.put("max_retries", packageRetries);
            row.put("recovery", detail);
            row.put("phase", d.getStatus());
            String reason = d.getError() != null ? d.getError() : (String) detail.get("reason");
            row.put("errorCode", reason == null ? null : reason.split(":", 2)[0]);
            row.put("retryable", Set.of("FAILED", "TIMEOUT", "CANCELED", "PAUSED").contains(d.getStatus()));
            return row;
        }).toList();
        return Map.of("closed", closed(tenantId, workitemId), "executions", executions);
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
