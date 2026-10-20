package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.json.JSONObject;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.guidance.InteractionWorkflowService;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchResultTransactionTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failureBetweenTerminalWriteAndHandoffRollsBackAndRetryCreatesExactlyOneChild(boolean legacy) {
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE dispatch(id BIGINT PRIMARY KEY, tenant_id BIGINT, status VARCHAR(32))");
        jdbc.execute("CREATE TABLE workitem(id BIGINT PRIMARY KEY, tenant_id BIGINT)");
        jdbc.execute("CREATE TABLE workitem_execution_control(tenant_id BIGINT,workitem_id BIGINT,closed INT DEFAULT 0,PRIMARY KEY(tenant_id,workitem_id))");
        jdbc.execute("CREATE TABLE qa(source_id BIGINT PRIMARY KEY,id BIGINT)");
        jdbc.execute("CREATE TABLE dispatch_result_decision(tenant_id BIGINT, dispatch_id BIGINT,result_id VARCHAR(36),"
                + "executor_id BIGINT,payload_sha256 VARCHAR(64),checkpoint_seq BIGINT,checkpoint_sha256 VARCHAR(80),"
                + "failure_category VARCHAR(128),disposition VARCHAR(16) NOT NULL,reason_code VARCHAR(64),ack_json VARCHAR(8000),handoff_json VARCHAR(8000),"
                + "PRIMARY KEY(tenant_id,dispatch_id,result_id))");
        jdbc.update("INSERT INTO dispatch VALUES(55,100,'RUNNING')");
        jdbc.update("INSERT INTO workitem VALUES(200,100)");
        DispatchResultStore store = spy(new DispatchResultStore(jdbc, new DataSourceTransactionManager(dataSource)));
        doNothing().when(store).requireReady(); // This test exercises commit/rollback, not MySQL metadata probing.
        DispatchDao dao = mock(DispatchDao.class);
        when(dao.findById(55L)).thenAnswer(call -> {
            DispatchDO row = new DispatchDO(); row.setId(55L); row.setTenantId(100L);
            row.setWorkitemId(200L); row.setExecutorId(1L);
            row.setStatus(jdbc.queryForObject("SELECT status FROM dispatch WHERE id=55",String.class));
            return row;
        });
        DispatchService dispatches = mock(DispatchService.class);
        when(dispatches.onResult(100L,1L,55,true,null,null,false,true,null)).thenAnswer(call -> {
            jdbc.update("UPDATE dispatch SET status='SUCCEEDED' WHERE id=55");
            return true;
        });
        when(dispatches.mayRouteHandoff(100,1,55)).thenReturn(true);
        DispatchCheckpointService checkpoints = mock(DispatchCheckpointService.class);
        when(checkpoints.matchesCompletionReceipt(eq(100L),eq(55L),anyString(),eq(7L),eq("sha256:abc"))).thenReturn(true);
        HandoffService handoffs = mock(HandoffService.class);
        when(handoffs.handle(100,200,55,"qa","AGENT")).thenAnswer(call -> {
            jdbc.update("INSERT INTO qa VALUES(55,56)");
            throw new IllegalStateException("crash after QA insert before decision");
        });
        var coordinator = new DispatchResultCoordinator(store,dao,dispatches,checkpoints,
                mock(DispatchPauseService.class),mock(DispatchRecoveryService.class),handoffs,
                mock(GuidanceService.class),mock(InteractionWorkflowService.class),mock(ScheduledTaskCapabilityGuard.class));
        JSONObject input=JSON.parseObject("{\"type\":\"TASK_RESULT\",\"resultProtocolVersion\":2,"
                + "\"resultId\":\"b55aa1a7-43d5-4452-b39a-278554b67942\",\"dispatchId\":55,\"success\":true,"
                + "\"checkpointSeq\":7,\"checkpointSha256\":\"sha256:abc\",\"handoff\":{\"to\":\"qa\",\"toType\":\"AGENT\"}}");
        if (legacy) { input.remove("resultProtocolVersion"); input.remove("resultId"); }
        assertTrue(coordinator.handle(100,1,input).ack().getBooleanValue("retryable"));
        assertEquals("RUNNING",jdbc.queryForObject("SELECT status FROM dispatch WHERE id=55",String.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM qa",Integer.class));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_result_decision",Integer.class));
        doAnswer(call -> { jdbc.update("INSERT INTO qa VALUES(55,56)"); return HandoffResult.agent(9,56); })
                .when(handoffs).handle(100,200,55,"qa","AGENT");
        JSONObject accepted=coordinator.handle(100,1,input).ack();
        assertTrue(accepted.getBooleanValue("accepted"));
        assertEquals("ACCEPTED", jdbc.queryForObject("SELECT disposition FROM dispatch_result_decision", String.class));
        assertEquals("SUCCEEDED",jdbc.queryForObject("SELECT status FROM dispatch WHERE id=55",String.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qa",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM dispatch_result_decision",Integer.class));
        reset(checkpoints,dispatches,handoffs);
        assertEquals(JSON.parseObject(accepted.toJSONString()),coordinator.handle(100,1,input).ack());
        verifyNoInteractions(checkpoints,dispatches,handoffs);
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM qa",Integer.class));
    }

    @Test void failoverBudgetCountsOnlyCommittedUniqueDecisions() {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE dispatch(id BIGINT PRIMARY KEY,tenant_id BIGINT,status VARCHAR(32))");
        jdbc.execute("CREATE TABLE workitem(id BIGINT PRIMARY KEY,tenant_id BIGINT)");
        jdbc.execute("CREATE TABLE workitem_execution_control(tenant_id BIGINT,workitem_id BIGINT,closed INT DEFAULT 0,PRIMARY KEY(tenant_id,workitem_id))");
        jdbc.execute("CREATE TABLE dispatch_result_decision(tenant_id BIGINT,dispatch_id BIGINT,result_id VARCHAR(36),executor_id BIGINT,"
                + "payload_sha256 VARCHAR(64),checkpoint_seq BIGINT,checkpoint_sha256 VARCHAR(80),failure_category VARCHAR(128),"
                + "disposition VARCHAR(16) NOT NULL,reason_code VARCHAR(64),ack_json VARCHAR(8000),handoff_json VARCHAR(8000),PRIMARY KEY(tenant_id,dispatch_id,result_id))");
        jdbc.update("INSERT INTO dispatch VALUES(55,100,'RUNNING')");jdbc.update("INSERT INTO workitem VALUES(200,100)");
        var store=spy(new DispatchResultStore(jdbc,new DataSourceTransactionManager(ds)));doNothing().when(store).requireReady();
        var dao=mock(DispatchDao.class);DispatchDO row=new DispatchDO();row.setId(55L);row.setTenantId(100L);
        row.setWorkitemId(200L);row.setExecutorId(1L);row.setStatus("RUNNING");when(dao.findById(55L)).thenReturn(row);
        var dispatches=mock(DispatchService.class);
        when(dispatches.onExecutorUnavailableResult(100,1,55,"runtime_recovery",null,0)).thenAnswer(call->{
            jdbc.update("UPDATE dispatch SET status='PENDING' WHERE id=55");return true;
        });
        var guidance=mock(GuidanceService.class);
        doThrow(new IllegalStateException("fail after requeue before decision")).doNothing().when(guidance).requeueForExecutorFailover(100,55);
        var coordinator=new DispatchResultCoordinator(store,dao,dispatches,mock(DispatchCheckpointService.class),
                mock(DispatchPauseService.class),mock(DispatchRecoveryService.class),mock(HandoffService.class),guidance,
                mock(InteractionWorkflowService.class),mock(ScheduledTaskCapabilityGuard.class));
        JSONObject input=JSON.parseObject("{\"resultProtocolVersion\":2,\"resultId\":\"b55aa1a7-43d5-4452-b39a-278554b67942\","
                + "\"dispatchId\":55,\"success\":false,\"failureCategory\":\"runtime_recovery\"}");
        assertEquals("RETRYABLE",coordinator.handle(100,1,input).ack().getString("disposition"));
        assertEquals(0,store.countSessionRecoveryDecisions(100,55));
        assertEquals("RUNNING",jdbc.queryForObject("SELECT status FROM dispatch WHERE id=55",String.class));
        assertEquals("ACCEPTED",coordinator.handle(100,1,input).ack().getString("disposition"));
        assertEquals(1,store.countSessionRecoveryDecisions(100,55));
        assertEquals("ACCEPTED",coordinator.handle(100,1,input).ack().getString("disposition"));
        assertEquals(1,store.countSessionRecoveryDecisions(100,55));
        verify(dispatches,times(2)).onExecutorUnavailableResult(100,1,55,"runtime_recovery",null,0);
        verify(dispatches,never()).onExecutorUnavailableResult(100,1,55,"runtime_recovery",null,1);
    }

    @Test void deferredSchedulingRunsOnlyAfterCommitAndScopeIsClearedOnRollback() {
        var dataSource=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";DB_CLOSE_DELAY=-1","sa","");
        JdbcTemplate jdbc=new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE done(id INT)");
        var tx=new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(dataSource));
        try (var scope=ResultCommitActions.open()) {
            tx.executeWithoutResult(status -> {
                jdbc.update("INSERT INTO done VALUES(1)");
                assertTrue(ResultCommitActions.defer("deliver",()-> {
                    assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
                    jdbc.update("INSERT INTO done VALUES(2)");
                }));
                assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM done",Integer.class));
            });
            scope.committed();
        }
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM done",Integer.class));
        assertThrows(IllegalStateException.class,()-> {
            try (var scope=ResultCommitActions.open()) {
                tx.executeWithoutResult(status -> {
                    assertTrue(ResultCommitActions.defer("never",()->fail("must not run")));
                    throw new IllegalStateException("rollback");
                });
            }
        });
        assertFalse(ResultCommitActions.defer("outside",()->fail("must not run")));
    }
}
