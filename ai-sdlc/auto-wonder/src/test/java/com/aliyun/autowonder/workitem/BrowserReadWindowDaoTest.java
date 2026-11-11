package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BrowserReadWindowDaoTest {
    @Test
    void windowsAreBoundedScopedAndOrderedForBothSchemas() throws Exception {
        for (String databaseId : java.util.List.of("autowonder-legacy", "autowonder-source-aware")) {
            var ds = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                    "jdbc:h2:mem:browser-window-" + databaseId + ";MODE=MySQL", "sa", "");
            try (var connection = ds.getConnection(); var sql = connection.createStatement()) {
                sql.execute("CREATE TABLE workitem_comment (id BIGINT, tenant_id BIGINT, workitem_id BIGINT, source_type VARCHAR(32), gmt_create TIMESTAMP)");
                sql.execute("CREATE TABLE workitem_event (id BIGINT, tenant_id BIGINT, workitem_id BIGINT, gmt_create TIMESTAMP)");
                for (int id = 1; id <= 130; id++) {
                    sql.execute("INSERT INTO workitem_comment VALUES (" + id + ",7,1,'WORKITEM','2026-11-03 10:00:00')");
                }
                sql.execute("INSERT INTO workitem_event VALUES (999,8,1,'2026-11-03 11:00:00'), (1000,7,2,'2026-11-03 11:00:00'), (130,7,1,'2026-11-03 10:00:00')");
                if (databaseId.endsWith("source-aware")) sql.execute("INSERT INTO workitem_comment VALUES (1001,7,1,'SCHEDULED_TASK_RUN','2026-11-03 11:00:00')");
                sql.execute("CREATE TABLE dispatch_runtime_event (id BIGINT, tenant_id BIGINT, dispatch_id BIGINT, workitem_id BIGINT, agent_id BIGINT, event_id VARCHAR(32), seq BIGINT, event_type VARCHAR(32), step_id VARCHAR(32), step_key VARCHAR(32), step_order INT, step_name VARCHAR(32), message VARCHAR(32), error VARCHAR(32), detail_json VARCHAR(200), event_time TIMESTAMP, gmt_create TIMESTAMP)");
                for (int id = 1; id <= 250; id++) sql.execute("INSERT INTO dispatch_runtime_event (id,tenant_id,dispatch_id,seq) VALUES (" + id + ",7,3,1)");
                sql.execute("INSERT INTO dispatch_runtime_event (id,tenant_id,dispatch_id) VALUES (999,8,3),(1000,7,4)");
                var configuration = new org.apache.ibatis.session.Configuration();
                configuration.setMapUnderscoreToCamelCase(true);
                configuration.setDatabaseId(databaseId);
                configuration.setEnvironment(new org.apache.ibatis.mapping.Environment("test",
                        new org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory(), ds));
                for (String mapper : java.util.List.of("WorkitemEventDao", "DispatchRuntimeEventDao")) {
                    String resource = "mapping/" + mapper + ".xml";
                    try (var input = getClass().getResourceAsStream("/" + resource)) {
                        new org.apache.ibatis.builder.xml.XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
                    }
                }
                try (var session = new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration).openSession()) {
                    var timeline = session.getMapper(WorkitemEventDao.class);
                    var first = timeline.listWindowKeys(7L, 1L, 0);
                    assertEquals(51, first.size());
                    assertEquals("system", first.get(0).getType());
                    assertEquals(130L, first.get(0).getId());
                    assertEquals(81L, timeline.listWindowKeys(7L, 1L, 50).get(0).getId());
                    var logs = session.getMapper(DispatchRuntimeEventDao.class);
                    var tail = logs.listWindow(7, 3, null, 201);
                    assertEquals(201, tail.size());
                    assertEquals(250L, tail.get(0).getId());
                    assertEquals(50L, tail.get(200).getId());
                    var history = logs.listWindow(7, 3, 51L, 201);
                    assertEquals(50, history.size());
                    assertEquals(50L, history.get(0).getId());
                }
            }
        }
    }
}
