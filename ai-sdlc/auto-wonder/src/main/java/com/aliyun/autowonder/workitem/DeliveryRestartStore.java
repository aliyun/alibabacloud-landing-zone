package com.aliyun.autowonder.workitem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Date;

/**
 * Durable bookkeeping for user-initiated delivery restarts: closed-delivery reopen
 * intents, idempotent restart-round allocation and restart audit. Service restarts
 * continue from the persisted rows; there is no in-memory state to lose.
 */
@Component
public class DeliveryRestartStore {

    private static final Logger log = LoggerFactory.getLogger(DeliveryRestartStore.class);

    private final JdbcTemplate jdbc;

    @Autowired(required = false)
    private com.aliyun.autowonder.audit.AuditLogService audit;

    public DeliveryRestartStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    void bindAudit(com.aliyun.autowonder.audit.AuditLogService auditService) {
        this.audit = auditService;
    }

    public boolean closed(long tenantId, long workitemId) {
        return !jdbc.queryForList(
                "SELECT 1 FROM workitem_execution_control WHERE tenant_id=? AND workitem_id=? AND closed=1",
                tenantId, workitemId).isEmpty();
    }

    /** Reopens a closed delivery for an authorized restart and audits the reopen reason. */
    public void recordClosedReopen(long tenantId, long workitemId, long userId, String operator) {
        jdbc.update("INSERT IGNORE INTO workitem_execution_control (tenant_id,workitem_id) VALUES (?,?)",
                tenantId, workitemId);
        int rows = jdbc.update("UPDATE workitem_execution_control SET closed=0,modifier_id=?,gmt_modified=NOW(3) "
                + "WHERE tenant_id=? AND workitem_id=? AND closed=1", userId, tenantId, workitemId);
        if (rows == 1) {
            jdbc.update("INSERT INTO workitem_event (tenant_id,workitem_id,event_type,actor_type,actor_ref,detail_json) "
                    + "VALUES (?,?,?,?,?,?)", tenantId, workitemId, "DELIVERY_REOPEN", operator, userId,
                    "{\"reason\":\"REASSIGN_RESTART\",\"source\":\"reassign\"}");
            audit(tenantId, workitemId, userId, "REOPEN_DELIVERY", "WORKITEM");
            log.info("closed delivery reopened by reassign restart workitemId={} operator={}", workitemId, operator);
        }
    }

    /**
     * Allocates the next restart round idempotently. A restart token maps duplicate
     * submissions and network retries of one request onto the same round; without a
     * token each explicit reassignment allocates the next round. Concurrent inserts
     * collide on the round unique key and converge onto the winner's round.
     *
     * <p>The round persists its own start intent (SDLC entry step + agent): a round
     * waiting for old executions to stop is started later from the stop-confirmation
     * path or the compensation sweep, which must not depend on the workitem's mutable
     * current step. Allocating a new round supersedes every earlier round still waiting
     * (deferred or stopping-old), so a stale delayed round can never fire and stop the
     * newer round's running dispatch.
     */
    public int allocateRound(long tenantId, long workitemId, long userId, String operator,
            String restartToken, Date scheduledStartAt, Long sdlcStepId, Long agentId) {
        if (restartToken != null && !restartToken.isBlank()) {
            Integer byToken = roundByToken(tenantId, workitemId, restartToken);
            if (byToken != null) {
                return byToken;
            }
        }
        Integer current = currentRound(tenantId, workitemId);
        int next = current == null ? 1 : current + 1;
        try {
            jdbc.update("INSERT INTO workitem_delivery_restart (tenant_id,workitem_id,restart_round,restart_token,"
                            + "requested_by,operator_type,scheduled_start_at,sdlc_step_id,agent_id,"
                            + "status,gmt_create,gmt_modified) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,'STARTING',NOW(3),NOW(3))",
                    tenantId, workitemId, next, restartToken, userId, operator,
                    scheduledStartAt == null ? null : new Timestamp(scheduledStartAt.getTime()),
                    sdlcStepId, agentId);
            supersedeEarlierRounds(tenantId, workitemId, next);
            auditRestart(tenantId, workitemId, userId);
            return next;
        } catch (DataIntegrityViolationException race) {
            if (restartToken != null && !restartToken.isBlank()) {
                Integer byToken = roundByToken(tenantId, workitemId, restartToken);
                if (byToken != null) {
                    return byToken;
                }
            }
            Integer winner = currentRound(tenantId, workitemId);
            if (winner != null && winner >= next) {
                // A concurrent restart won the same round; converge instead of duplicating.
                return winner;
            }
            throw race;
        }
    }

    /** Earlier rounds still waiting (deferred or stopping-old) are replaced by {@code round}. */
    private int supersedeEarlierRounds(long tenantId, long workitemId, int round) {
        return jdbc.update("UPDATE workitem_delivery_restart SET status='SUPERSEDED',"
                        + "stop_reason=?,gmt_modified=NOW(3) WHERE tenant_id=? AND workitem_id=? "
                        + "AND restart_round<? AND status IN ('STARTING','STOPPING_OLD')",
                "已被第 " + round + " 轮重启取代", tenantId, workitemId, round);
    }

