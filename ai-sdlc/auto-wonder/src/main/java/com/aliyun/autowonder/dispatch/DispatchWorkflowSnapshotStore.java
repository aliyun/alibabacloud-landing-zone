package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.sdlc.SdlcStepDao;
import com.aliyun.autowonder.storage.ObjectStorage;
import com.aliyun.autowonder.workitem.DeliveryRestartStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipInputStream;

/** Immutable workflow content and lineage, separate from the source-aware dispatch schema. */
@Component
public class DispatchWorkflowSnapshotStore {
    private final JdbcTemplate jdbc;
    private final SdlcStepDao steps;
    private final AgentDao agents;
    private final AgentVersionDao versions;
    private final DispatchDao dispatches;
    private final ObjectStorage storage;
    private final DeliveryRestartStore restarts;

    public DispatchWorkflowSnapshotStore(JdbcTemplate jdbc, SdlcStepDao steps, AgentDao agents,
            AgentVersionDao versions, DispatchDao dispatches, ObjectStorage storage) {
        this.jdbc = jdbc;
        this.steps = steps;
        this.agents = agents;
        this.versions = versions;
        this.dispatches = dispatches;
        this.storage = storage;
        this.restarts = new DeliveryRestartStore(jdbc);
    }

    public record Snapshot(long roundId, Long sourceDispatchId, Long sdlcId, Long stepId,
            Long agentVersionId, String document, String sha256, String fencedBy) {
        public Map<String, Object> workflow() { return JSON.parseObject(document); }
    }

