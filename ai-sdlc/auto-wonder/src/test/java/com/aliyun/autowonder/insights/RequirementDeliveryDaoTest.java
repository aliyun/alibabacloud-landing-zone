package com.aliyun.autowonder.insights;

import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RequirementDeliveryDaoTest {

    // The mapping returns java.util.Date via MyBatis' DateTypeHandler, so a Timestamp/DATE
    // cross-class equals() always fails; compare both sides as Timestamp instants instead.
    private static void assertLocalTimeEquals(String expectedLocalTime, Date actual) {
        assertNotNull(actual);
        assertEquals(Timestamp.valueOf(expectedLocalTime), new Timestamp(actual.getTime()));
    }

    @Test
    void listsOnlyCurrentlyDoneRequirementsWithLastDoneTransitionAndFirstWorkitemDispatch() throws Exception {
        var ds = new DriverManagerDataSource(
                "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE workitem(id BIGINT,tenant_id BIGINT,template_id BIGINT,title VARCHAR,"
                + "work_type VARCHAR,status_node_id BIGINT,is_deleted INT)");
        jdbc.execute("CREATE TABLE status_node(id BIGINT,tenant_id BIGINT,template_id BIGINT,code VARCHAR,category VARCHAR)");
        jdbc.execute("CREATE TABLE workitem_event(id BIGINT,tenant_id BIGINT,workitem_id BIGINT,event_type VARCHAR,"
                + "from_val VARCHAR,to_val VARCHAR,gmt_create TIMESTAMP)");
        jdbc.execute("CREATE TABLE dispatch(id BIGINT,tenant_id BIGINT,workitem_id BIGINT,source_type VARCHAR,gmt_create TIMESTAMP,is_deleted INT)");
        jdbc.execute("INSERT INTO status_node VALUES"
                + "(101,9,1,'developing','IN_PROGRESS'),(102,9,1,'released','DONE'),(103,9,1,'canceled','CANCELED'),"
                + "(104,9,3,'published','DONE'),(105,9,3,'draft','IN_PROGRESS')");
        jdbc.execute("INSERT INTO workitem VALUES"
                + "(10,9,1,'跨周交付需求','REQ',102,0),"
                + "(11,9,1,'无派发需求','REQ',102,0),"
                + "(12,9,1,'任务不是需求','TASK',102,0),"
                + "(13,9,1,'已重开需求','REQ',101,0),"
                + "(14,99,1,'其他租户需求','REQ',102,0),"
                + "(15,9,1,'已删除需求','REQ',102,1),"
                + "(16,9,1,'区间起点需求','REQ',102,0),"
                + "(17,9,1,'区间终点上需求','REQ',102,0),"
                + "(18,9,3,'跨模板完成码需求','REQ',104,0)");
        jdbc.execute("INSERT INTO workitem_event VALUES"
                + "(1,9,10,'CREATE',NULL,'released','2026-01-07 08:00:00'),"
                + "(2,9,10,'STATUS_CHANGE','developing','released','2026-01-03 08:00:00'),"
                + "(3,9,10,'STATUS_CHANGE','released','developing','2026-01-04 08:00:00'),"
                + "(4,9,10,'STATUS_CHANGE','developing','released','2026-01-05 10:00:00'),"
                + "(5,9,11,'STATUS_CHANGE','developing','released','2026-01-06 10:00:00'),"
                + "(6,9,12,'STATUS_CHANGE','developing','released','2026-01-05 10:00:00'),"
                + "(7,9,13,'STATUS_CHANGE','developing','released','2026-01-04 10:00:00'),"
                + "(8,9,13,'STATUS_CHANGE','released','developing','2026-01-06 10:00:00'),"
                + "(9,99,14,'STATUS_CHANGE','developing','released','2026-01-05 10:00:00'),"
                + "(10,9,15,'STATUS_CHANGE','developing','released','2026-01-05 10:00:00'),"
                + "(11,9,16,'STATUS_CHANGE','developing','released','2026-01-01 00:00:00'),"
                + "(12,9,17,'STATUS_CHANGE','developing','released','2026-01-08 00:00:00'),"
                + "(13,9,18,'STATUS_CHANGE','draft','published','2026-01-04 10:00:00'),"
                + "(14,9,18,'STATUS_CHANGE','published','released','2026-01-06 10:00:00')");
        jdbc.execute("INSERT INTO dispatch VALUES"
                + "(1,9,10,'SCHEDULED_TASK_RUN','2026-01-01 09:00:00',0),"
                + "(2,9,10,'WORKITEM','2026-01-02 09:00:00',0),"
                + "(3,9,10,'WORKITEM','2026-01-03 09:00:00',1),"
                + "(4,9,11,'SCHEDULED_TASK_RUN','2026-01-01 09:00:00',0),"
                + "(5,9,16,'SCHEDULED_TASK_RUN','2025-12-28 09:00:00',0),"
                + "(6,9,16,'WORKITEM','2025-12-30 09:00:00',0)");

        var config = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        config.setDatabaseId("autowonder-source-aware");
        for (String name : List.of("DashboardDao", "RequirementDeliveryDao")) {
            try (var stream = Files.newInputStream(Path.of("src/main/resources/mapping/" + name + ".xml"))) {
                new XMLMapperBuilder(stream, config, name, config.getSqlFragments()).parse();
            }
        }
        try (var session = new SqlSessionFactoryBuilder().build(config).openSession()) {
            var rows = session.getMapper(RequirementDeliveryDao.class).listDeliveredRequirements(
                    9L, Timestamp.valueOf("2026-01-01 00:00:00"), Timestamp.valueOf("2026-01-08 00:00:00"));
            assertEquals(4, rows.size());
            assertEquals(List.of(16L, 18L, 10L, 11L),
                    rows.stream().map(RequirementDeliveryService.DeliveredRequirement::getWorkitemId).toList());
            var atStart = rows.get(0);
            assertEquals("区间起点需求", atStart.getTitle());
            assertLocalTimeEquals("2026-01-01 00:00:00", atStart.getDeliveredAt());
            assertLocalTimeEquals("2025-12-30 09:00:00", atStart.getDeliveryStartedAt());
            var crossTemplate = rows.get(1);
            assertLocalTimeEquals("2026-01-04 10:00:00", crossTemplate.getDeliveredAt());
            var reopened = rows.get(2);
            assertLocalTimeEquals("2026-01-05 10:00:00", reopened.getDeliveredAt());
            assertLocalTimeEquals("2026-01-02 09:00:00", reopened.getDeliveryStartedAt());
            var noWorkitemDispatch = rows.get(3);
            assertNull(noWorkitemDispatch.getDeliveryStartedAt());
        }
    }
}
