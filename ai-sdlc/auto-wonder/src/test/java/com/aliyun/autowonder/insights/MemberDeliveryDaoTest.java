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
    private static final List<String> MAPPERS = List.of("DashboardDao", "WorkitemDao", "MemberDeliveryDao");

    private static Configuration configuration(DriverManagerDataSource ds) throws Exception {
        var config = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        config.setDatabaseId("autowonder-source-aware");
        for (String name : MAPPERS) {
            try (var stream = Files.newInputStream(Path.of("src/main/resources/mapping/" + name + ".xml"))) {
                new XMLMapperBuilder(stream, config, name, config.getSqlFragments()).parse();
            }
        }
        return config;
    }

    @Test void countsByKanbanCategoryWithMutuallyExclusiveStatusColumnsAndCanceledExclusion() throws Exception {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        var jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE workitem(id BIGINT,tenant_id BIGINT,creator_id BIGINT,assignee_type VARCHAR,assignee_ref BIGINT,assign_operator_id BIGINT,work_type VARCHAR,status_node_id BIGINT,gmt_create TIMESTAMP,gmt_modified TIMESTAMP,is_deleted INT)");
        jdbc.execute("CREATE TABLE status_node(id BIGINT,tenant_id BIGINT,category VARCHAR)");
        jdbc.execute("CREATE TABLE dispatch(id BIGINT,tenant_id BIGINT,workitem_id BIGINT,source_type VARCHAR,status VARCHAR,gmt_create TIMESTAMP,gmt_modified TIMESTAMP,is_deleted INT)");
        jdbc.execute("CREATE TABLE workitem_event(tenant_id BIGINT,workitem_id BIGINT,gmt_create TIMESTAMP)");
        jdbc.execute("CREATE TABLE dispatch_request(dispatch_id BIGINT,tenant_id BIGINT,kind VARCHAR,status VARCHAR,expires_at TIMESTAMP)");
        jdbc.execute("INSERT INTO status_node VALUES(1,9,'DONE'),(2,9,'IN_PROGRESS'),(3,9,'INIT'),(4,9,'CANCELED')");
        jdbc.execute("INSERT INTO workitem VALUES"
            + "(10,9,999,'HUMAN',7,NULL,'REQ',1,'2026-01-01','2026-01-06',0),"
            + "(11,9,999,'AGENT',NULL,8,'TASK',2,'2026-01-01','2026-01-06',0),"
            + "(12,99,999,'HUMAN',7,NULL,'REQ',1,'2026-01-01','2026-01-06',0),"
            + "(13,9,999,'HUMAN',7,NULL,'REQ',1,'2026-01-01','2026-01-06',1),"
            + "(14,9,999,'HUMAN',7,NULL,'TASK',3,'2026-01-01','2026-01-01',0),"
            + "(15,9,999,'HUMAN',7,NULL,'TASK',3,'2026-01-01','2026-01-01',0),"
            + "(16,9,999,'HUMAN',7,NULL,'REQ',1,'2025-12-01','2025-12-20',0),"
            + "(17,9,999,'HUMAN',7,NULL,'TASK',4,'2026-01-01','2026-01-06',0),"
            + "(18,9,999,'HUMAN',9,NULL,'TASK',2,'2026-01-01','2026-01-06',0),"
            + "(19,9,999,'AGENT',NULL,11,'TASK',3,'2026-01-01','2026-01-06',0),"
            // 周期内创建、当前未取消：计入 created 与 notStarted。
            + "(20,9,999,'HUMAN',7,NULL,'REQ',3,'2026-01-06','2026-01-06',0),"
            // 周期内创建、当前 CANCELED：集合层剔除，created 也不计。
            + "(21,9,999,'HUMAN',7,NULL,'REQ',4,'2026-01-06','2026-01-06',0),"
            // 周期内创建、已软删除：不计入。
            + "(22,9,999,'HUMAN',7,NULL,'TASK',3,'2026-01-06','2026-01-06',1)");
        jdbc.execute("INSERT INTO workitem_event VALUES(9,14,'2026-01-05'),(9,15,'2026-01-05'),"
            + "(9,16,'2026-01-05'),(9,17,'2026-01-05'),(9,19,'2026-01-05')");
        jdbc.execute("INSERT INTO dispatch VALUES"
            + "(1,9,10,'WORKITEM','SUCCEEDED','2026-01-05','2026-01-06',0),"
            + "(2,9,10,'WORKITEM','SUCCEEDED','2026-01-05','2026-01-06',0),"
            + "(3,9,11,'WORKITEM','RUNNING','2026-01-02','2026-01-06',0),"
            + "(4,9,11,'SCHEDULED_TASK_RUN','SUCCEEDED','2026-01-05','2026-01-06',0),"
            + "(5,9,15,'WORKITEM','SUCCEEDED','2025-12-01','2025-12-20',0),"
            + "(6,9,18,'WORKITEM','RUNNING','2026-01-02','2026-01-06',0),"
            + "(7,9,19,'SCHEDULED_TASK_RUN','SUCCEEDED','2026-01-05','2026-01-06',0)");
        jdbc.execute("INSERT INTO dispatch_request VALUES(6,9,'INPUT','PENDING',NULL)");
        try (var session = new SqlSessionFactoryBuilder().build(configuration(ds)).openSession()) {
            var rows = session.getMapper(MemberDeliveryDao.class).counts(9, java.sql.Timestamp.valueOf("2026-01-05 00:00:00"), java.sql.Timestamp.valueOf("2026-01-07 00:00:00"));
            // 按 (成员归属, 工单类型) 分组，供 Service 分别汇总 summary 与 members 明细。
            assertEquals(5, rows.size());
            var humanReq = group(rows, 7L, "REQ");
            assertEquals(1, humanReq.getCompleted());
            assertEquals(0, humanReq.getInProgress());
            assertEquals(1, humanReq.getNotStarted());
            assertEquals(0, humanReq.getPendingDecision());
            assertEquals(3, humanReq.getTotal());
            assertEquals(1, humanReq.getRequirements());
            // 窗口内创建计入 created；窗口前创建、窗口内有活动的 16 不算新建；取消的 21 与软删除的 22 不进入任何分组。
            assertEquals(1, humanReq.getCreated());
            var humanTask = group(rows, 7L, "TASK");
            assertEquals(0, humanTask.getCompleted());
            assertEquals(0, humanTask.getInProgress());
            assertEquals(1, humanTask.getNotStarted());
            assertEquals(1, humanTask.getPendingDecision());
            assertEquals(2, humanTask.getTotal());
            assertEquals(0, humanTask.getRequirements());
            assertEquals(0, humanTask.getCreated());
            var agentOwner = group(rows, 8L, "TASK");
            assertEquals(1, agentOwner.getInProgress());
            assertEquals(0, agentOwner.getCompleted());
            assertEquals(0, agentOwner.getCreated());
            var pendingHuman = group(rows, 9L, "TASK");
            assertEquals(1, pendingHuman.getPendingDecision());
            assertEquals(0, pendingHuman.getInProgress());
            assertEquals(1, pendingHuman.getTotal());
            assertEquals(0, pendingHuman.getCreated());
            var scheduledOnly = group(rows, 11L, "TASK");
            assertEquals(1, scheduledOnly.getNotStarted());
            assertEquals(0, scheduledOnly.getCompleted());
            assertEquals(1, scheduledOnly.getTotal());
            assertEquals(0, scheduledOnly.getCreated());
            // 全部类型合计：只有窗口内创建的 20 计入 created，且恒有 created <= total。
            var total = rows.stream().mapToLong(MemberDeliveryService.TypeCounts::getTotal).sum();
            var created = rows.stream().mapToLong(MemberDeliveryService.TypeCounts::getCreated).sum();
            assertEquals(1, created);
            assertTrue(created <= total);
        }
    }

    private static MemberDeliveryService.TypeCounts group(List<MemberDeliveryService.TypeCounts> rows, long memberId, String workType) {
        return rows.stream().filter(row -> Long.valueOf(memberId).equals(row.getMemberId()) && workType.equals(row.getWorkType()))
            .findFirst().orElseThrow();
    }

    @Test void actualMapperMarksOnlyNullCoalesceFallbackAndKeepsRawNames() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE org_member(user_id BIGINT NOT NULL,tenant_id BIGINT,is_deleted INT,status INT)");
        jdbc.execute("CREATE TABLE `user`(id BIGINT,nickname VARCHAR,username VARCHAR)");
        jdbc.execute("INSERT INTO org_member VALUES(123456789,9,0,0),(2,9,0,0),(3,9,0,0),(4,9,0,0),(5,9,0,0),(6,99,0,0),(7,9,1,0)");
        jdbc.execute("INSERT INTO `user` VALUES(2,'成员 #123456789','raw'),(3,'',''),(4,'<b>成员</b>',NULL),(5,'',NULL)");
        try(var session=new SqlSessionFactoryBuilder().build(configuration(ds)).openSession()){
            var rows=session.getMapper(MemberDeliveryDao.class).members(9);
            assertEquals(5,rows.size());
            var byId=rows.stream().collect(java.util.stream.Collectors.toMap(MemberDeliveryService.Member::getMemberId,x->x));
            assertEquals("成员 #123456789",byId.get(123456789L).getMemberName());assertEquals("成员 #123456789",byId.get(2L).getMemberName());
            assertEquals("",byId.get(3L).getMemberName());assertEquals("<b>成员</b>",byId.get(4L).getMemberName());assertEquals("成员 #5",byId.get(5L).getMemberName());
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            for(long id:List.of(2L,3L,4L))assertFalse(mapper.valueToTree(byId.get(id)).has("memberNameKind"));
            for(long id:List.of(123456789L,5L))assertEquals("MEMBER_ID_FALLBACK",mapper.valueToTree(byId.get(id)).path("memberNameKind").asText());
        }
    }

}
