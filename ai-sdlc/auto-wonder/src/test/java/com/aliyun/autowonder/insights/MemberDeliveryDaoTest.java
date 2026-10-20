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

    @Test void actualMapperMarksOnlyNullCoalesceFallbackAndKeepsRawNames() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE org_member(user_id BIGINT NOT NULL,tenant_id BIGINT,is_deleted INT,status INT)");
        jdbc.execute("CREATE TABLE `user`(id BIGINT,nickname VARCHAR,username VARCHAR)");
        jdbc.execute("INSERT INTO org_member VALUES(123456789,9,0,0),(2,9,0,0),(3,9,0,0),(4,9,0,0),(5,9,0,0),(6,99,0,0),(7,9,1,0)");
        jdbc.execute("INSERT INTO `user` VALUES(2,'成员 #123456789','raw'),(3,'',''),(4,'<b>成员</b>',NULL),(5,'',NULL)");
        var config=new Configuration(new Environment("test",new JdbcTransactionFactory(),ds));config.setDatabaseId("autowonder-source-aware");
        for(String name:List.of("DashboardDao","MemberDeliveryDao")) try(var stream=Files.newInputStream(Path.of("src/main/resources/mapping/"+name+".xml"))){new XMLMapperBuilder(stream,config,name,config.getSqlFragments()).parse();}
        try(var session=new SqlSessionFactoryBuilder().build(config).openSession()){
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