    public Integer currentRound(long tenantId, long workitemId) {
        var rows = jdbc.queryForList(
                "SELECT MAX(restart_round) AS max_round FROM workitem_delivery_restart "
                        + "WHERE tenant_id=? AND workitem_id=?", tenantId, workitemId);
        if (rows.isEmpty() || rows.get(0).get("max_round") == null) {
            return null;
        }
        return ((Number) rows.get(0).get("max_round")).intValue();
    }

    /** Recent restart rounds, newest first, for the delivery recovery state API. */
    public java.util.List<java.util.Map<String, Object>> listRounds(long tenantId, long workitemId, int limit) {
        return jdbc.queryForList("SELECT restart_round, restart_token, status, stop_reason, dispatch_id, "
                + "scheduled_start_at, requested_by, operator_type, gmt_create, gmt_modified "
                + "FROM workitem_delivery_restart WHERE tenant_id=? AND workitem_id=? "
                + "ORDER BY restart_round DESC LIMIT ?", tenantId, workitemId, limit);
    }

    public Integer roundByToken(long tenantId, long workitemId, String restartToken) {
        var rows = jdbc.queryForList(
                "SELECT restart_round FROM workitem_delivery_restart WHERE tenant_id=? AND workitem_id=? "
                        + "AND restart_token=?", tenantId, workitemId, restartToken);
        if (rows.isEmpty()) {
            return null;
        }
        return ((Number) rows.get(0).get("restart_round")).intValue();
    }

    /**
     * The newest restart round still waiting for its scheduled fire time, i.e. a planned
     * restart the scheduled-start scanner has to trigger. Returns null when the latest
     * round already started.
     */
    public Integer pendingScheduledRound(long tenantId, long workitemId) {
        var rows = jdbc.queryForList(
                "SELECT restart_round FROM workitem_delivery_restart WHERE tenant_id=? AND workitem_id=? "
                        + "AND status='STARTING' AND dispatch_id IS NULL AND scheduled_start_at IS NOT NULL "
                        + "ORDER BY restart_round DESC LIMIT 1", tenantId, workitemId);
        if (rows.isEmpty()) {
            return null;
        }
        return ((Number) rows.get(0).get("restart_round")).intValue();
    }

    public static String restartIdempotencyKey(long workitemId, int round) {
        return "restart:" + round + ":" + workitemId;
    }

    /** A restart round waiting for its old executions to stop, with its persisted start intent. */
    public record WaitingRestart(long tenantId, long workitemId, int restartRound,
            Long sdlcStepId, Long agentId, long requestedBy) { }

    /** Waiting rounds of one workitem, oldest first. */
    public java.util.List<WaitingRestart> waitingRestarts(long tenantId, long workitemId) {
        return jdbc.query("SELECT restart_round, sdlc_step_id, agent_id, requested_by "
                        + "FROM workitem_delivery_restart WHERE tenant_id=? AND workitem_id=? "
                        + "AND status='STOPPING_OLD' ORDER BY restart_round",
                (rs, i) -> new WaitingRestart(tenantId, workitemId, rs.getInt(1),
                        nullableId(rs, 2), nullableId(rs, 3), rs.getLong(4)),
                tenantId, workitemId);
    }

    /** Every waiting round across workitems, for the compensation sweep that resumes them. */
    public java.util.List<WaitingRestart> waitingRestarts(int limit) {
        return jdbc.query("SELECT tenant_id, workitem_id, restart_round, sdlc_step_id, agent_id, requested_by "
                        + "FROM workitem_delivery_restart WHERE status='STOPPING_OLD' "
                        + "ORDER BY gmt_modified LIMIT ?",
                (rs, i) -> new WaitingRestart(rs.getLong(1), rs.getLong(2), rs.getInt(3),
                        nullableId(rs, 4), nullableId(rs, 5), rs.getLong(6)),
                limit);
    }

    private static Long nullableId(ResultSet rs, int column) throws SQLException {
        // MySQL returns BigInteger from getObject for BIGINT UNSIGNED; H2 returns
        // Long. Let JDBC convert the value and preserve missing legacy start intent.
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private void auditRestart(long tenantId, long workitemId, long userId) {
        audit(tenantId, workitemId, userId, "RESTART_DELIVERY", "WORKITEM");
    }

    private void audit(long tenantId, long subjectId, long userId, String action, String targetType) {
        if (audit == null) {
            return;
        }
        var record = new com.aliyun.autowonder.audit.AuditLogRecord();
        record.setTenantId(tenantId);
        record.setActorId(userId);
        record.setActorType(userId == 0 ? "SYSTEM" : "HUMAN");
        record.setModule("workitem");
        record.setAction(action);
        record.setTargetType(targetType);
        record.setTargetId(subjectId);
        record.setEventType(action);
        record.setTriggerType("RESTART");
        audit.recordRequired(record);
    }
}
