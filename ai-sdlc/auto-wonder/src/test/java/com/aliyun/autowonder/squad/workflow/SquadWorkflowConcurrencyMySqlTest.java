package com.aliyun.autowonder.squad.workflow;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.Date;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Real InnoDB pin for the shared snapshot table. The H2-free cases here cover what a mocked DAO
 * cannot: two instances racing to create the same key must converge on one row (the quota gap lock
 * can pick a deadlock victim), submissions must serialize under the row lock, a callback from an
 * older attempt must be rejected untouched, and retention must keep the READY row that still
 * matches the current assembly hash even when a configuration rollback made it older than others.
 */
@Testcontainers(disabledWithoutDocker = true)
class SquadWorkflowConcurrencyMySqlTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("squad_workflow").withUsername("test").withPassword("test");

    private static final String INPUT_JSON = "{\"analysisVersion\":\"squad-workflow-v1\","
            + "\"members\":[{\"agentId\":\"11\",\"steps\":[]}],\"sources\":[]}";
    private static final long CONVERSATION = 7001L;

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static SqlSessionTemplate template;

    @BeforeAll
    static void setUpDatabase() throws Exception {
        dataSource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new EncodedResource(
                    new ByteArrayResource(Files.readAllBytes(Path.of("docs/autowonder-schema.sql")))));
        }
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        SqlSessionFactoryBean factoryBean = new SqlSessionFactoryBean();
        factoryBean.setDataSource(dataSource);
        factoryBean.setConfiguration(configuration);
        factoryBean.setMapperLocations(new ClassPathResource("mapping/SquadWorkflowDao.xml"));
        SqlSessionFactory factory = factoryBean.getObject();
        template = new SqlSessionTemplate(factory);
    }

    @Test
    void concurrentFirstTriggersConvergeOnOneRow() throws Exception {
        long workspaceId = 20001L;
        long squadId = 30001L;
        WorkflowInput input = input(workspaceId, squadId);
        WorkflowInputAssembler assembler = mock(WorkflowInputAssembler.class);
        when(assembler.assemble(workspaceId, squadId)).thenReturn(input);
        SquadWorkflowFinalizer finalizer = mock(SquadWorkflowFinalizer.class);
        when(finalizer.attachConversation(anyLong(), anyLong(), anyString(), anyLong(), anyLong()))
                .thenReturn(true);
        SquadWorkflowConversationBridge bridge = mock(SquadWorkflowConversationBridge.class);
        when(bridge.start(any(), any()))
                .thenReturn(new SquadWorkflowConversationBridge.Attachment(CONVERSATION, 88L));
        WorkflowValidator validator = mock(WorkflowValidator.class);

        SquadWorkflowService first = service(assembler, validator, finalizer, bridge);
        SquadWorkflowService second = service(assembler, validator, finalizer, bridge);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Long> left = pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return first.ensureGeneration(workspaceId, squadId, 9L, null, false).generationId();
            });
            Future<Long> right = pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return second.ensureGeneration(workspaceId, squadId, 9L, null, false).generationId();
            });
            start.countDown();
            assertEquals(left.get(30, TimeUnit.SECONDS), right.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, count("SELECT COUNT(*) FROM squad_workflow_snapshot"
                + " WHERE workspace_id=? AND squad_id=?", workspaceId, squadId));
        verify(bridge, times(1)).start(any(), any());
    }

    @Test
    void concurrentSubmissionsSerializeUnderTheRowLock() throws Exception {
        long workspaceId = 20002L;
        long squadId = 30002L;
        SquadWorkflowDO row = row(workspaceId, squadId, "hash-a", "GENERATING", "attempt-1");
        dao().insert(row);
        WorkflowValidator validator = mock(WorkflowValidator.class);
        when(validator.validateJson(any(), anyString())).thenReturn(new WorkflowFeedback(
                WorkflowFeedback.REJECTED, null,
                List.of(new WorkflowValidationError("X", "/workflow/nodes", "bad", "")), "invalid",
                null));
        SquadWorkflowService service = service(mock(WorkflowInputAssembler.class), validator,
                mock(SquadWorkflowFinalizer.class), mock(SquadWorkflowConversationBridge.class));

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<WorkflowFeedback> first = pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return service.acceptSubmission(workspaceId, CONVERSATION, String.valueOf(row.getId()),
                        "attempt-1", "submission-1", "{\"candidate\":1}");
            });
            Future<WorkflowFeedback> second = pool.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return service.acceptSubmission(workspaceId, CONVERSATION, String.valueOf(row.getId()),
                        "attempt-1", "submission-2", "{\"candidate\":2}");
            });
            start.countDown();
            assertFalse(first.get(30, TimeUnit.SECONDS).accepted());
            assertFalse(second.get(30, TimeUnit.SECONDS).accepted());
        } finally {
            pool.shutdownNow();
        }

        assertEquals(2, count("SELECT submission_count FROM squad_workflow_snapshot WHERE id=?",
                row.getId()));
        assertEquals(2, count("SELECT repair_count FROM squad_workflow_snapshot WHERE id=?",
                row.getId()));
        assertEquals("REPAIRING", text("SELECT status FROM squad_workflow_snapshot WHERE id=?",
                row.getId()));
    }

    @Test
    void aLateSubmissionFromAnOlderAttemptIsRejectedWithoutTouchingTheRow() {
        long workspaceId = 20003L;
        long squadId = 30003L;
        SquadWorkflowDO row = row(workspaceId, squadId, "hash-a", "GENERATING", "attempt-2");
        dao().insert(row);
        SquadWorkflowService service = service(mock(WorkflowInputAssembler.class),
                mock(WorkflowValidator.class), mock(SquadWorkflowFinalizer.class),
                mock(SquadWorkflowConversationBridge.class));

        BizException thrown = assertThrows(BizException.class, () -> service.acceptSubmission(
                workspaceId, CONVERSATION, String.valueOf(row.getId()), "attempt-1", "s1", "{}"));

        assertEquals(ErrorCode.SQUAD_WORKFLOW_ATTEMPT_MISMATCH.getCode(), thrown.getCode());
        assertEquals(0, count("SELECT submission_count FROM squad_workflow_snapshot WHERE id=?",
                row.getId()));
        assertEquals("GENERATING", text("SELECT status FROM squad_workflow_snapshot WHERE id=?",
                row.getId()));
    }

    @Test
    void retentionKeepsTheReadyRowForTheCurrentAssemblyAfterARollback() {
        long workspaceId = 20004L;
        long squadId = 30004L;
        WorkflowInput current = input(workspaceId, squadId);
        SquadWorkflowDO currentRow = row(workspaceId, squadId, current.inputHash(), "READY", "attempt-a");
        dao().insert(currentRow);
        SquadWorkflowDO staleRow = row(workspaceId, squadId, "hash-b", "READY", "attempt-b");
        dao().insert(staleRow);
        SquadWorkflowDO failedRow = row(workspaceId, squadId + 1, "hash-c", "FAILED", "attempt-c");
        dao().insert(failedRow);
        jdbc.update("UPDATE squad_workflow_snapshot SET gmt_modified = DATE_SUB(NOW(3), INTERVAL 30 DAY)"
                + " WHERE id IN (?, ?, ?)", currentRow.getId(), staleRow.getId(), failedRow.getId());

        WorkflowInputAssembler assembler = mock(WorkflowInputAssembler.class);
        when(assembler.assemble(workspaceId, squadId)).thenReturn(current);
        SquadWorkflowService service = service(assembler, mock(WorkflowValidator.class),
                mock(SquadWorkflowFinalizer.class), mock(SquadWorkflowConversationBridge.class));

        service.cleanupExpired();

        assertEquals(1, count("SELECT COUNT(*) FROM squad_workflow_snapshot WHERE id=?",
                currentRow.getId()));
        assertEquals(0, count("SELECT COUNT(*) FROM squad_workflow_snapshot WHERE id=?",
                staleRow.getId()));
        assertEquals(0, count("SELECT COUNT(*) FROM squad_workflow_snapshot WHERE id=?",
                failedRow.getId()));
    }

    private static SquadWorkflowService service(WorkflowInputAssembler assembler,
            WorkflowValidator validator, SquadWorkflowFinalizer finalizer,
            SquadWorkflowConversationBridge bridge) {
        return new SquadWorkflowService(assembler, validator, dao(), finalizer, bridge,
                new DataSourceTransactionManager(dataSource), 2, 600, 7, 30);
    }

    private static SquadWorkflowDao dao() {
        return template.getMapper(SquadWorkflowDao.class);
    }

    private static WorkflowInput input(long workspaceId, long squadId) {
        return new WorkflowInput(workspaceId, squadId, WorkflowInput.ANALYSIS_VERSION, INPUT_JSON,
                WorkflowInputAssembler.sha256Hex(INPUT_JSON),
                List.of(new WorkflowInput.Member("11", "实现者", "开发", "AW_FS_DEV", "88", "7",
                        "交付流程", "REQ", List.of(), List.of())),
                List.of());
    }

    private static SquadWorkflowDO row(long workspaceId, long squadId, String inputHash,
            String status, String attemptId) {
        SquadWorkflowDO row = new SquadWorkflowDO();
        row.setWorkspaceId(workspaceId);
        row.setSquadId(squadId);
        row.setInputHash(inputHash);
        row.setAnalysisVersion(WorkflowInput.ANALYSIS_VERSION);
        row.setStatus(status);
        row.setAttemptId(attemptId);
        row.setConversationId(CONVERSATION);
        row.setRequestedBy(9L);
        row.setInputJson(INPUT_JSON);
        row.setSubmissionCount(0);
        row.setRepairCount(0);
        row.setDeadlineAt(new Date(System.currentTimeMillis() + 120_000L));
        row.setRowVersion(0);
        return row;
    }

    private static int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }

    private static String text(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }
}