    public Snapshot find(long tenantId, long dispatchId) {
        var rows = jdbc.query("SELECT delivery_round_id,source_dispatch_id,sdlc_id,sdlc_step_id,"
                        + "agent_version_id,snapshot_json,snapshot_sha256,fenced_by_restart_key "
                        + "FROM dispatch_workflow_snapshot WHERE tenant_id=? AND dispatch_id=?",
                (rs, n) -> new Snapshot(rs.getLong(1), nullable(rs, 2), nullable(rs, 3), nullable(rs, 4),
                        nullable(rs, 5), rs.getString(6), rs.getString(7), rs.getString(8)), tenantId, dispatchId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** Called under the workitem control lock, before inserting the dispatch. */
    public Snapshot prepare(DispatchDO dispatch) {
        if (dispatch.executionSourceType() != ExecutionSourceType.WORKITEM
                || DispatchStatus.WAITING_FOR_PAUSE.equals(dispatch.getStatus())) return null;
        Snapshot intent = restartIntent(dispatch.getTenantId(), dispatch.getWorkitemId(), dispatch.getIdempotencyKey());
        if (intent != null) {
            if (!restarts.mayStart(dispatch.getTenantId(), dispatch.getWorkitemId(), dispatch.getIdempotencyKey())) throw superseded();
            dispatch.setAgentVersionId(intent.agentVersionId());
            dispatch.setDeliverySourceDispatchId(intent.sourceDispatchId());
            return intent;
        }
        if (dispatch.getResumeFromDispatchId() != null && isRecovery(dispatch)) {
            DispatchDO source = dispatches.findById(dispatch.getResumeFromDispatchId());
            requireSameSubject(dispatch, source);
            if (fenced(source)) throw superseded();
            Snapshot original = require(source);
            requireNotFenced(original);
            dispatch.setAgentVersionId(original.agentVersionId());
            return new Snapshot(original.roundId(), source.getId(), original.sdlcId(), original.stepId(),
                    original.agentVersionId(), original.document(), original.sha256(), null);
        }
        Long sourceId = DispatchService.isInteractionDispatchValue(dispatch) && dispatch.getResumeFromDispatchId() != null
                ? dispatch.getResumeFromDispatchId() : dispatch.getDeliverySourceDispatchId();
        Snapshot captured = capture(dispatch.getTenantId(), dispatch.getSdlcStepId(), dispatch.getAgentId(),
                dispatch.getAgentVersionId(), sourceId);
        dispatch.setAgentVersionId(captured.agentVersionId());
        if (dispatch.getResumeFromDispatchId() != null && !compatibleResume(dispatch, captured)) {
            dispatch.setResumeFromDispatchId(null);
        }
        return captured;
    }

    private boolean compatibleResume(DispatchDO dispatch, Snapshot next) {
        DispatchDO source = dispatches.findById(dispatch.getResumeFromDispatchId());
        if (source == null || !Objects.equals(source.getTenantId(), dispatch.getTenantId())
                || !Objects.equals(source.getWorkitemId(), dispatch.getWorkitemId())
                || !Objects.equals(source.getAgentId(), dispatch.getAgentId()) || fenced(source)) return false;
        try {
            Snapshot original = require(source);
            return Objects.equals(original.stepId(), next.stepId())
                    && Objects.equals(original.agentVersionId(), next.agentVersionId())
                    && original.sha256().equals(next.sha256());
        } catch (BizException missingHistoricalSnapshot) {
            return false;
        }
    }

    public void insert(DispatchDO dispatch, Snapshot snapshot) {
        if (snapshot == null) return;
        jdbc.update("INSERT INTO dispatch_workflow_snapshot (tenant_id,dispatch_id,workitem_id,delivery_round_id,"
                        + "source_dispatch_id,sdlc_id,sdlc_step_id,agent_version_id,snapshot_json,snapshot_sha256) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)", dispatch.getTenantId(), dispatch.getId(), dispatch.getWorkitemId(),
                snapshot.roundId() == 0 ? dispatch.getId() : snapshot.roundId(), snapshot.sourceDispatchId(),
                snapshot.sdlcId(), snapshot.stepId(), snapshot.agentVersionId(), snapshot.document(), snapshot.sha256());
    }

    public Snapshot capture(long tenantId, Long stepId, Long agentId, Long versionId, Long sourceId) {
        Map<String, Object> workflow = SdlcSnapshotBuilder.build(tenantId, stepId, steps);
        Long flowId = new JSONObject(workflow).getLong("sdlcId");
        if (versionId == null && agentId != null) {
            var agent = agents.findById(agentId);
            if (agent != null && Objects.equals(agent.getTenantId(), tenantId)) versionId = agent.getOnlineVersionId();
        }
        if (versionId != null) {
            var version = versions.findById(versionId);
            if (version == null || !Objects.equals(version.getTenantId(), tenantId)
                    || !Objects.equals(version.getAgentId(), agentId)) throw new BizException(ErrorCode.AGENT_VERSION_NOT_FOUND);
        }
        String json = JSON.toJSONString(workflow);
        return new Snapshot(0, sourceId, flowId, stepId, versionId, json, digest(json), null);
    }

    /** Imports the original package for legacy rows; current configuration is never called a historical snapshot. */
    public Snapshot require(DispatchDO source) {
        return require(source, 0);
    }

    private Snapshot require(DispatchDO source, int depth) {
        if (depth > 64) throw unavailable();
        Snapshot existing = find(source.getTenantId(), source.getId());
        if (existing != null) return existing;
        Snapshot imported;
        if (source.getPackageOssRef() != null) {
            imported = fromPackage(source);
        } else if (source.getResumeFromDispatchId() != null && isRecovery(source)) {
            // An undelivered legacy recovery can still inherit its delivered predecessor.
            DispatchDO parent = dispatches.findById(source.getResumeFromDispatchId());
            requireSameSubject(source, parent);
            if (parent.getId() >= source.getId()) throw unavailable();
            imported = require(parent, depth + 1);
        } else if (source.getExecutorId() == null && source.getAgentVersionId() == null) {
            imported = capture(source.getTenantId(), source.getSdlcStepId(), source.getAgentId(), null,
                    source.getDeliverySourceDispatchId());
        } else {
            throw unavailable();
        }
        jdbc.update("INSERT IGNORE INTO dispatch_workflow_snapshot (tenant_id,dispatch_id,workitem_id,delivery_round_id,"
                        + "source_dispatch_id,sdlc_id,sdlc_step_id,agent_version_id,snapshot_json,snapshot_sha256) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?)", source.getTenantId(), source.getId(), source.getWorkitemId(),
                imported.roundId() == 0 ? source.getId() : imported.roundId(), source.getResumeFromDispatchId(),
                imported.sdlcId(), imported.stepId(), imported.agentVersionId(), imported.document(), imported.sha256());
        return find(source.getTenantId(), source.getId());
    }

    public void freezeRestart(long tenantId, long workitemId, int round, Long stepId, Long agentId) {
        String key = DeliveryRestartStore.restartIdempotencyKey(workitemId, round);
        if (restartIntent(tenantId, workitemId, key) != null) return;
        DispatchDO source = dispatches.listByWorkitem(tenantId, workitemId).stream()
                .filter(row -> !DispatchService.isInteractionDispatchValue(row))
                .max(java.util.Comparator.comparingLong(DispatchDO::getId)).orElse(null);
        Snapshot snapshot = capture(tenantId, stepId, agentId, null, source == null ? null : source.getId());
        jdbc.update("INSERT INTO delivery_restart_snapshot (tenant_id,workitem_id,restart_key,source_dispatch_id,"
                        + "sdlc_id,sdlc_step_id,agent_version_id,snapshot_json,snapshot_sha256) VALUES (?,?,?,?,?,?,?,?,?)",
                tenantId, workitemId, key, snapshot.sourceDispatchId(), snapshot.sdlcId(), snapshot.stepId(),
                snapshot.agentVersionId(), snapshot.document(), snapshot.sha256());
        // Completed formal rows are fenced too: a delayed handoff or manual retry cannot revive the old round.
        for (DispatchDO old : dispatches.listByWorkitem(tenantId, workitemId)) {
            if (DispatchService.isInteractionDispatchValue(old)) continue;
            jdbc.update("UPDATE dispatch_workflow_snapshot SET fenced_by_restart_key=? "
                    + "WHERE tenant_id=? AND dispatch_id=? AND fenced_by_restart_key IS NULL", key, tenantId, old.getId());
        }
    }

    public Snapshot restartIntent(long tenantId, long workitemId, String restartKey) {
        if (restartKey == null || !restartKey.startsWith("restart:")) return null;
        var rows = jdbc.query("SELECT source_dispatch_id,sdlc_id,sdlc_step_id,agent_version_id,snapshot_json,snapshot_sha256 "
                        + "FROM delivery_restart_snapshot WHERE tenant_id=? AND workitem_id=? AND restart_key=?",
                (rs, n) -> new Snapshot(0, nullable(rs, 1), nullable(rs, 2), nullable(rs, 3), nullable(rs, 4),
                        rs.getString(5), rs.getString(6), null), tenantId, workitemId, restartKey);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public boolean fenced(DispatchDO dispatch) {
        if (dispatch.executionSourceType() != ExecutionSourceType.WORKITEM) return false;
        Snapshot snapshot = find(dispatch.getTenantId(), dispatch.getId());
        if (snapshot != null) return snapshot.fencedBy() != null;
        // Legacy rows have no snapshot to mark; persist the same boundary in the restart intent.
        return !jdbc.queryForList("SELECT 1 FROM delivery_restart_snapshot WHERE tenant_id=? AND workitem_id=? "
                        + "AND source_dispatch_id>=? LIMIT 1", dispatch.getTenantId(), dispatch.getWorkitemId(), dispatch.getId()).isEmpty();
    }

    public void requireNotFenced(Snapshot snapshot) {
        if (snapshot != null && snapshot.fencedBy() != null) throw superseded();
    }

    private Snapshot fromPackage(DispatchDO source) {
        try {
            byte[] bytes = storage.get(source.getPackageOssRef());
            if (bytes == null || bytes.length > 64 * 1024 * 1024) throw unavailable();
            JSONObject manifest = null;
            String document = null;
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    if (!entry.getName().equals("sdlc.json") && !entry.getName().equals("manifest.json")) continue;
                    byte[] content = zip.readNBytes(2 * 1024 * 1024 + 1);
                    if (content.length > 2 * 1024 * 1024) throw unavailable();
                    String text = new String(content, StandardCharsets.UTF_8);
                    if (entry.getName().equals("manifest.json")) manifest = JSON.parseObject(text); else document = text;
                }
            }
            if (manifest == null || document == null
                    || !Objects.equals(manifest.getLong("tenantId"), source.getTenantId())
                    || !Objects.equals(manifest.getLong("workitemId"), source.getWorkitemId())
                    || !Objects.equals(manifest.getLong("dispatchId"), source.getId())) throw unavailable();
            JSONObject workflow = JSON.parseObject(document);
            if (source.getAgentVersionId() != null
                    && !source.getAgentVersionId().equals(manifest.getLong("agentVersionId"))) throw unavailable();
            var digests = manifest.getJSONObject("fileDigests");
            if (digests == null || !("sha256:" + digest(document)).equals(digests.getString("sdlc.json"))) throw unavailable();
            if (source.getSdlcStepId() != null
                    && !String.valueOf(source.getSdlcStepId()).equals(workflow.getString("currentStepId"))) throw unavailable();
            return new Snapshot(0, source.getResumeFromDispatchId(), workflow.getLong("sdlcId"), source.getSdlcStepId(),
                    manifest.getLong("agentVersionId"), document, digest(document), null);
        } catch (BizException failure) {
            throw failure;
        } catch (Exception failure) {
            throw BizException.keyed(ErrorCode.CONFLICT, "dispatch.snapshot.unavailable", new Object[0],
                    "original workflow snapshot cannot be read", failure);
        }
    }

    private static boolean isRecovery(DispatchDO d) {
        return "RECOVERY".equals(d.getResumeMode()) || d.getIdempotencyKey() != null && d.getIdempotencyKey().startsWith("continue:");
    }

    private static void requireSameSubject(DispatchDO dispatch, DispatchDO source) {
        if (source == null || source.executionSourceType() != ExecutionSourceType.WORKITEM
                || !Objects.equals(source.getTenantId(), dispatch.getTenantId())
                || !Objects.equals(source.getWorkitemId(), dispatch.getWorkitemId())
                || !Objects.equals(source.getAgentId(), dispatch.getAgentId())
                || !Objects.equals(source.getSdlcStepId(), dispatch.getSdlcStepId())) throw unavailable();
    }

    public static BizException superseded() {
        return BizException.keyed(ErrorCode.CONFLICT, "dispatch.snapshot.superseded", new Object[0], "delivery round has been superseded");
    }

    private static BizException unavailable() {
        return BizException.keyed(ErrorCode.CONFLICT, "dispatch.snapshot.unavailable", new Object[0], "original workflow snapshot is unavailable");
    }

    private static Long nullable(java.sql.ResultSet rs, int column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static String digest(String json) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
