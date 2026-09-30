package com.aliyun.autowonder.workitem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryRestartStoreTest {

    JdbcTemplate jdbc;
    DeliveryRestartStore store;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:restart-store-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE workitem_execution_control ("
                + "tenant_id BIGINT NOT NULL, workitem_id BIGINT NOT NULL, "
                + "closed TINYINT NOT NULL DEFAULT 0, modifier_id BIGINT NOT NULL DEFAULT 0, "
                + "gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                + "PRIMARY KEY (tenant_id, workitem_id))");
        jdbc.execute("CREATE TABLE workitem_delivery_restart ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, workitem_id BIGINT NOT NULL, "
                + "restart_round INT NOT NULL, restart_token VARCHAR(64) NULL, "
                + "requested_by BIGINT NOT NULL DEFAULT 0, operator_type VARCHAR(16) NOT NULL DEFAULT 'SYSTEM', "
                + "scheduled_start_at DATETIME(3) NULL, sdlc_step_id BIGINT NULL, agent_id BIGINT NULL, "
                + "status VARCHAR(32) NOT NULL DEFAULT 'STARTING', "
                + "stop_reason VARCHAR(512) NULL, dispatch_id BIGINT NULL, "
                + "gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                + "gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                + "UNIQUE (tenant_id, workitem_id, restart_round), "
                + "UNIQUE (tenant_id, workitem_id, restart_token))");
        jdbc.execute("CREATE TABLE workitem_event (id BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "tenant_id BIGINT, workitem_id BIGINT, event_type VARCHAR(64), actor_type VARCHAR(16), "
                + "actor_ref BIGINT, detail_json VARCHAR(1024))");
        store = new DeliveryRestartStore(jdbc);
    }

    private int allocate(String token, Date scheduledStartAt) {
        return store.allocateRound(100L, 500L, 7L, "HUMAN", token, scheduledStartAt, 300031L, 40013L);
    }

    private String statusOf(int round) {
        return jdbc.queryForObject("SELECT status FROM workitem_delivery_restart "
                + "WHERE tenant_id=100 AND workitem_id=500 AND restart_round=" + round, String.class);
    }

    @Test
    void allocateRoundIncrementsPerExplicitRestart() {
        assertEquals(1, allocate(null, null));
        assertEquals(2, allocate(null, null));
        assertEquals(3, allocate(null, null));
    }

    @Test
    void allocateRoundMapsDuplicateSubmissionsOfOneRequestOntoOneRound() {
        assertEquals(1, allocate("token-1", null));
        // Retry of the same request: same token, same round.
        assertEquals(1, allocate("token-1", null));
        // The next explicit reassignment is a new round, whichever token it carries.
        assertEquals(2, allocate("token-2", null));
        assertEquals(2, allocate("token-2", null));
    }

    @Test
    void allocateRoundPersistsStartIntentForWaitingAdvancement() {
        allocate(null, null);

        Map<String, Object> row = jdbc.queryForMap("SELECT sdlc_step_id, agent_id, requested_by "
                + "FROM workitem_delivery_restart WHERE tenant_id=100 AND workitem_id=500 AND restart_round=1");
        assertEquals(300031L, ((Number) row.get("sdlc_step_id")).longValue());
        assertEquals(40013L, ((Number) row.get("agent_id")).longValue());
        assertEquals(7L, ((Number) row.get("requested_by")).longValue());
    }

    @Test
    void allocateRoundSupersedesEarlierDeferredRoundSoItCanNeverFire() {
        Date future = new Date(System.currentTimeMillis() + 3600_000L);
        assertEquals(1, allocate(null, future));
        assertEquals(2, allocate(null, null));

        assertEquals("SUPERSEDED", statusOf(1));
        assertEquals("STARTING", statusOf(2));
        // Delayed restart -> immediate restart -> the planned time arrives: the scanner
        // has no pending scheduled round left to trigger.
        assertNull(store.pendingScheduledRound(100L, 500L));
    }

    @Test
    void allocateRoundSupersedesEarlierWaitingStopOldRound() {
        seedRound(1, null);
        jdbc.update("UPDATE workitem_delivery_restart SET status='STOPPING_OLD',sdlc_step_id=300031,"
                + "agent_id=40013 WHERE tenant_id=100 AND workitem_id=500 AND restart_round=1");

        assertEquals(2, allocate(null, null));

        assertEquals("SUPERSEDED", statusOf(1));
        assertTrue(store.waitingRestarts(100L, 500L).isEmpty());
    }

    @Test
    void allocateRoundLeavesStartedAndTerminalRoundsUntouched() {
        seedRound(1, null);
        seedRound(2, null);
        seedRound(3, null);
        jdbc.update("UPDATE workitem_delivery_restart SET status='STARTED',dispatch_id=900 "
                + "WHERE tenant_id=100 AND workitem_id=500 AND restart_round=1");
        jdbc.update("UPDATE workitem_delivery_restart SET status='FAILED' "
                + "WHERE tenant_id=100 AND workitem_id=500 AND restart_round=2");
        jdbc.update("UPDATE workitem_delivery_restart SET status='SUPERSEDED' "
                + "WHERE tenant_id=100 AND workitem_id=500 AND restart_round=3");

        assertEquals(4, allocate(null, null));

        assertEquals("STARTED", statusOf(1));
        assertEquals("FAILED", statusOf(2));
        assertEquals("SUPERSEDED", statusOf(3));
    }

    @Test
    void tokenRetryOfAnOlderRoundDoesNotSupersedeNewerRounds() {
        assertEquals(1, allocate("token-1", null));
        assertEquals(2, allocate("token-2", null));

        // A late retry of the first submission converges onto round 1; round 2 — the
        // user's newer intent — must keep waiting/running, not be superseded.
        assertEquals(1, allocate("token-1", null));

        assertEquals("STARTING", statusOf(2));
    }

    @Test
    void recordClosedReopenReopensClosedDeliveryOnceAndAudits() {
        jdbc.update("INSERT INTO workitem_execution_control (tenant_id,workitem_id,closed) VALUES (100,500,1)");
        assertTrue(store.closed(100L, 500L));

        store.recordClosedReopen(100L, 500L, 7L, "HUMAN");

        assertFalse(store.closed(100L, 500L));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM workitem_event "
                + "WHERE tenant_id=100 AND workitem_id=500 AND event_type='DELIVERY_REOPEN'", Integer.class));

        // Reopening an already-open delivery is not an event.
        store.recordClosedReopen(100L, 500L, 7L, "HUMAN");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM workitem_event "
                + "WHERE tenant_id=100 AND workitem_id=500 AND event_type='DELIVERY_REOPEN'", Integer.class));
    }

    @Test
    void recordClosedReopenCreatesControlRowForNeverClosedDelivery() {
        assertFalse(store.closed(100L, 500L));

        store.recordClosedReopen(100L, 500L, 7L, "HUMAN");

        assertFalse(store.closed(100L, 500L));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM workitem_event "
                + "WHERE tenant_id=100 AND workitem_id=500", Integer.class));
    }

    @Test
    void pendingScheduledRoundTracksDeferredRestartUntilDispatchExists() {
        assertNull(store.pendingScheduledRound(100L, 500L));
        Date future = new Date(System.currentTimeMillis() + 3600_000L);
        allocate(null, future);

        assertEquals(1, store.pendingScheduledRound(100L, 500L));

        jdbc.update("UPDATE workitem_delivery_restart SET dispatch_id=900 WHERE tenant_id=100 AND workitem_id=500");
        assertNull(store.pendingScheduledRound(100L, 500L));
    }

    @Test
    void pendingScheduledRoundIgnoresStartedOrUnscheduledRounds() {
        allocate(null, null);
        assertNull(store.pendingScheduledRound(100L, 500L));

        Date future = new Date(System.currentTimeMillis() + 3600_000L);
        allocate(null, future);
        jdbc.update("UPDATE workitem_delivery_restart SET status='STARTED' "
                + "WHERE tenant_id=100 AND workitem_id=500 AND restart_round=2");
        assertNull(store.pendingScheduledRound(100L, 500L));
    }

    @Test
    void listRoundsReturnsNewestFirst() {
        allocate(null, null);
        allocate(null, null);

        List<Map<String, Object>> rounds = store.listRounds(100L, 500L, 10);

        assertEquals(2, rounds.size());
        assertEquals(2, ((Number) rounds.get(0).get("restart_round")).intValue());
        assertEquals(1, ((Number) rounds.get(1).get("restart_round")).intValue());
        assertEquals(1, store.listRounds(100L, 500L, 1).size());
    }

    @Test
    void waitingRestartsReturnsWaitingRoundsWithPersistedIntent() {
        assertTrue(store.waitingRestarts(100L, 500L).isEmpty());
        seedRound(1, null);
        jdbc.update("UPDATE workitem_delivery_restart SET status='STOPPING_OLD',sdlc_step_id=300031,"
                + "agent_id=40013 WHERE tenant_id=100 AND workitem_id=500 AND restart_round=1");

        List<DeliveryRestartStore.WaitingRestart> waiting = store.waitingRestarts(100L, 500L);

        assertEquals(1, waiting.size());
        DeliveryRestartStore.WaitingRestart round = waiting.get(0);
        assertEquals(100L, round.tenantId());
        assertEquals(500L, round.workitemId());
        assertEquals(1, round.restartRound());
        assertEquals(300031L, round.sdlcStepId());
        assertEquals(40013L, round.agentId());
        assertEquals(7L, round.requestedBy());
    }

    @Test
    void waitingRestartsSweepCoversEveryWorkitem() {
        seedRound(1, null);
        jdbc.update("UPDATE workitem_delivery_restart SET status='STOPPING_OLD',sdlc_step_id=300031,"
                + "agent_id=40013 WHERE tenant_id=100 AND workitem_id=500 AND restart_round=1");
        jdbc.update("INSERT INTO workitem_delivery_restart "
                + "(tenant_id,workitem_id,restart_round,requested_by,operator_type,sdlc_step_id,agent_id,status) "
                + "VALUES (100,501,1,7,'HUMAN',300032,40013,'STOPPING_OLD')");

        List<DeliveryRestartStore.WaitingRestart> waiting = store.waitingRestarts(50);

        assertEquals(2, waiting.size());
        assertTrue(waiting.stream().anyMatch(r -> r.workitemId() == 500L));
        assertTrue(waiting.stream().anyMatch(r -> r.workitemId() == 501L));
    }

    @Test
    void concurrentInsertCollisionConvergesOntoWinnerRound() {
        seedRound(5, null);
        JdbcTemplate racing = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int update(String sql, Object... args) {
                if (sql.startsWith("INSERT INTO workitem_delivery_restart")) {
                    // Both allocations read max_round=5 and try round 6; the rival lands first.
                    seedRound(6, null);
                }
                return super.update(sql, args);
            }
        };
        DeliveryRestartStore loser = new DeliveryRestartStore(racing);

        // A concurrent restart won round 6 while this request raced in; converge, never duplicate.
        assertEquals(6, loser.allocateRound(100L, 500L, 7L, "HUMAN", null, null, 300031L, 40013L));
    }

    @Test
    void concurrentInsertCollisionPrefersTokenRoundWhenPresent() {
        seedRound(1, null);
        seedRound(2, null);
        seedRound(3, null);
        JdbcTemplate racing = new JdbcTemplate(jdbc.getDataSource()) {
            @Override
            public int update(String sql, Object... args) {
                if (sql.startsWith("INSERT INTO workitem_delivery_restart")) {
                    // The same token resubmitted concurrently wins round 4 before this insert.
                    seedRound(4, "token-1");
                }
                return super.update(sql, args);
            }
        };
        DeliveryRestartStore loser = new DeliveryRestartStore(racing);

        assertEquals(4, loser.allocateRound(100L, 500L, 7L, "HUMAN", "token-1", null, 300031L, 40013L));
    }

    private void seedRound(int round, String token) {
        jdbc.update("INSERT INTO workitem_delivery_restart "
                        + "(tenant_id,workitem_id,restart_round,restart_token,requested_by,operator_type,status) "
                        + "VALUES (100,500,?,?,7,'HUMAN','STARTING')", round, token);
    }

    @Test
    void restartIdempotencyKeyEncodesRoundAndWorkitem() {
        assertEquals("restart:3:500", DeliveryRestartStore.restartIdempotencyKey(500L, 3));
    }
}
