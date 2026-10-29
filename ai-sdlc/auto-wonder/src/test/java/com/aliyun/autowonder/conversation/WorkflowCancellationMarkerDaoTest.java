package com.aliyun.autowonder.conversation;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WorkflowCancellationMarkerDaoTest {
    @Test
    void persistsMarkerPreservingExistingContextAndFencesConcurrentChanges() throws Exception {
        var dataSource = new UnpooledDataSource("org.h2.Driver",
                "jdbc:h2:mem:workflow_cancel_marker;MODE=MySQL", "sa", "");
        var config = new Configuration(new Environment("test", new JdbcTransactionFactory(), dataSource));
        String resource = "mapping/AgentConversationTurnDao.xml";
        try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
        }
        try (var session = new SqlSessionFactoryBuilder().build(config).openSession(true)) {
            var connection = session.getConnection();
            try (var st = connection.createStatement()) {
                st.execute("CREATE TABLE agent_conversation_turn (id BIGINT, tenant_id BIGINT, direction VARCHAR(8), status VARCHAR(32), source_context VARCHAR(4000))");
                st.execute("INSERT INTO agent_conversation_turn VALUES (1, 7, 'IN', 'PROCESSING', NULL)");
            }
            var dao = session.getMapper(AgentConversationTurnDao.class);
            String prior = "{\"origin\":\"workflow\"}";
            String marked = "{\"origin\":\"workflow\",\"workflowCancelSentAt\":123}";
            assertEquals(1, dao.updateProcessingSourceContext(7L, 1L, null, prior));
            assertEquals(1, dao.updateProcessingSourceContext(7L, 1L, prior, marked));
            assertEquals(0, dao.updateProcessingSourceContext(7L, 1L, prior, "{}"));
            assertEquals(0, dao.updateProcessingSourceContext(8L, 1L, marked, "{}"));
            try (var st = connection.createStatement()) {
                try (var rs = st.executeQuery("SELECT source_context FROM agent_conversation_turn WHERE id=1")) {
                    assertTrue(rs.next());
                    assertEquals(marked, rs.getString(1));
                }
                st.execute("UPDATE agent_conversation_turn SET status='CANCELED' WHERE id=1");
            }
            assertEquals(0, dao.updateProcessingSourceContext(7L, 1L, marked, "{}"));
        }
    }
}
