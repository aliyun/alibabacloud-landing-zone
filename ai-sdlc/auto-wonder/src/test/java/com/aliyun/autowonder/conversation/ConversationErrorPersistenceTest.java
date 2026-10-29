package com.aliyun.autowonder.conversation;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ConversationErrorPersistenceTest {
    @Test
    void longErrorDoesNotRollbackTerminalAckOrOutboundReply() throws Exception {
        var ds = new UnpooledDataSource("org.h2.Driver", "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL", "sa", "");
        var config = new Configuration(new Environment("test", new JdbcTransactionFactory(), ds));
        String resource = "mapping/AgentConversationTurnDao.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        try (var session = new SqlSessionFactoryBuilder().build(config).openSession(false)) {
            var connection = session.getConnection();
            try (var st = connection.createStatement()) {
                st.execute("CREATE TABLE agent_conversation_turn (id BIGINT AUTO_INCREMENT PRIMARY KEY, tenant_id BIGINT, conversation_id BIGINT, direction VARCHAR(8), content CLOB, external_msg_id VARCHAR(256), request_id VARCHAR(128), source_context CLOB, submitted_user_id BIGINT, dispatch_attempt INT DEFAULT 1, status VARCHAR(16), error VARCHAR(1024), source_inbound_turn_id BIGINT, delivery_status VARCHAR(16), delivery_attempt INT, delivery_available_at TIMESTAMP, delivery_error CLOB)");
                st.execute("INSERT INTO agent_conversation_turn (id,tenant_id,conversation_id,direction,status) VALUES (1,7,10,'IN','PROCESSING')");
            }
            session.commit();
            var dao = session.getMapper(AgentConversationTurnDao.class);
            String error = "provider rejected: " + "错误信息".repeat(700);
            assertEquals(0, dao.updateInboundStatusIfProcessingAttempt(7L,10L,1L,"FAILED",error,2));
            assertEquals(1, dao.updateInboundStatusIfProcessingAttempt(7L,10L,1L,"FAILED",error,1));
            var out = new AgentConversationTurnDO();
            out.setTenantId(7L);out.setConversationId(10L);out.setDirection("OUT");out.setStatus("FAILED");
            out.setError(error);out.setContent(error);out.setSubmittedUserId(7001L);
            dao.insert(out);
            session.commit();
            try (var st = connection.createStatement();var rs=st.executeQuery("SELECT status,error,content,submitted_user_id FROM agent_conversation_turn ORDER BY id")) {
                assertTrue(rs.next());assertEquals("FAILED",rs.getString(1));assertEquals(error.substring(0,1024),rs.getString(2));
                assertTrue(rs.next());assertEquals("FAILED",rs.getString(1));assertEquals(error.substring(0,1024),rs.getString(2));assertEquals(error,rs.getString(3));assertEquals(7001L,rs.getLong(4));
            }
            assertEquals(0,dao.updateInboundStatusIfProcessing(7L,10L,1L,"FAILED",error));
            assertEquals(0,dao.updateInboundStatusIfProcessing(8L,10L,1L,"FAILED",error));
            // All other business-error writers share the same database boundary.
            assertEquals(1,dao.updateStatusIfCurrent(7L,1L,"FAILED","FAILED",error));
            assertEquals(1,dao.updateStatusAndContextIfCurrent(7L,1L,"FAILED","FAILED",error,null));
        }
    }
}
