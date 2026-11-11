package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Workitem-scoped business keys deliberately survive workflow changes and delivery restarts. */
@Service
public class DeliveryExternalOperationService {
    private static final Set<String> KINDS = Set.of("PR_CREATE", "COMMENT_CREATE", "CLOUD_MUTATION");
    private final JdbcTemplate jdbc;
    private final DispatchDao dispatches;
    private final DispatchRecoveryService recovery;
    private final DispatchWorkflowSnapshotStore snapshots;
    private final AuditLogService audit;

    public DeliveryExternalOperationService(JdbcTemplate jdbc, DispatchDao dispatches, DispatchRecoveryService recovery,
            DispatchWorkflowSnapshotStore snapshots, AuditLogService audit) {
        this.jdbc = jdbc;
        this.dispatches = dispatches;
        this.recovery = recovery;
        this.snapshots = snapshots;
        this.audit = audit;
    }

    public List<Map<String, Object>> list(long tenantId, long workitemId) {
        return jdbc.queryForList("SELECT operation_key AS operationKey,operation_kind AS kind,request_sha256 AS requestSha256,"
                        + "status,owner_dispatch_id AS ownerDispatchId,delivery_round_id AS deliveryRoundId,"
                        + "receipt_ref AS receiptRef,readback_ref AS readbackRef,version "
                        + "FROM delivery_external_operation WHERE tenant_id=? AND workitem_id=? ORDER BY operation_key LIMIT 200",
                tenantId, workitemId);
    }

    @Transactional
    public Map<String, Object> claim(DispatchDO owner, String key, String kind, String requestSha256) {
        requireOwner(owner);
        validateKey(key);
        if (kind == null || !KINDS.contains(kind) || requestSha256 == null || !requestSha256.matches("[a-f0-9]{64}")) throw invalid();
        var rows = jdbc.queryForList("SELECT * FROM delivery_external_operation WHERE tenant_id=? AND workitem_id=? "
                + "AND operation_key=? FOR UPDATE", owner.getTenantId(), owner.getWorkitemId(), key);
        Map<String, Object> row = rows.isEmpty() ? null : rows.get(0);
        if (row != null) {
            if (!kind.equals(row.get("operation_kind")) || !requestSha256.equals(row.get("request_sha256"))) {
                throw BizException.keyed(ErrorCode.CONFLICT, "dispatch.operation.key_conflict", new Object[0],
                        "external operation business key already describes a different request");
            }
            if (!"NOT_FOUND".equals(row.get("status"))) {
                Map<String, Object> result = view(row);
                result.put("mayExecute", false);
                return result;
            }
        }
        var snapshot = snapshots.require(owner);
        String claimToken = UUID.randomUUID().toString();
        if (row == null) {
            jdbc.update("INSERT INTO delivery_external_operation (tenant_id,workitem_id,operation_key,operation_kind,"
                            + "request_sha256,status,owner_dispatch_id,delivery_round_id,claim_token,version) "
                            + "VALUES (?,?,?,?,?,'IN_FLIGHT',?,?,?,0)", owner.getTenantId(), owner.getWorkitemId(), key, kind,
                    requestSha256, owner.getId(), snapshot.roundId(), claimToken);
        } else {
            jdbc.update("UPDATE delivery_external_operation SET status='IN_FLIGHT',owner_dispatch_id=?,delivery_round_id=?,"
                            + "claim_token=?,version=version+1,gmt_modified=NOW(3) WHERE tenant_id=? AND workitem_id=? AND operation_key=?",
                    owner.getId(), snapshot.roundId(), claimToken, owner.getTenantId(), owner.getWorkitemId(), key);
        }
        record(owner, key, "CLAIM", Map.of("kind", kind, "requestSha256", requestSha256));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operationKey", key);
        result.put("status", "IN_FLIGHT");
        result.put("mayExecute", true);
        result.put("claimToken", claimToken);
        return result;
    }

    @Transactional
    public void complete(DispatchDO owner, String key, String claimToken, String status, String receiptRef) {
        requireOwner(owner);
        validateKey(key);
        if (claimToken == null || status == null || !Set.of("SUCCEEDED", "UNKNOWN").contains(status)) throw invalid();
        if ("SUCCEEDED".equals(status)) validateReference(receiptRef);
        int changed = jdbc.update("UPDATE delivery_external_operation SET status=?,receipt_ref=?,version=version+1,gmt_modified=NOW(3) "
                        + "WHERE tenant_id=? AND workitem_id=? AND operation_key=? AND owner_dispatch_id=? AND claim_token=? "
                        + "AND status='IN_FLIGHT'", status, "SUCCEEDED".equals(status) ? receiptRef : null,
                owner.getTenantId(), owner.getWorkitemId(), key, owner.getId(), claimToken);
        if (changed != 1) {
            var replay = jdbc.queryForList("SELECT status,receipt_ref FROM delivery_external_operation "
                            + "WHERE tenant_id=? AND workitem_id=? AND operation_key=? AND owner_dispatch_id=? AND claim_token=?",
                    owner.getTenantId(), owner.getWorkitemId(), key, owner.getId(), claimToken);
            if (!replay.isEmpty() && status.equals(replay.get(0).get("status"))
                    && Objects.equals("SUCCEEDED".equals(status) ? receiptRef : null, replay.get(0).get("receipt_ref"))) return;
            throw changed();
        }
        record(owner, key, status, receiptRef == null ? Map.of() : Map.of("receiptRef", receiptRef));
    }

