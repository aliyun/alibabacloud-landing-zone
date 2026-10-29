package com.aliyun.autowonder.sdlc;

import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.agent.AgentVersionDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.sdlc.dto.CreateStepRequest;
import com.aliyun.autowonder.sdlc.dto.ReorderRequest;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.concurrent.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Uses the unchanged canonical signed-INT tables and the real production mapper SQL. */
@Testcontainers(disabledWithoutDocker = true)
class SdlcStructureMySqlTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("sdlc_structure").withUsername("test").withPassword("test");
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    SdlcService service;
    SdlcDao parents;
    SdlcStepDao steps;
    WorkitemDao workitems;
    AgentVersionDao versions;
    static final long LARGE = 1792777830441368L;

    @BeforeEach
    void setup() throws Exception {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(ds);
        String schema = Files.readString(Path.of("docs/autowonder-schema.sql"));
        for (String table : List.of("sdlc", "sdlc_step")) {
            var block = Pattern.compile("CREATE TABLE IF NOT EXISTS `" + table + "` \\(.*?;", Pattern.DOTALL).matcher(schema);
            assertTrue(block.find());
            jdbc.execute(block.group());
            jdbc.update("DELETE FROM " + table);
        }
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(ds);
        var config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(config);
        factory.setMapperLocations(new ClassPathResource("mapping/SdlcDao.xml"), new ClassPathResource("mapping/SdlcStepDao.xml"));
        var session = new SqlSessionTemplate(factory.getObject());
        parents = session.getMapper(SdlcDao.class);
        steps = session.getMapper(SdlcStepDao.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        workitems = mock(WorkitemDao.class);
        versions = mock(AgentVersionDao.class);
        service = new SdlcService(parents, steps, mock(StatusNodeDao.class), workitems, versions, mock(AgentDao.class));
        jdbc.update("INSERT INTO sdlc(id,tenant_id,name) VALUES (9,100,'owned'),(10,100,'other')");
    }

    void step(long id, int order, boolean deleted) {
        jdbc.update("INSERT INTO sdlc_step(id,tenant_id,sdlc_id,step_order,name,is_deleted,instruction_md) VALUES (?,100,9,?,'owned',?,'retained')", id, order, deleted);
    }
    void run(Runnable body) { tx.executeWithoutResult(status -> body.run()); }
    List<Integer> orders() { return steps.listBySdlc(9L).stream().map(SdlcStepDO::getStepOrder).toList(); }
    ReorderRequest reorder(Long... ids) { var req = new ReorderRequest(); req.setStepIds(List.of(ids)); return req; }
    void add() { var req = new CreateStepRequest(); req.setName("replacement"); service.addStep(9, req, 100, 7); }

    @Test
    void deletesLargeIdsAndRetainsHistoryAcrossTailMiddleAddCycles() {
        step(LARGE, 1, false); step(Long.MAX_VALUE - 20, 2, false); step(LARGE + 1, 3, false);
        run(() -> service.deleteStep(9, LARGE + 1, 100, 7));
        run(this::add);
        run(() -> service.deleteStep(9, Long.MAX_VALUE - 20, 100, 7));
        run(this::add);
        assertEquals(List.of(1, 2, 3), orders());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM sdlc_step WHERE is_deleted=1 AND step_order<0 AND instruction_md='retained'", Integer.class));
        assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM sdlc_step", Integer.class));
        assertEquals(3, steps.listBySdlcIds(List.of(9L)).size());
        assertEquals(3, steps.countBySdlcIds(List.of(9L)).get(0).getCnt());
        assertNull(steps.findById(LARGE + 1));
        assertThrows(BizException.class, () -> run(() -> service.deleteStep(9, LARGE + 1, 100, 7)));
    }

    @Test
    void reorderAvoidsLegacyBoundaryTombstonesAndLeavesThemUntouched() {
        step(LARGE, 1, false); step(LARGE + 1, 2, false);
        step(11, -1, true); step(12, Integer.MIN_VALUE, true); step(13, Integer.MIN_VALUE + 1, true);
        run(() -> service.reorderSteps(9, reorder(LARGE + 1, LARGE), 100, 7));
        assertEquals(List.of(LARGE + 1, LARGE), steps.listBySdlc(9L).stream().map(SdlcStepDO::getId).toList());
        assertEquals(List.of(Integer.MIN_VALUE, Integer.MIN_VALUE + 1, -1), jdbc.queryForList("SELECT step_order FROM sdlc_step WHERE is_deleted=1 ORDER BY step_order", Integer.class));
    }

    @Test
    void wholeDeletePreservesOrdersAndLargeIds() {
        step(LARGE, 1, false); step(Long.MAX_VALUE - 20, 2, false); step(11, -1, true);
        run(() -> service.delete(9, 100, 7));
        assertNull(parents.findById(9L));
        assertEquals(List.of(-1, 1, 2), jdbc.queryForList("SELECT step_order FROM sdlc_step ORDER BY step_order", Integer.class));
        assertEquals(3, jdbc.queryForObject("SELECT COUNT(*) FROM sdlc_step WHERE is_deleted=1", Integer.class));
    }

    @Test
    void positiveOverflowAndDuplicateReorderRejectBeforeWrites() {
        step(LARGE, Integer.MAX_VALUE, false);
        assertThrows(BizException.class, () -> run(this::add));
        assertEquals(List.of(Integer.MAX_VALUE), orders());
        assertThrows(BizException.class, () -> run(() -> service.reorderSteps(9, reorder(LARGE, LARGE), 100, 7)));
        assertEquals(List.of(Integer.MAX_VALUE), orders());
    }
    @Test
    void allocationModelsFiniteCapacityAndScansMultiplePagesWithoutChangingHistory() {
        for (int i = 1; i <= 270; i++) step(1000 + i, -i, true);
        step(2000, Integer.MIN_VALUE, true);
        run(() -> {
            var allocator = new SdlcStepOrderAllocator(steps);
            assertEquals(List.of(-271, -272), allocator.allocate(9, 100, 2));
            assertThrows(BizException.class, () -> allocator.allocate(9, 100, 1, -270));
            // One free slot is insufficient for a two-slot reservation; no partial mutations.
            assertThrows(BizException.class, () -> allocator.allocate(9, 100, 2, -271));
        });
        assertEquals(271, jdbc.queryForObject("SELECT COUNT(*) FROM sdlc_step", Integer.class));
        step(LARGE, 1, false);
        run(() -> service.delete(9, 100, 7));
        assertNull(parents.findById(9L));
        assertEquals(1, jdbc.queryForObject("SELECT step_order FROM sdlc_step WHERE id=?", Integer.class, LARGE));
        assertEquals(272, jdbc.queryForObject("SELECT COUNT(*) FROM sdlc_step WHERE is_deleted=1", Integer.class));
    }

    @Test
    void secondPassFailureAndBulkChildFailureRollBackEveryWrite() {
        step(LARGE, 1, false); step(LARGE + 1, 2, false);
        var failing = mock(SdlcStepDao.class, org.mockito.AdditionalAnswers.delegatesTo(steps));
        doReturn(0).when(failing).updateOrder(LARGE, 100L, 9L, 2, 7L);
        var failedService = new SdlcService(parents, failing, mock(StatusNodeDao.class), workitems, versions, mock(AgentDao.class));
        assertThrows(BizException.class, () -> run(() -> failedService.reorderSteps(9, reorder(LARGE + 1, LARGE), 100, 7)));
        assertEquals(List.of(1, 2), orders());
        doReturn(0).when(failing).updateOrder(LARGE + 1, 100L, 9L, 1, 7L);
        assertThrows(BizException.class, () -> run(() -> failedService.deleteStep(9, LARGE, 100, 7)));
        assertEquals(List.of(1, 2), orders());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM sdlc_step WHERE is_deleted=1", Integer.class));
        doThrow(new IllegalStateException("forced child failure")).when(failing).deleteAllForDeletedParent(9L, 100L);
        assertThrows(IllegalStateException.class, () -> run(() -> failedService.delete(9, 100, 7)));
        assertNotNull(parents.findById(9L));
        assertEquals(List.of(1, 2), orders());
    }

    @Test
    void tenantParentAndLiveReferenceChecksMakeNoChanges() {
        step(LARGE, 1, false);
        assertThrows(BizException.class, () -> run(() -> service.deleteStep(9, LARGE, 101, 7)));
        assertThrows(BizException.class, () -> run(() -> service.deleteStep(10, LARGE, 100, 7)));
        assertThrows(BizException.class, () -> run(() -> service.delete(9, 101, 7)));
        when(workitems.countBySdlcId(9L)).thenReturn(1);
        when(workitems.listIdsBySdlcId(9L, 5)).thenReturn(List.of(12L));
        assertThrows(BizException.class, () -> run(() -> service.delete(9, 100, 7)));
        assertNotNull(parents.findById(9L));
        assertEquals(List.of(1), orders());
    }

    @ParameterizedTest
    @ValueSource(strings = {"delete/delete", "delete/add", "delete/reorder", "reorder/reorder", "whole/add"})
    void parentLockSerializesWritersAndCurrentReadsIgnoreOlderRepeatableReadSnapshot(String pair) throws Exception {
        step(LARGE, 1, false); step(LARGE + 1, 2, false);
        var pool = Executors.newFixedThreadPool(2);
        var held = new CountDownLatch(1);
        var snapshot = new CountDownLatch(1);
        var mutated = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> run(() -> {
                jdbc.queryForList("SELECT id FROM sdlc WHERE id=9 FOR UPDATE");
                held.countDown(); await(snapshot);
                if (pair.startsWith("whole")) service.delete(9, 100, 7);
                else if (pair.startsWith("reorder")) service.reorderSteps(9, reorder(LARGE + 1, LARGE), 100, 7);
                else service.deleteStep(9, LARGE, 100, 7);
                mutated.countDown(); await(release);
            }));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            Future<?> second = pool.submit(() -> run(() -> {
                assertEquals(List.of(1, 2), orders()); // Establish an old RR snapshot and mapper cache.
                parents.findById(9L);
                snapshot.countDown();
                if (pair.equals("whole/add")) assertThrows(BizException.class, this::add);
                else if (pair.endsWith("/add")) add();
                else if (pair.endsWith("/delete")) service.deleteStep(9, LARGE + 1, 100, 7);
                else if (pair.startsWith("delete")) service.reorderSteps(9, reorder(LARGE + 1), 100, 7);
                else service.reorderSteps(9, reorder(LARGE, LARGE + 1), 100, 7);
            }));
            assertTrue(mutated.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(200, TimeUnit.MILLISECONDS));
            release.countDown(); first.get(10, TimeUnit.SECONDS); second.get(10, TimeUnit.SECONDS);
            if (pair.equals("delete/delete")) {
                assertEquals(List.of(), orders());
                assertEquals(List.of(-2, -1), jdbc.queryForList("SELECT step_order FROM sdlc_step ORDER BY step_order", Integer.class));
            } else if (pair.equals("whole/add")) assertNull(parents.findById(9L));
            else assertEquals(pair.equals("delete/reorder") ? List.of(1) : List.of(1, 2), orders());
        } finally { release.countDown(); snapshot.countDown(); pool.shutdownNow(); }
    }

    @Test
    void differentParentsDoNotShareAnApplicationLock() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try {
            run(() -> {
                jdbc.queryForList("SELECT id FROM sdlc WHERE id=9 FOR UPDATE");
                Future<?> other = pool.submit(() -> run(() -> {
                    var req = new CreateStepRequest(); req.setName("independent");
                    service.addStep(10, req, 100, 7);
                }));
                try { other.get(5, TimeUnit.SECONDS); }
                catch (Exception e) { throw new AssertionError(e); }
            });
        } finally { pool.shutdownNow(); }
        assertEquals(1, steps.listBySdlc(10L).size());
    }

    private static void await(CountDownLatch latch) {
        try { if (!latch.await(10, TimeUnit.SECONDS)) throw new AssertionError("transaction barrier timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

}
