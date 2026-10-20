package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.json.JSONObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.junit.jupiter.api.Assertions.*;

/** Real InnoDB locks: H2 cannot detect missing-result gap lock deadlocks. */
@Testcontainers(disabledWithoutDocker = true)
class DispatchResultConcurrencyMySqlTest {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4");
    JdbcTemplate jdbc;
    DispatchResultStore store;

    @BeforeEach void setup() throws Exception {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        store = new DispatchResultStore(jdbc, new DataSourceTransactionManager(ds));
        jdbc.execute("DROP TABLE IF EXISTS dispatch_result_decision");
        jdbc.execute("DROP TABLE IF EXISTS dispatch");
        jdbc.execute("CREATE TABLE dispatch(id BIGINT PRIMARY KEY,tenant_id BIGINT,status VARCHAR(32)) ENGINE=InnoDB");
        jdbc.execute("INSERT INTO dispatch VALUES(1,1,'RUNNING'),(2,2,'RUNNING')");
        String migration = Files.readString(Path.of("docs/migration/V078__durable_dispatch_result.sql"));
        jdbc.execute(migration.substring(migration.indexOf("CREATE TABLE IF NOT EXISTS dispatch_result_decision")));
    }

    @Test void independentResultsCommitWithoutGapLockDeadlock() throws Exception {
        var barrier = new CyclicBarrier(2);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = pool.submit(() -> complete(1, "result", barrier));
            Future<?> second = pool.submit(() -> complete(2, "result", barrier));
            first.get(20, TimeUnit.SECONDS);
            second.get(20, TimeUnit.SECONDS);
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_result_decision", Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test void concurrentDuplicateResultsAreAppliedOnce() throws Exception {
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Void> task = () -> { assertTrue(start.await(5, TimeUnit.SECONDS)); complete(1, "same", null); return null; };
            Future<?> first = pool.submit(task), second = pool.submit(task);
            start.countDown();
            first.get(20, TimeUnit.SECONDS); second.get(20, TimeUnit.SECONDS);
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_result_decision", Integer.class));
        } finally { pool.shutdownNow(); }
    }

    @Test void failureRollsBackDecisionAndTerminalState() {
        assertThrows(IllegalStateException.class, () -> store.transaction(() -> {
            complete(1, "rollback", null);
            throw new IllegalStateException("after insert");
        }));
        assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM dispatch WHERE id=1", String.class));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_result_decision", Integer.class));
        complete(1, "rollback", null);
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_result_decision", Integer.class));
    }

    private void complete(long id, String result, CyclicBarrier barrier) {
        store.transaction(() -> {
            store.lockDispatch(id, id);
            if (store.findLocked(id, id, result) != null) return null;
            if (barrier != null) {
                try { barrier.await(5, TimeUnit.SECONDS); }
                catch (Exception e) { throw new IllegalStateException(e); }
            }
            assertEquals("RUNNING", jdbc.queryForObject("SELECT status FROM dispatch WHERE id=?", String.class, id));
            jdbc.update("UPDATE dispatch SET status='SUCCEEDED' WHERE id=?", id);
            JSONObject input = new JSONObject(); input.put("dispatchId", id); input.put("resultId", result);
            JSONObject ack = new JSONObject(); ack.put("disposition", "ACCEPTED");
            store.insert(id, 1, input, "a".repeat(64), ack, null);
            return null;
        });
    }
}
