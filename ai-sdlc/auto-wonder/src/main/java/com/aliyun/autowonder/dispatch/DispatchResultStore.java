package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Required V061 persistence. No ACK or completion receipt is fabricated when the schema is absent. */
@Service
public class DispatchResultStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private volatile boolean ready;
    public DispatchResultStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.tx = new TransactionTemplate(manager);
        // Dispatch/workitem row locks serialize competing results. Avoid REPEATABLE_READ
        // gap locks on absent decision keys deadlocking independent result inserts.
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }
    public void requireReady() {
        if (ready) return;
        jdbc.queryForList("SELECT tenant_id,dispatch_id,result_id,executor_id,payload_sha256,checkpoint_seq,"
                + "checkpoint_sha256,failure_category,disposition,reason_code,ack_json,handoff_json FROM dispatch_result_decision WHERE 1=0");
        jdbc.queryForList("SELECT completion_result_id,gc_pending FROM dispatch_recovery_checkpoint WHERE 1=0");
        List<String> indexes = jdbc.queryForList("SELECT CONCAT(TABLE_NAME,':',INDEX_NAME,':',"
                + "GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ',')) "
                + "FROM information_schema.STATISTICS WHERE TABLE_SCHEMA=DATABASE() AND NON_UNIQUE=0 AND "
                + "((TABLE_NAME='dispatch_result_decision' AND INDEX_NAME='PRIMARY') OR "
                + "(TABLE_NAME='dispatch_recovery_checkpoint' AND INDEX_NAME='uk_checkpoint_completion')) "
                + "GROUP BY TABLE_NAME,INDEX_NAME", String.class);
        if (!indexes.contains("dispatch_result_decision:PRIMARY:tenant_id,dispatch_id,result_id")
                || !indexes.contains("dispatch_recovery_checkpoint:uk_checkpoint_completion:tenant_id,dispatch_id,completion_result_id"))
            throw new IllegalStateException("RESULT_SCHEMA_NOT_READY");
        ready = true;
    }
    public <T> T transaction(Supplier<T> action) { return tx.execute(status -> action.get()); }
    public void lockDispatch(long tenantId, long dispatchId) {
        List<Long> rows = jdbc.queryForList("SELECT id FROM dispatch WHERE tenant_id=? AND id=? FOR UPDATE",
                Long.class, tenantId, dispatchId);
        if (rows.isEmpty()) throw new IllegalStateException("RESULT_DISPATCH_NOT_FOUND");
    }
    public void lockSubject(DispatchDO dispatch) {
        if (dispatch.executionSourceType() != ExecutionSourceType.WORKITEM) return;
        jdbc.queryForList("SELECT id FROM workitem WHERE tenant_id=? AND id=? FOR UPDATE",
                dispatch.getTenantId(), dispatch.getWorkitemId());
        jdbc.update("INSERT IGNORE INTO workitem_execution_control (tenant_id,workitem_id) VALUES (?,?)",
                dispatch.getTenantId(), dispatch.getWorkitemId());
        jdbc.queryForList("SELECT closed FROM workitem_execution_control WHERE tenant_id=? AND workitem_id=? FOR UPDATE",
                dispatch.getTenantId(), dispatch.getWorkitemId());
    }
    public Decision find(long tenantId, long dispatchId, String resultId) {
        return find(tenantId, dispatchId, resultId, false);
    }
    public Decision findLocked(long tenantId, long dispatchId, String resultId) {
        return find(tenantId, dispatchId, resultId, true);
    }
    private Decision find(long tenantId, long dispatchId, String resultId, boolean locked) {
        List<Decision> rows = jdbc.query("SELECT executor_id,payload_sha256,ack_json,handoff_json "
                        + "FROM dispatch_result_decision WHERE tenant_id=? AND dispatch_id=? AND result_id=?" + (locked ? " FOR UPDATE" : ""),
                (rs, n) -> new Decision(rs.getLong(1), rs.getString(2),
                        JSON.parseObject(rs.getString(3)), rs.getString(4) == null ? null : JSON.parseObject(rs.getString(4))),
                tenantId, dispatchId, resultId);
        return rows.isEmpty() ? null : rows.get(0);
    }
    public long countSessionRecoveryDecisions(long tenantId, long dispatchId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_result_decision "
                + "WHERE tenant_id=? AND dispatch_id=? AND disposition='ACCEPTED' AND failure_category='runtime_recovery'",
                Long.class, tenantId, dispatchId);
        return count == null ? 0 : count;
    }
    public void insert(long tenantId, long executorId, JSONObject input, String hash,
            JSONObject ack, JSONObject handoff) {
        jdbc.update("INSERT INTO dispatch_result_decision (tenant_id,dispatch_id,result_id,executor_id,"
                        + "payload_sha256,checkpoint_seq,checkpoint_sha256,failure_category,disposition,reason_code,ack_json,handoff_json) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)", tenantId, input.getLongValue("dispatchId"),
                input.getString("resultId"), executorId, hash, input.getLong("checkpointSeq"),
                input.getString("checkpointSha256"), input.getString("failureCategory"), ack.getString("disposition"), ack.getString("reasonCode"),
                ack.toJSONString(), handoff == null ? null : handoff.toJSONString());
    }
    public record Decision(long executorId, String hash, JSONObject ack, JSONObject handoff) { }
}
