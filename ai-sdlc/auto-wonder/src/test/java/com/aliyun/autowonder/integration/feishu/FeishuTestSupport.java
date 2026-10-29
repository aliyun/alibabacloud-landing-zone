package com.aliyun.autowonder.integration.feishu;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.security.crypto.SecretCrypto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import java.util.UUID;
import static org.mockito.Mockito.*;

class FeishuTestSupport {
    final ObjectMapper json = new ObjectMapper();
    final JdbcTemplate jdbc;
    final FeishuBindingDao dao;
    final AgentDao agents = mock(AgentDao.class);
    final SecretCrypto keys = mock(SecretCrypto.class);
    final FeishuBindingService service;
    FeishuTestSupport() {
        var ds = new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        new ResourceDatabasePopulator(new FileSystemResource("docs/migration/V060__feishu_agent_conversation.sql")).execute(ds);
        jdbc = new JdbcTemplate(ds);
        upgradeSchema(jdbc);
        dao = new FeishuBindingDao(jdbc);
        when(keys.encrypt(anyString())).thenAnswer(i -> "enc:" + i.getArgument(0));
        when(keys.decrypt(anyString())).thenAnswer(i -> i.<String>getArgument(0).substring(4));
        var agent = new AgentDO(); agent.setId(7L); agent.setTenantId(1L); agent.setIsDeleted(0);
        when(agents.findById(7L)).thenReturn(agent);
        service = new FeishuBindingService(dao, keys, agents, json);
    }

    /** Applies the V070 shared-binding shape so the fixture matches production DAO SQL. */
    private static void upgradeSchema(JdbcTemplate jdbc) {
        jdbc.queryForList("SELECT CONSTRAINT_NAME FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS "
                        + "WHERE TABLE_NAME='feishu_robot_binding' AND CONSTRAINT_TYPE='UNIQUE'", String.class)
                .forEach(name -> jdbc.execute(
                        "ALTER TABLE feishu_robot_binding DROP CONSTRAINT \"" + name + "\""));
        jdbc.execute("ALTER TABLE feishu_robot_binding ADD COLUMN binding_mode VARCHAR(16) NOT NULL DEFAULT 'LEGACY'");
        jdbc.execute("ALTER TABLE feishu_robot_binding ADD COLUMN platform_config_id BIGINT");
        jdbc.execute("ALTER TABLE feishu_robot_binding ADD COLUMN group_id VARCHAR(256)");
        jdbc.execute("ALTER TABLE feishu_robot_binding ADD COLUMN route_version BIGINT NOT NULL DEFAULT 0");
        jdbc.execute("ALTER TABLE feishu_robot_binding ALTER COLUMN credential_ref SET NULL");
        jdbc.execute("ALTER TABLE feishu_robot_binding ADD COLUMN legacy_identity VARCHAR(128) "
                + "GENERATED ALWAYS AS (CASE WHEN binding_mode='LEGACY' THEN app_id ELSE NULL END)");
        jdbc.execute("ALTER TABLE feishu_robot_binding ADD CONSTRAINT uk_feishu_legacy_identity "
                + "UNIQUE (legacy_identity)");
        jdbc.execute("ALTER TABLE feishu_robot_binding ADD CONSTRAINT uk_feishu_shared_group "
                + "UNIQUE (platform_config_id, group_id)");
        jdbc.execute("ALTER TABLE feishu_message_inbox ADD COLUMN receipt_context CLOB");
        jdbc.execute("ALTER TABLE feishu_message_inbox ADD COLUMN platform_config_id BIGINT");
        jdbc.execute("ALTER TABLE feishu_message_inbox ADD COLUMN group_id VARCHAR(128)");
        jdbc.execute("ALTER TABLE feishu_message_inbox ADD CONSTRAINT uk_feishu_shared_receipt "
                + "UNIQUE (platform_config_id, group_id, message_id)");
    }
    FeishuBinding create() {
        return service.save(1L, 9L, null, new FeishuBindingService.Request("cli_test", "secret", "verify-token", null, false, 7L, "ENABLED", null));
    }
    com.fasterxml.jackson.databind.JsonNode event(String id, String type, String mentions) throws Exception {
        var event = json.createObjectNode();
        var sender = event.putObject("sender");
        sender.put("sender_type", "user"); sender.putObject("sender_id").put("open_id", "ou_user");
        var msg = event.putObject("message");
        msg.put("message_id", id); msg.put("chat_id", "oc_chat"); msg.put("chat_type", type);
        msg.put("message_type", "text");
        msg.put("content", json.writeValueAsString(java.util.Map.of("text", "@_user_1 hello")));
        msg.set("mentions", json.readTree(mentions));
        return event;
    }
}
