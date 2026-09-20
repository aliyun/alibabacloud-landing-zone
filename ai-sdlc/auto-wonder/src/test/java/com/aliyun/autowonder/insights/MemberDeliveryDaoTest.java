package com.aliyun.autowonder.insights;

import org.junit.jupiter.api.Test;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MemberDeliveryDaoTest {
    @Test void countsDistinctWorkitemsByHumanOwnerWithWorkspaceAndSourceFences() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE workitem(id BIGINT,tenant_id BIGINT,creator_id BIGINT,assignee_type VARCHAR,assignee_ref BIGINT,assign_operator_id BIGINT,work_type VARCHAR,status_node_id BIGINT,gmt_create TIMESTAMP,gmt_modified TIMESTAMP,is_deleted INT)");
        jdbc.execute("CREATE TABLE status_node(id BIGINT,tenant_id BIGINT,category VARCHAR)");
        jdbc.execute("CREATE TABLE dispatch(id BIGINT,tenant_id BIGINT,workitem_id BIGINT,source_type VARCHAR,status VARCHAR,gmt_create TIMESTAMP,gmt_modified TIMESTAMP,is_deleted INT)");
        jdbc.execute("CREATE TABLE workitem_event(tenant_id BIGINT,workitem_id BIGINT,gmt_create TIMESTAMP)");
        jdbc.execute("INSERT INTO status_node VALUES(1,9,'DONE'),(2,9,'IN_PROGRESS')");
        jdbc.execute("INSERT INTO workitem VALUES(10,9,999,'HUMAN',7,NULL,'REQ',1,'2026-01-01','2026-01-06',0),(11,9,999,'AGENT',100,8,'TASK',2,'2026-01-01','2026-01-06',0),(12,99,999,'HUMAN',7,NULL,'REQ',1,'2026-01-01','2026-01-06',0),(13,9,999,'HUMAN',7,NULL,'REQ',1,'2026-01-01','2026-01-06',1)");
        jdbc.execute("INSERT INTO dispatch VALUES(1,9,10,'WORKITEM','SUCCEEDED','2026-01-05','2026-01-06',0),(2,9,10,'WORKITEM','SUCCEEDED','2026-01-05','2026-01-06',0),(3,9,11,'WORKITEM','RUNNING','2026-01-05','2026-01-06',0),(4,9,11,'SCHEDULED_TASK_RUN','SUCCEEDED','2026-01-05','2026-01-06',0)");
        var config = new Configuration(new Environment("test",new JdbcTransactionFactory(),ds));
        config.setDatabaseId("autowonder-source-aware");
        for (String name : List.of("DashboardDao", "MemberDeliveryDao")) {
            try (var stream = Files.newInputStream(Path.of("src/main/resources/mapping/"+name+".xml"))) {
                new XMLMapperBuilder(stream, config, name, config.getSqlFragments()).parse();
            }
        }
        try (var session = new SqlSessionFactoryBuilder().build(config).openSession()) {
            var rows = session.getMapper(MemberDeliveryDao.class).counts(9, java.sql.Timestamp.valueOf("2026-01-05 00:00:00"), java.sql.Timestamp.valueOf("2026-01-07 00:00:00"));
            assertEquals(2, rows.size());
            var owner = rows.stream().filter(r -> Long.valueOf(7).equals(r.getMemberId())).findFirst().orElseThrow();
            assertEquals(1,owner.getTotal()); assertEquals(1,owner.getCompleted()); assertEquals(1,owner.getRequirements());
            var agentOwner = rows.stream().filter(r -> Long.valueOf(8).equals(r.getMemberId())).findFirst().orElseThrow();
            assertEquals(1,agentOwner.getInProgress()); assertEquals(0,agentOwner.getCompleted());
        }
    }
}
