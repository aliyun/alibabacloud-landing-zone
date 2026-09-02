package com.aliyun.autowonder.scheduledtask;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Database-level races which must stay correct even when nodes use separate connections. */
@Testcontainers(disabledWithoutDocker = true)
class ScheduledTaskConcurrencyTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("test").withUsername("test").withPassword("test");

    private final ScheduledTaskIntegrationFixture fixture = new ScheduledTaskIntegrationFixture(MYSQL);

    @Test
    void triggerKeyRaceCreatesExactlyOneRunAcrossIndependentConnections() throws Exception {
        fixture.createDatabase("scheduled_race");
        try (Connection connection = fixture.open("scheduled_race")) {
            fixture.applyFile(connection, "docs/autowonder-schema.sql");
        }
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> insertRunAfterBarrier(ready, start));
            Future<Boolean> second = pool.submit(() -> insertRunAfterBarrier(ready, start));
            assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
        } finally {
            pool.shutdownNow();
        }
        try (Connection connection = fixture.open("scheduled_race")) {
            assertEquals(1, fixture.count(connection,
                    "SELECT COUNT(*) FROM scheduled_task_run WHERE workspace_id = 9 AND trigger_key = 'task:88:scheduled:2026-08-10T18:00:00Z'"));
        }
    }

    @Test
    void equalNumericIdsRemainSeparatedBySourceAndTenant() throws Exception {
        fixture.createDatabase("scheduled_sources");
        try (Connection connection = fixture.open("scheduled_sources")) {
            fixture.applyFile(connection, "docs/autowonder-schema.sql");
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO dispatch(tenant_id, source_type, workitem_id, agent_id, idempotency_key) VALUES (1, 'WORKITEM', 10000, 1, 'WORKITEM:10000:1:0')");
                statement.executeUpdate("INSERT INTO dispatch(tenant_id, source_type, workitem_id, agent_id, idempotency_key) VALUES (1, 'SCHEDULED_TASK_RUN', 10000, 1, 'SCHEDULED_TASK_RUN:10000:1:0')");
                statement.executeUpdate("INSERT INTO dispatch(tenant_id, source_type, workitem_id, agent_id, idempotency_key) VALUES (2, 'WORKITEM', 10000, 1, 'WORKITEM:10000:1:0')");
            }
            assertEquals(2, fixture.count(connection,
                    "SELECT COUNT(*) FROM dispatch WHERE tenant_id = 1 AND workitem_id = 10000"));
            assertEquals(1, fixture.count(connection,
                    "SELECT COUNT(*) FROM dispatch WHERE tenant_id = 1 AND source_type = 'SCHEDULED_TASK_RUN' AND workitem_id = 10000"));
            assertEquals(1, fixture.count(connection,
                    "SELECT COUNT(*) FROM dispatch WHERE tenant_id = 2 AND source_type = 'WORKITEM' AND workitem_id = 10000"));
        }
    }

    private boolean insertRunAfterBarrier(CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        start.await();
        try (Connection connection = fixture.open("scheduled_race"); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO scheduled_task_run(workspace_id, scheduled_task_id, trigger_key, trigger_type, scheduled_at, status, squad_id, initial_agent_id, session_mode, execution_snapshot_json, owner_id, creator_id) VALUES (9, 88, 'task:88:scheduled:2026-08-10T18:00:00Z', 'SCHEDULED', '2026-08-10 18:00:00.000', 'QUEUED', 1, 1, 'ISOLATED', JSON_OBJECT(), 1, 1)");
            return true;
        } catch (SQLException duplicate) {
            return false;
        }
    }
}
