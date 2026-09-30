package com.aliyun.autowonder.dispatch;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DeliveryRestartProgressStoreTest {

    JdbcTemplate jdbc;
    DeliveryRestartProgressStore store;

    @BeforeEach
    void setUp() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:restart-progress-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE workitem_delivery_restart ("
                + "id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT NOT NULL, workitem_id BIGINT NOT NULL, "
                + "restart_round INT NOT NULL, restart_token VARCHAR(64) NULL, "
                + "requested_by BIGINT NOT NULL DEFAULT 0, operator_type VARCHAR(16) NOT NULL DEFAULT 'SYSTEM', "
                + "scheduled_start_at DATETIME(3) NULL, status VARCHAR(32) NOT NULL DEFAULT 'STARTING', "
                + "stop_reason VARCHAR(512) NULL, dispatch_id BIGINT NULL, "
                + "gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                + "gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3), "
                + "UNIQUE (tenant_id, workitem_id, restart_round))");
        store = new DeliveryRestartProgressStore(jdbc);
        jdbc.update("INSERT INTO workitem_delivery_restart (tenant_id,workitem_id,restart_round,status) "
                + "VALUES (100,500,2,'STARTING')");
    }

    private String status() {
        return jdbc.queryForObject("SELECT status FROM workitem_delivery_restart "
                + "WHERE tenant_id=100 AND workitem_id=500 AND restart_round=2", String.class);
    }

    private String stopReason() {
        return jdbc.queryForObject("SELECT stop_reason FROM workitem_delivery_restart "
                + "WHERE tenant_id=100 AND workitem_id=500 AND restart_round=2", String.class);
    }

    private Long dispatchId() {
        return jdbc.queryForObject("SELECT dispatch_id FROM workitem_delivery_restart "
                + "WHERE tenant_id=100 AND workitem_id=500 AND restart_round=2", Long.class);
    }

    @Test
    void markStoppingOldRecordsCountAndReason() {
        store.markStoppingOld(100L, 500L, "restart:2:500", 3);

        assertEquals("STOPPING_OLD", status());
        assertEquals("正在停止旧执行 3 个，停止确认后启动新轮次", stopReason());
        assertNull(dispatchId());
    }

    @Test
    void markStartingClearsStopStateTowardNewRound() {
        store.markStoppingOld(100L, 500L, "restart:2:500", 1);

        store.markStarting(100L, 500L, "restart:2:500");

        assertEquals("STARTING", status());
        assertNull(stopReason());
        assertNull(dispatchId());
    }

    @Test
    void markStartedRecordsNewDispatch() {
        store.markStarted(100L, 500L, "restart:2:500", 88L);

        assertEquals("STARTED", status());
        assertEquals(88L, dispatchId());
        assertNull(stopReason());
    }

    @Test
    void markFailedTruncatesReasonToColumnLimit() {
        String longReason = "x".repeat(600);

        store.markFailed(100L, 500L, "restart:2:500", longReason);

        assertEquals("FAILED", status());
        assertEquals(512, stopReason().length());
        assertNull(dispatchId());
    }

    @Test
    void markFailedKeepsNullReasonNull() {
        store.markFailed(100L, 500L, "restart:2:500", null);

        assertEquals("FAILED", status());
        assertNull(stopReason());
    }

    @Test
    void unparsableRoundKeyUpdatesNothing() {
        store.markStarted(100L, 500L, "garbage", 88L);
        store.markStarted(100L, 500L, null, 88L);
        store.markStarted(100L, 500L, "restart:abc:500", 88L);

        assertEquals("STARTING", status());
        assertNull(dispatchId());
    }

    @Test
    void roundKeyParsesRoundSegmentFromTheMiddle() {
        jdbc.update("INSERT INTO workitem_delivery_restart (tenant_id,workitem_id,restart_round,status) "
                + "VALUES (100,999,12,'STARTING')");

        store.markStarted(100L, 999L, "restart:12:999", 77L);

        Long dispatch = jdbc.queryForObject("SELECT dispatch_id FROM workitem_delivery_restart "
                + "WHERE tenant_id=100 AND workitem_id=999 AND restart_round=12", Long.class);
        assertEquals(77L, dispatch);
    }
}
