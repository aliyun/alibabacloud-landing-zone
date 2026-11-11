package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.agent.*;
import com.aliyun.autowonder.sdlc.*;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

final class WorkflowSnapshotFixture {
    final JdbcTemplate jdbc;
    final TransactionTemplate tx;
    final DispatchDao dispatches = mock(DispatchDao.class);
    final SdlcStepDao steps = mock(SdlcStepDao.class);
    final AgentDao agents = mock(AgentDao.class);
    final AgentVersionDao versions = mock(AgentVersionDao.class);
    final InMemoryObjectStorage storage = new InMemoryObjectStorage();
    final DispatchWorkflowSnapshotStore snapshots;
    final Map<Long, DispatchDO> rows = new LinkedHashMap<>();
    final SdlcStepDO step = new SdlcStepDO();

    WorkflowSnapshotFixture() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        String sql = Files.readString(Path.of("docs/migration/V111__delivery_workflow_snapshot.sql"))
                .replaceAll("(?m)^--.*$", "").replace("CHARACTER SET ascii COLLATE ascii_bin", "")
                .replace("ENGINE=InnoDB DEFAULT CHARSET=utf8mb4", "");
        for (String statement : sql.split(";")) if (!statement.isBlank()) jdbc.execute(statement);
        jdbc.execute("CREATE TABLE workitem_delivery_restart (tenant_id BIGINT,workitem_id BIGINT,restart_round INT,"
                + "restart_token VARCHAR(64),status VARCHAR(32),PRIMARY KEY(tenant_id,workitem_id,restart_round))");
        jdbc.execute("CREATE TABLE test_control (tenant_id BIGINT,workitem_id BIGINT,PRIMARY KEY(tenant_id,workitem_id))");
        jdbc.update("INSERT INTO test_control VALUES (1,10)");
        when(dispatches.findById(anyLong())).thenAnswer(i -> rows.get(i.getArgument(0)));
        when(dispatches.listByWorkitem(anyLong(), anyLong())).thenAnswer(i -> rows.values().stream()
                .filter(d -> d.getTenantId().equals(i.getArgument(0)) && d.getWorkitemId().equals(i.getArgument(1))).toList());
        step.setId(77L); step.setTenantId(1L); step.setSdlcId(70L); step.setStepOrder(1);
        step.setName("Original entry"); step.setKind("implementation"); step.setInstructionMd("old instruction");
        when(steps.findById(77L)).thenReturn(step);
        when(steps.listBySdlc(70L)).thenReturn(List.of(step));
        AgentDO agent = new AgentDO(); agent.setId(20L); agent.setTenantId(1L); agent.setOnlineVersionId(21L);
        when(agents.findById(20L)).thenReturn(agent);
        AgentVersionDO version = new AgentVersionDO(); version.setId(21L); version.setTenantId(1L); version.setAgentId(20L);
        when(versions.findById(21L)).thenReturn(version);
        snapshots = new DispatchWorkflowSnapshotStore(jdbc, steps, agents, versions, dispatches, storage);
    }

    DispatchDO dispatch(long id, String status) {
        DispatchDO d = new DispatchDO(); d.setId(id); d.setTenantId(1L); d.setWorkitemId(10L);
        d.setAgentId(20L); d.setSdlcStepId(77L); d.setStatus(status); d.setIdempotencyKey("assignment:" + id);
        d.setSourceType("WORKITEM"); d.setVersion(0); rows.put(id, d); return d;
    }

    DispatchWorkflowSnapshotStore.Snapshot freeze(DispatchDO d) {
        var prepared = snapshots.prepare(d);
        snapshots.insert(d, prepared);
        return snapshots.find(d.getTenantId(), d.getId());
    }
}
