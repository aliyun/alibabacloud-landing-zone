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
    @Test void manyPauseCyclesPlateauAndLiveForksRemainProtected() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE dispatch(id BIGINT PRIMARY KEY,tenant_id BIGINT,status VARCHAR(32),executor_id BIGINT,workitem_id BIGINT,agent_id BIGINT,source_type VARCHAR(32),resume_from_dispatch_id BIGINT,delivery_source_dispatch_id BIGINT)");
        jdbc.execute("CREATE TABLE dispatch_recovery(tenant_id BIGINT,dispatch_id BIGINT,cancel_requested INT)");
        jdbc.execute("CREATE TABLE dispatch_result_decision(tenant_id BIGINT,dispatch_id BIGINT,result_id VARCHAR(36),executor_id BIGINT,checkpoint_seq BIGINT,reason_code VARCHAR(64))");
        jdbc.execute("CREATE TABLE dispatch_recovery_checkpoint(id BIGINT PRIMARY KEY,tenant_id BIGINT,workitem_id BIGINT,dispatch_id BIGINT,agent_id BIGINT,checkpoint_seq BIGINT,provider VARCHAR(32),provider_session_id VARCHAR(256),runtime_id VARCHAR(128),executor_id BIGINT,active_step_id VARCHAR(128),oss_ref VARCHAR(512),sha256 VARCHAR(80),size_bytes BIGINT,completion_result_id VARCHAR(36),gc_pending INT DEFAULT 0,gmt_create TIMESTAMP)");
        Configuration config=new Configuration(new Environment("cycles",new JdbcTransactionFactory(),ds));
        String xml;
        try(var stream=getClass().getResourceAsStream("/mapping/DispatchCheckpointDao.xml")) { xml=new String(stream.readAllBytes(),StandardCharsets.UTF_8); }
        new XMLMapperBuilder(new StringReader(xml.replace("&lt;=>","IS NOT DISTINCT FROM")),config,"cycles.xml",config.getSqlFragments()).parse();
        try(var session=new SqlSessionFactoryBuilder().build(config).openSession(true)) {
            var dao=session.getMapper(DispatchCheckpointDao.class);
            var dispatchDao=org.mockito.Mockito.mock(DispatchDao.class);
            org.mockito.Mockito.when(dispatchDao.listBySource(100L,"WORKITEM",200L)).thenAnswer(call->jdbc.query("SELECT * FROM dispatch",(rs,n)->{
                var row=new DispatchDO();row.setId(rs.getLong("id"));row.setTenantId(rs.getLong("tenant_id"));row.setWorkitemId(rs.getLong("workitem_id"));row.setAgentId(rs.getLong("agent_id"));row.setStatus(rs.getString("status"));row.setExecutorId(rs.getLong("executor_id"));
                row.setResumeFromDispatchId(rs.getObject("resume_from_dispatch_id",Long.class));row.setDeliverySourceDispatchId(rs.getObject("delivery_source_dispatch_id",Long.class));return row;
            }));
            var storage=org.mockito.Mockito.mock(com.aliyun.autowonder.storage.ObjectStorage.class);
            var objects=new java.util.HashSet<String>();
            org.mockito.Mockito.when(storage.exists(org.mockito.ArgumentMatchers.anyString())).thenAnswer(call->objects.contains(call.getArgument(0)));
            org.mockito.Mockito.doAnswer(call->{ objects.remove(call.getArgument(0)); return null; }).when(storage).delete(org.mockito.ArgumentMatchers.anyString());
            var store=org.mockito.Mockito.mock(DispatchResultStore.class);
            org.mockito.Mockito.when(store.transaction(org.mockito.ArgumentMatchers.any())).thenAnswer(call->((java.util.function.Supplier<?>)call.getArgument(0)).get());
            var service=new DispatchCheckpointService(dao,org.mockito.Mockito.mock(DispatchRuntimeEventDao.class),dispatchDao,storage,new com.aliyun.autowonder.storage.OssProperties());
            org.springframework.test.util.ReflectionTestUtils.setField(service,"resultStore",store);
            long previous=0;
            for(long round=1;round<=24;round++) {
                long id=round;
                if(previous>0 && round%2==0) jdbc.update("UPDATE dispatch SET status='CANCELED' WHERE id=?",previous);
                jdbc.update("INSERT INTO dispatch VALUES(?,100,'PAUSING',1,200,300,'WORKITEM',?,NULL)",id,previous==0?null:previous);
                var d=new DispatchDO();d.setId(id);d.setTenantId(100L);d.setWorkitemId(200L);d.setAgentId(300L);
                org.mockito.Mockito.when(dispatchDao.findById(id)).thenAnswer(call->jdbc.queryForObject("SELECT * FROM dispatch WHERE id=?",(rs,n)->{
                    var row=new DispatchDO();row.setId(id);row.setTenantId(100L);row.setWorkitemId(200L);row.setAgentId(300L);row.setExecutorId(1L);row.setStatus(rs.getString("status"));return row;
                },id));
                for(long seq=1;seq<=3;seq++) {
                    long row=id*10+seq;String ref="bucket/"+row;objects.add(ref);
                    jdbc.update("INSERT INTO dispatch_recovery_checkpoint(id,tenant_id,workitem_id,dispatch_id,agent_id,checkpoint_seq,executor_id,oss_ref,size_bytes) VALUES(?,100,200,?,300,?,1,?,1048576)",row,id,seq,ref);
                }
                session.clearCache();service.reconcileRetention();session.clearCache();
                if(previous>0) assertNotNull(dao.findByDispatchAndSeq(100L,previous,1L),"running/unconfirmed consumer keeps its source");
                assertEquals(1,jdbc.update("INSERT INTO dispatch_result_decision(tenant_id,dispatch_id,result_id,executor_id,checkpoint_seq,reason_code) VALUES(100,?,?,1,1,'PAUSE_CONFIRMED')",id,"pause-"+id));
                jdbc.update("UPDATE dispatch SET status='PAUSED' WHERE id=?",id);
                if(round==8) {
                    jdbc.update("INSERT INTO dispatch VALUES(999,100,'PENDING',1,200,300,'WORKITEM',?,NULL)",previous);
                    session.clearCache();service.reconcileRetention();session.clearCache();
                    assertNotNull(dao.findByDispatchAndSeq(100L,previous,1L),"a live fork must retain issued checkpoints");
                    assertTrue(objects.contains("bucket/"+(previous*10+1)));
                    jdbc.update("UPDATE dispatch SET status='CANCELED' WHERE id=999");
                }
                session.clearCache();service.reconcileRetention();session.clearCache();
                if(previous>0) assertNull(dao.findByDispatchAndSeq(100L,previous,1L),"superseded pause is collected after all consumers settle");
                assertEquals(3,objects.size(),"retained bytes plateau at 3 MiB across 24 rounds");
                assertEquals(3L*1048576,jdbc.queryForObject("SELECT SUM(size_bytes) FROM dispatch_recovery_checkpoint",Long.class));
                previous=id;
            }
            // A reference arriving after tombstoning must resurrect the row before physical deletion.
            jdbc.update("UPDATE dispatch SET status='CANCELED' WHERE id=24");
            jdbc.update("UPDATE dispatch_recovery_checkpoint SET gc_pending=1 WHERE dispatch_id=24 AND checkpoint_seq=1");
            jdbc.update("INSERT INTO dispatch VALUES(1000,100,'RUNNING',1,200,300,'WORKITEM',24,NULL)");
            session.clearCache();service.reconcileRetention();session.clearCache();
            assertNotNull(dao.findByDispatchAndSeq(100L,24L,1L));
            assertTrue(objects.contains("bucket/241"));
            // An active descendant through a failed checkpoint-less parent still depends on 24.
            jdbc.update("UPDATE dispatch SET status='FAILED' WHERE id=1000");
            jdbc.update("INSERT INTO dispatch VALUES(1001,100,'RUNNING',1,200,300,'WORKITEM',1000,NULL)");
            jdbc.update("INSERT INTO dispatch VALUES(25,100,'PAUSED',1,200,300,'WORKITEM',24,NULL)");
            jdbc.update("INSERT INTO dispatch_recovery_checkpoint(id,tenant_id,workitem_id,dispatch_id,agent_id,checkpoint_seq,executor_id,oss_ref,size_bytes) VALUES(251,100,200,25,300,1,1,'bucket/251',1048576)");
            objects.add("bucket/251");
            jdbc.update("INSERT INTO dispatch_result_decision VALUES(100,25,'pause-25',1,1,'PAUSE_CONFIRMED')");
            session.clearCache();service.reconcileRetention();session.clearCache();
            assertNotNull(dao.findByDispatchAndSeq(100L,24L,1L),"transitive unresolved lineage retains its ancestor");
            jdbc.update("UPDATE dispatch SET status='FAILED' WHERE id=1001");
            objects.remove("bucket/251");
            session.clearCache();service.reconcileRetention();session.clearCache();
            assertNotNull(dao.findByDispatchAndSeq(100L,24L,1L),"a missing replacement object cannot retire the source");
            objects.add("bucket/251");
            session.clearCache();service.reconcileRetention();session.clearCache();
            assertNull(dao.findByDispatchAndSeq(100L,24L,1L),"dead failed branches do not pin ancestors forever");
            assertEquals(java.util.Set.of("bucket/251"),objects);
        }
    }

    @Test void legacyPauseReceiptsSurviveNewerCheckpointsUntilThePauseIsFenced() throws Exception {
        var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
        var jdbc=new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE dispatch(id BIGINT,tenant_id BIGINT,status VARCHAR(32),executor_id BIGINT,workitem_id BIGINT DEFAULT 1,agent_id BIGINT DEFAULT 1,source_type VARCHAR(32) DEFAULT 'WORKITEM',resume_from_dispatch_id BIGINT,delivery_source_dispatch_id BIGINT)");
        jdbc.execute("CREATE TABLE dispatch_recovery(tenant_id BIGINT,dispatch_id BIGINT,cancel_requested INT)");
        jdbc.execute("CREATE TABLE dispatch_result_decision(tenant_id BIGINT,dispatch_id BIGINT,result_id VARCHAR(36),executor_id BIGINT,checkpoint_seq BIGINT,reason_code VARCHAR(64))");
        jdbc.execute("CREATE TABLE dispatch_recovery_checkpoint(id BIGINT PRIMARY KEY,tenant_id BIGINT,workitem_id BIGINT,"
                + "dispatch_id BIGINT,agent_id BIGINT,checkpoint_seq BIGINT,provider VARCHAR(32),provider_session_id VARCHAR(256),"
                + "runtime_id VARCHAR(128),executor_id BIGINT,active_step_id VARCHAR(128),oss_ref VARCHAR(512),sha256 VARCHAR(80),"
                + "size_bytes BIGINT,completion_result_id VARCHAR(36),gc_pending INT DEFAULT 0,gmt_create TIMESTAMP)");
        jdbc.update("INSERT INTO dispatch(id,tenant_id,status,executor_id) VALUES(55,100,'PAUSING',1)");
        jdbc.update("INSERT INTO dispatch_recovery_checkpoint(id,tenant_id,dispatch_id,checkpoint_seq,executor_id) "
                + "VALUES(1,100,55,1,1),(2,100,55,2,1),(3,100,55,3,1)");
        Configuration config=new Configuration(new Environment("pause-retention",new JdbcTransactionFactory(),ds));
        String xml;
        try(var stream=getClass().getResourceAsStream("/mapping/DispatchCheckpointDao.xml")) {
            xml=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        }
        xml=xml.replace("&lt;=>","IS NOT DISTINCT FROM");
        new XMLMapperBuilder(new StringReader(xml),config,"pause-retention.xml",config.getSqlFragments()).parse();
        try(var session=new SqlSessionFactoryBuilder().build(config).openSession(true)) {
            var dao=session.getMapper(DispatchCheckpointDao.class);
            for(String status:new String[]{"PAUSING","PAUSE_FAILED","PAUSED"}) {
                jdbc.update("UPDATE dispatch SET status=?",status);
                session.clearCache();
                assertTrue(dao.listObsolete(100L,55L,2).isEmpty(),status);
                assertTrue(dao.listRetentionSubjects(2,200).isEmpty(),status);
                assertNotNull(dao.findByDispatchAndSeq(100L,55L,1L),status);
            }
            jdbc.update("INSERT INTO dispatch_recovery VALUES(100,55,1)");
            session.clearCache();
            assertEquals(1,dao.listObsolete(100L,55L,2).size(),"cancel fences the old pause");
            jdbc.update("DELETE FROM dispatch_recovery");
            jdbc.update("UPDATE dispatch SET executor_id=2");
            session.clearCache();
            assertEquals(1,dao.listObsolete(100L,55L,2).size(),"old owner no longer pins checkpoints");
            jdbc.update("UPDATE dispatch SET executor_id=1,status='CANCELED'");
            session.clearCache();
            assertEquals(1,dao.listObsolete(100L,55L,2).size(),"terminal execution releases retention");
        }
    }

    @Test void activeCompletionSurvivesAndEveryResolvedLifecycleReleasesItsPin() throws Exception {
        for (String resolution : new String[]{"SUCCEEDED","FAILED","TIMEOUT","CANCELED","OLD_OWNER","CANCEL_REQUESTED","DECIDED"}) {
            var ds=new DriverManagerDataSource("jdbc:h2:mem:"+UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1","sa","");
            var jdbc=new JdbcTemplate(ds);
            jdbc.execute("CREATE TABLE dispatch(id BIGINT,tenant_id BIGINT,status VARCHAR(32),executor_id BIGINT,workitem_id BIGINT DEFAULT 1,agent_id BIGINT DEFAULT 1,source_type VARCHAR(32) DEFAULT 'WORKITEM',resume_from_dispatch_id BIGINT,delivery_source_dispatch_id BIGINT)");
            jdbc.execute("CREATE TABLE dispatch_recovery(tenant_id BIGINT,dispatch_id BIGINT,cancel_requested INT)");
            jdbc.execute("CREATE TABLE dispatch_result_decision(tenant_id BIGINT,dispatch_id BIGINT,result_id VARCHAR(36),executor_id BIGINT,checkpoint_seq BIGINT,reason_code VARCHAR(64))");
            jdbc.execute("CREATE TABLE dispatch_recovery_checkpoint(id BIGINT PRIMARY KEY,tenant_id BIGINT,workitem_id BIGINT,"
                    + "dispatch_id BIGINT,agent_id BIGINT,checkpoint_seq BIGINT,provider VARCHAR(32),provider_session_id VARCHAR(256),"
                    + "runtime_id VARCHAR(128),executor_id BIGINT,active_step_id VARCHAR(128),oss_ref VARCHAR(512),sha256 VARCHAR(80),"
                    + "size_bytes BIGINT,completion_result_id VARCHAR(36),gc_pending INT DEFAULT 0,gmt_create TIMESTAMP)");
            jdbc.update("INSERT INTO dispatch(id,tenant_id,status,executor_id) VALUES(55,100,'RUNNING',1)");
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
                else if (resolution.equals("DECIDED")) jdbc.update("INSERT INTO dispatch_result_decision(tenant_id,dispatch_id,result_id) VALUES(100,55,'result-A')");
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
