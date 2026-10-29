package com.aliyun.autowonder.insights;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class StageDurationDaoTest {
    @Test
    void filtersWorkspaceSourceDateSquadAndHistoricalFlowInBothSchemas() throws Exception {
        for (String schema : List.of("autowonder-source-aware", "autowonder-legacy")) {
            var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
            var jdbc = new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE workitem(id BIGINT,tenant_id BIGINT,title VARCHAR,is_deleted INT)");
            jdbc.execute("CREATE TABLE dispatch(id BIGINT,tenant_id BIGINT,workitem_id BIGINT,source_type VARCHAR,sdlc_step_id BIGINT,"
                    + "agent_version_id BIGINT,agent_id BIGINT,status VARCHAR,gmt_create TIMESTAMP,gmt_modified TIMESTAMP,is_deleted INT)");
            jdbc.execute("CREATE TABLE sdlc_step(id BIGINT,tenant_id BIGINT,sdlc_id BIGINT,is_deleted INT,name VARCHAR)");
            jdbc.execute("CREATE TABLE agent_version(id BIGINT,tenant_id BIGINT,agent_id BIGINT,sdlc_id BIGINT)");
            jdbc.execute("CREATE TABLE sdlc(id BIGINT,tenant_id BIGINT,name VARCHAR)");
            jdbc.execute("CREATE TABLE agent(id BIGINT,tenant_id BIGINT,name VARCHAR)");
            jdbc.execute("CREATE TABLE squad(id BIGINT,tenant_id BIGINT,is_deleted INT)");
            jdbc.execute("CREATE TABLE squad_member(squad_id BIGINT,tenant_id BIGINT,agent_id BIGINT)");
            jdbc.execute("CREATE TABLE dispatch_runtime_event(id BIGINT,tenant_id BIGINT,dispatch_id BIGINT,step_id BIGINT,step_key VARCHAR,"
                    + "step_order INT,step_name VARCHAR,event_type VARCHAR,event_time TIMESTAMP,gmt_create TIMESTAMP)");
            jdbc.execute("INSERT INTO workitem VALUES (100,9,'Original 标题',0),(101,99,'Other workspace',0),(102,9,'Deleted',1)");
            jdbc.execute("INSERT INTO sdlc_step VALUES (10,9,5,1,'Existing stage'),(11,99,6,0,'Other tenant stage')");
            jdbc.execute("INSERT INTO agent_version VALUES (1,9,7,6)");
            jdbc.execute("INSERT INTO sdlc VALUES (5,9,'Historical workflow'),(6,9,'Later workflow')");
            jdbc.execute("INSERT INTO agent VALUES (7,9,'User name')");
            jdbc.execute("INSERT INTO squad VALUES (8,9,0),(9,99,0)");
            jdbc.execute("INSERT INTO squad_member VALUES (8,9,7),(9,99,7)");
            for (int id = 1; id <= 7; id++) {
                long tenant = id == 3 ? 99 : 9, workitem = id == 3 ? 101 : id == 4 ? 102 : 100;
                String source = id == 2 ? "SCHEDULED_TASK_RUN" : "WORKITEM";
                String created = id == 5 ? "2026-01-08 00:00:00" : "2026-01-01 00:00:00";
                jdbc.update("INSERT INTO dispatch VALUES (?,?,?,?,?,?,?,?,?,?,?)", id, tenant, workitem, source,
                        id == 7 ? null : 10, 1, 7, "SUCCEEDED", Timestamp.valueOf(created), Timestamp.valueOf(created), id == 6 ? 1 : 0);
            }
            jdbc.execute("INSERT INTO dispatch_runtime_event VALUES"
                    + "(1,9,1,10,NULL,1,'Recorded stage','step.started',NULL,'2026-01-01 00:00:00'),"
                    + "(2,99,1,10,NULL,1,'Other tenant','step.completed',NULL,'2026-01-01 00:01:00'),"
                    + "(3,9,1,10,NULL,1,'Noise','tool.completed',NULL,'2026-01-01 00:01:00'),"
                    + "(4,9,7,10,NULL,1,NULL,'step.started',NULL,'2026-01-01 00:00:00'),"
                    + "(5,9,7,11,NULL,2,NULL,'step.completed',NULL,'2026-01-01 00:00:01'),"
                    + "(6,9,1,10,NULL,1,NULL,'step.completed',NULL,'2026-01-01 00:00:02')");
            var config = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
            config.setMapUnderscoreToCamelCase(true); config.setDatabaseId(schema);
            for (String name : List.of("DashboardDao", "StageDurationDao")) {
                try (var stream = Files.newInputStream(Path.of("src/main/resources/mapping/" + name + ".xml"))) {
                    new XMLMapperBuilder(stream, config, name, config.getSqlFragments()).parse();
                }
            }
            try (var session = new SqlSessionFactoryBuilder().build(config).openSession()) {
                var dao = session.getMapper(StageDurationDao.class);
                var start = Timestamp.valueOf("2026-01-01 00:00:00"); var end = Timestamp.valueOf("2026-01-08 00:00:00");
                var rows = dao.listExecutions(9, start, end, 8L, 5L, 5001);
                assertEquals(schema.endsWith("source-aware") ? 1 : 2, rows.size());
                assertEquals("Historical workflow", rows.getFirst().getSdlcName());
                assertEquals("Original 标题", rows.getFirst().getTitle());
                assertEquals(0, dao.listExecutions(9, start, end, 9L, null, 5001).size());
                assertEquals(1, dao.listExecutions(9, start, end, 8L, 6L, 5001).size());
                assertEquals(1, dao.listExecutions(9, start, end, null, null, 1).size());
                assertTrue(dao.listEvents(9, List.of()).isEmpty());
                var events = dao.listEvents(9, List.of(1L));
                assertEquals(2, events.size());
                assertEquals("Recorded stage", events.getFirst().getStepName());
                assertNull(events.getFirst().getEventTime());
                assertNotNull(events.getFirst().getGmtCreate());
                assertEquals("Existing stage", events.getLast().getFallbackStepName());
                assertNull(events.getLast().getStepName());
                var both = dao.listEvents(9, List.of(7L, 1L));
                assertEquals(List.of(1L, 6L, 4L, 5L), both.stream().map(StageDurationService.Event::getId).toList());
                assertNull(both.getLast().getFallbackStepName());
            }
        }
    }
}