    /** Readback is an explicit assertion of settled external state; an uncertain lookup must remain UNKNOWN. */
    @Transactional
    public void reconcile(DispatchDO owner, String key, int expectedVersion, String status, String evidenceRef) {
        requireOwner(owner);
        validateKey(key);
        validateReference(evidenceRef);
        if (status == null || !Set.of("SUCCEEDED", "NOT_FOUND").contains(status) || expectedVersion < 0) throw invalid();
        var rows = jdbc.queryForList("SELECT * FROM delivery_external_operation WHERE tenant_id=? AND workitem_id=? "
                + "AND operation_key=? FOR UPDATE", owner.getTenantId(), owner.getWorkitemId(), key);
        if (rows.isEmpty()) throw changed();
        var current = rows.get(0);
        DispatchDO previous = dispatches.findById(((Number) current.get("owner_dispatch_id")).longValue());
        if (previous == null || !Objects.equals(previous.getTenantId(), owner.getTenantId())
                || !Objects.equals(previous.getWorkitemId(), owner.getWorkitemId())) throw changed();
        if (!Objects.equals(previous.getId(), owner.getId())) {
            var state = recovery.controlStates(owner.getTenantId(), List.of(previous.getId())).get(previous.getId());
            if ((!DispatchStatus.isTerminal(previous.getStatus()) && !DispatchStatus.PAUSED.equals(previous.getStatus()))
                    || state != null && state.stopPending()) {
                throw BizException.keyed(ErrorCode.CONFLICT, "dispatch.operation.owner_unconfirmed", new Object[0],
                        "previous external operation owner has not confirmed its stop");
            }
        }
        int updated = jdbc.update("UPDATE delivery_external_operation SET status=?,readback_ref=?,receipt_ref=?,"
                        + "version=version+1,gmt_modified=NOW(3) WHERE tenant_id=? AND workitem_id=? AND operation_key=? "
                        + "AND version=? AND status IN ('UNKNOWN','IN_FLIGHT')", status, evidenceRef,
                "SUCCEEDED".equals(status) ? evidenceRef : null, owner.getTenantId(), owner.getWorkitemId(), key, expectedVersion);
        if (updated != 1) throw changed();
        record(owner, key, "READBACK_" + status, Map.of("evidenceRef", evidenceRef, "expectedVersion", expectedVersion));
    }

    private void requireOwner(DispatchDO supplied) {
        if (supplied == null || supplied.executionSourceType() != ExecutionSourceType.WORKITEM) throw new BizException(ErrorCode.NO_PERMISSION);
        recovery.lockWorkitemControl(supplied);
        DispatchDO current = dispatches.findById(supplied.getId());
        if (current == null || !Objects.equals(current.getTenantId(), supplied.getTenantId())
                || !Objects.equals(current.getWorkitemId(), supplied.getWorkitemId())
                || current.getStatus() == null || !Set.of(DispatchStatus.DISPATCHED, DispatchStatus.ACKED, DispatchStatus.RUNNING).contains(current.getStatus())
                || recovery.fenced(current) || snapshots.fenced(current)) throw new BizException(ErrorCode.NO_PERMISSION);
        recovery.requireOpen(current);
    }

    private Map<String, Object> view(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("operationKey", row.get("operation_key"));
        result.put("status", row.get("status"));
        result.put("receiptRef", row.get("receipt_ref"));
        result.put("ownerDispatchId", row.get("owner_dispatch_id"));
        result.put("deliveryRoundId", row.get("delivery_round_id"));
        result.put("version", row.get("version"));
        return result;
    }

    private void record(DispatchDO owner, String key, String action, Map<String, Object> details) {
        AuditLogRecord event = new AuditLogRecord();
        event.setTenantId(owner.getTenantId());
        event.setActorType("AGENT");
        event.setActorId(owner.getAgentId());
        event.setModule("dispatch");
        event.setAction("EXTERNAL_OPERATION_" + action);
        event.setTargetType("WORKITEM");
        event.setTargetId(owner.getWorkitemId());
        event.setEventType("EXTERNAL_OPERATION_" + action);
        event.setTriggerType("DISPATCH");
        event.detail("dispatchId", owner.getId()).detail("operationKey", key);
        details.forEach(event::detail);
        audit.recordRequired(event);
    }

    private static void validateKey(String key) {
        if (key == null || !key.matches("[A-Za-z0-9][A-Za-z0-9:._/+-]{0,190}")) throw invalid();
    }

    private static void validateReference(String value) {
        if (value == null || value.isBlank() || value.length() > 1024 || value.contains("\n") || value.contains("\r")) throw invalid();
    }

    private static BizException invalid() {
        return BizException.keyed(ErrorCode.PARAM_INVALID, "dispatch.operation.invalid", new Object[0], "invalid external operation receipt request");
    }

    private static BizException changed() {
        return BizException.keyed(ErrorCode.CONFLICT, "dispatch.operation.changed", new Object[0], "external operation receipt changed or is unavailable");
    }
}
