package com.aliyun.autowonder.dispatch;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import static org.junit.jupiter.api.Assertions.*;

class DispatchCheckpointRetentionMapperTest {
    @Test void activeCompletionSurvivesAndEveryResolvedLifecycleReleasesItsPin() throws Exception {
        for (String resolution : new String[]{"SUCCEEDED","FAILED","TIMEOUT","CANCELED","OLD_OWNER","CANCEL_REQUESTED","DECIDED"}) {
            var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
            var jdbc=new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE dispatch(id BIGINT,tenant_id BIGINT,status VARCHAR(32),executor_id BIGINT)");
            jdbc.execute("CREATE TABLE dispatch_recovery(tenant_id BIGINT,dispatch_id BIGINT,cancel_requested INT)");
            jdbc.execute("CREATE TABLE dispatch_result_decision(tenant_id BIGINT,dispatch_id BIGINT,result_id VARCHAR(36))");
            jdbc.execute("CREATE TABLE dispatch_recovery_checkpoint(id BIGINT PRIMARY KEY,tenant_id BIGINT,workitem_id BIGINT,"
                    + "dispatch_id BIGINT,agent_id BIGINT,checkpoint_seq BIGINT,provider VARCHAR(32),provider_session_id VARCHAR(256),"
                    + "runtime_id VARCHAR(128),executor_id BIGINT,active_step_id VARCHAR(128),oss_ref VARCHAR(512),sha256 VARCHAR(80),"
                    + "size_bytes BIGINT,completion_result_id VARCHAR(36),gc_pending INT DEFAULT 0,gmt_create TIMESTAMP)");
            jdbc.update("INSERT INTO dispatch VALUES(55,100,'RUNNING',1)");
            jdbc.update("INSERT INTO dispatch_recovery_checkpoint(id,tenant_id,dispatch_id,checkpoint_seq,executor_id,completion_result_id) VALUES(1,100,55,1,1,'result-A')");
            jdbc.update("INSERT INTO dispatch_recovery_checkpoint(id,tenant_id,dispatch_id,checkpoint_seq,executor_id) VALUES(2,100,55,2,1),(3,100,55,3,1)");
            Configuration config=new Configuration(new Environment("retention",new JdbcTransactionFactory(),ds));
            String xml;
            try(var stream=getClass().getResourceAsStream("/mapping/DispatchCheckpointDao.xml")) {
                xml=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
            }
            // Exercise the production mapper predicates; translate only MySQL's null-safe comparison to H2's equivalent.
            xml=xml.replace("&lt;=>","IS NOT DISTINCT FROM");
            new XMLMapperBuilder(new StringReader(xml),config,"retention.xml",config.getSqlFragments()).parse();
            try(var session=new SqlSessionFactoryBuilder().build(config).openSession(true)) {
                var dao=session.getMapper(DispatchCheckpointDao.class);
                assertTrue(dao.listObsolete(100L,55L,2).isEmpty(),resolution+": active A must remain pinned");
                assertTrue(dao.listRetentionSubjects(2,200).isEmpty());
                if (resolution.equals("OLD_OWNER")) jdbc.update("UPDATE dispatch SET executor_id=2");
                else if (resolution.equals("CANCEL_REQUESTED")) jdbc.update("INSERT INTO dispatch_recovery VALUES(100,55,1)");
                else if (resolution.equals("DECIDED")) jdbc.update("INSERT INTO dispatch_result_decision VALUES(100,55,'result-A')");
                else jdbc.update("UPDATE dispatch SET status=?",resolution);
                session.clearCache();
                assertEquals(1,dao.listRetentionSubjects(2,200).size(),resolution);
                assertEquals(1,dao.releaseResolvedPins(100,55),resolution);
                assertEquals(1,dao.listObsolete(100L,55L,2).size(),resolution);
                assertEquals(1,dao.markGarbage(100,55,1));
                assertNull(dao.findByDispatchAndSeq(100L,55L,1L));
                assertEquals(2,dao.listLatestByDispatch(100L,55L,2).size());
                assertEquals(1,dao.listGarbage(200).size());
            }
        }
    }
}
