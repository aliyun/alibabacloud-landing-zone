package com.aliyun.autowonder.integration;

import com.aliyun.autowonder.conversation.AgentConversationTurnDO;
import com.aliyun.autowonder.conversation.AgentConversationTurnDao;
import com.aliyun.autowonder.im.ImChannelAdmissionService;
import com.aliyun.autowonder.im.ImDeliveryFence;
import com.aliyun.autowonder.im.ImRouteSnapshot;
import com.aliyun.autowonder.im.PlatformImChannelConfigDao;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 在真实 MySQL 上验证 V070 的升级语义与运行时约束：旧库数据保留且可重复执行、
 * LEGACY/SHARED 与用途隔离约束、渠道切换与准入的行锁边界、以及 IM 回复持久投递的
 * 领取/租约/UNKNOWN 状态机。
 */
@Testcontainers(disabledWithoutDocker = true)
class SharedGroupChannelMySqlTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("shared_group_channels").withUsername("test").withPassword("test");

    /**
     * Delivery clocks are compared against the server's {@code CURRENT_TIMESTAMP(3)}, which runs
     * in the container's UTC zone, so the driver must bind DATETIME literals as UTC too. With the
     * default {@code connectionTimeZone=LOCAL} a +08 host would write lease instants eight hours
     * ahead of the server clock.
     */
    private static final String UTC_DATETIME_BINDING = "?connectionTimeZone=UTC&preserveInstants=true";

    static DataSource dataSource;
    static JdbcTemplate jdbc;
    static SqlSessionFactory factory;

    @BeforeAll
    static void setup() throws Exception {
        dataSource = new DriverManagerDataSource(
                MYSQL.getJdbcUrl() + UTC_DATETIME_BINDING,
                MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        createLegacySchema();
        seedLegacyRows();
        factory = buildSqlSessionFactory();
        runMigration();
        seedGroupChatConfigs();
    }

    @BeforeEach
    void resetSelection() {
        jdbc.update("UPDATE platform_im_selection SET provider = 'DINGTALK', generation = 0, "
                + "activated_at = NULL WHERE id = 1");
    }

    @Test
    void migrationPreservesLegacyRowsAndIsIdempotent() throws Exception {
        runMigration();

        Map<String, Object> dingtalk = jdbc.queryForMap(
                "SELECT binding_mode, credential_ref, platform_config_id, group_id, route_version, "
                        + "confirmed_at, legacy_identity FROM dingtalk_robot_binding WHERE id = 7");
        assertEquals("LEGACY", dingtalk.get("binding_mode"));
        assertEquals("cipher-ding", dingtalk.get("credential_ref"));
        assertNull(dingtalk.get("platform_config_id"));
        assertNull(dingtalk.get("group_id"));
        assertEquals(0L, ((Number) dingtalk.get("route_version")).longValue());
        assertNull(dingtalk.get("confirmed_at"));
        assertEquals("robot-legacy", dingtalk.get("legacy_identity"));

        Map<String, Object> feishu = jdbc.queryForMap(
                "SELECT binding_mode, credential_ref, legacy_identity FROM feishu_robot_binding WHERE id = 8");
        assertEquals("LEGACY", feishu.get("binding_mode"));
        assertEquals("{\"secret\":\"cipher\"}", feishu.get("credential_ref"));
        assertEquals("app-legacy", feishu.get("legacy_identity"));

        Map<String, Object> notify = jdbc.queryForMap(
                "SELECT purpose, credential_ref, route_version FROM platform_im_channel_config WHERE id = 50");
        assertEquals("NOTIFY", notify.get("purpose"));
        assertEquals("cipher-notify", notify.get("credential_ref"));
        assertEquals(0L, ((Number) notify.get("route_version")).longValue());
        assertEquals("text", jdbc.queryForObject(
                "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                        + "AND TABLE_NAME = 'platform_im_channel_config' AND COLUMN_NAME = 'credential_ref'",
                String.class));

        assertNull(jdbc.queryForObject(
                "SELECT receipt_context FROM feishu_message_inbox WHERE id = 9", String.class));
        assertNull(jdbc.queryForObject(
                "SELECT platform_config_id FROM feishu_message_inbox WHERE id = 9", Long.class));
        assertNull(jdbc.queryForObject(
                "SELECT group_id FROM feishu_message_inbox WHERE id = 9", String.class));

        Map<String, Object> turn = jdbc.queryForMap(
                "SELECT source_inbound_turn_id, delivery_status, delivery_attempt "
                        + "FROM agent_conversation_turn WHERE id = 10");
        assertNull(turn.get("source_inbound_turn_id"));
        assertNull(turn.get("delivery_status"));
        assertEquals(0, ((Number) turn.get("delivery_attempt")).intValue());

        assertFalse(indexExists("dingtalk_robot_binding", "uk_robot"));
        assertFalse(indexExists("feishu_robot_binding", "uk_feishu_app"));
        assertFalse(indexExists("platform_im_channel_config", "uk_platform_im_provider"));
        assertTrue(indexExists("dingtalk_robot_binding", "uk_dingtalk_legacy_identity"));
        assertTrue(indexExists("dingtalk_robot_binding", "uk_dingtalk_shared_group"));
        assertTrue(indexExists("dingtalk_robot_binding", "idx_dingtalk_robot_code"));
        assertTrue(indexExists("feishu_robot_binding", "uk_feishu_legacy_identity"));
        assertTrue(indexExists("feishu_robot_binding", "uk_feishu_shared_group"));
        assertTrue(indexExists("feishu_robot_binding", "idx_feishu_app_id"));
        assertTrue(indexExists("platform_im_channel_config", "uk_platform_im_provider_purpose"));
        assertTrue(indexExists("agent_conversation_turn", "uk_turn_source_inbound"));
        assertTrue(indexExists("agent_conversation_turn", "idx_turn_delivery_claim"));
        assertTrue(indexExists("feishu_message_inbox", "uk_feishu_shared_receipt"));
    }

    @Test
    void removalMigrationDropsSixConfirmationColumnsIdempotentlyAndKeepsBindings() throws Exception {
        assertEquals(3, confirmationColumnCount("dingtalk_robot_binding"));
        assertEquals(3, confirmationColumnCount("feishu_robot_binding"));
        jdbc.update("INSERT INTO dingtalk_robot_binding (id, tenant_id, binding_mode, "
                + "platform_config_id, group_id, app_key, credential_ref, robot_code, agent_id, "
                + "route_version, confirmed_at, challenge_hash, challenge_expires_at, status) "
                + "VALUES (20, 1, 'SHARED', 60, 'cid-upgrade', 'ak', NULL, 'robot-shared', 3, 1, "
                + "NOW(3), ?, NOW(3), 'ENABLED')", "a".repeat(64));
        jdbc.update("INSERT INTO feishu_robot_binding (id, tenant_id, binding_mode, "
                + "platform_config_id, group_id, app_id, credential_ref, agent_id, route_version, "
                + "confirmed_at, challenge_hash, challenge_expires_at, status) "
                + "VALUES (21, 1, 'SHARED', 61, 'oc-upgrade', 'cli_shared', NULL, 4, 1, NOW(3), ?, "
                + "NOW(3), 'ENABLED')", "b".repeat(64));
        try {
            applyMigration("docs/migration/V100__remove_group_confirmation.sql");
            applyMigration("docs/migration/V100__remove_group_confirmation.sql");

            assertEquals(0, confirmationColumnCount("dingtalk_robot_binding"));
            assertEquals(0, confirmationColumnCount("feishu_robot_binding"));
            // Business rows and their routing data survive; only the six columns are gone.
            Map<String, Object> dingtalk = jdbc.queryForMap("SELECT binding_mode, platform_config_id, "
                    + "group_id, route_version, status FROM dingtalk_robot_binding WHERE id = 20");
            assertEquals("SHARED", dingtalk.get("binding_mode"));
            assertEquals(60L, ((Number) dingtalk.get("platform_config_id")).longValue());
            assertEquals("cid-upgrade", dingtalk.get("group_id"));
            assertEquals(1L, ((Number) dingtalk.get("route_version")).longValue());
            assertEquals("ENABLED", dingtalk.get("status"));
            Map<String, Object> feishu = jdbc.queryForMap("SELECT binding_mode, platform_config_id, "
                    + "group_id, status FROM feishu_robot_binding WHERE id = 21");
            assertEquals("SHARED", feishu.get("binding_mode"));
            assertEquals(61L, ((Number) feishu.get("platform_config_id")).longValue());
            assertEquals("oc-upgrade", feishu.get("group_id"));
            // Startup never re-adds the columns: the schema is defined without them.
            assertFalse(readMainSchema().contains("confirmed_at"));
        } finally {
            jdbc.update("DELETE FROM dingtalk_robot_binding WHERE id = 20");
            jdbc.update("DELETE FROM feishu_robot_binding WHERE id = 21");
            restoreConfirmationColumns();
        }
    }

    private static int confirmationColumnCount(String table) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME IN "
                + "('confirmed_at', 'challenge_hash', 'challenge_expires_at')", Integer.class, table);
        return count == null ? 0 : count;
    }

    private static String readMainSchema() throws Exception {
        return java.nio.file.Files.readString(java.nio.file.Path.of("docs/autowonder-schema.sql"));
    }

    private static void restoreConfirmationColumns() {
        for (String table : List.of("dingtalk_robot_binding", "feishu_robot_binding")) {
            if (columnMissing(table, "confirmed_at")) {
                jdbc.execute("ALTER TABLE `" + table + "` ADD COLUMN `confirmed_at` DATETIME(3) NULL");
            }
            if (columnMissing(table, "challenge_hash")) {
                jdbc.execute("ALTER TABLE `" + table + "` ADD COLUMN `challenge_hash` CHAR(64) NULL");
            }
            if (columnMissing(table, "challenge_expires_at")) {
                jdbc.execute("ALTER TABLE `" + table + "` ADD COLUMN `challenge_expires_at` DATETIME(3) NULL");
            }
        }
    }

    private static boolean columnMissing(String table, String column) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.COLUMNS "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                Integer.class, table, column);
        return count == null || count == 0;
    }

    private static void applyMigration(String path) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(path));
        }
    }

    @Test
    void migrationEnforcesLegacyAndSharedBindingConstraints() {
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO dingtalk_robot_binding (tenant_id, binding_mode, app_key, credential_ref, "
                        + "robot_code, agent_id) VALUES (1, 'LEGACY', 'ak', 'cipher', 'robot-legacy', 3)"),
                "legacy identity must stay unique after uk_robot is dropped");
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO dingtalk_robot_binding (tenant_id, binding_mode, app_key, credential_ref, "
                        + "robot_code, agent_id) VALUES (1, 'LEGACY', 'ak', '', 'robot-empty-cred', 3)"),
                "legacy binding requires a non-blank credential");
        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO dingtalk_robot_binding (tenant_id, binding_mode, platform_config_id, group_id, "
                        + "app_key, credential_ref, robot_code, agent_id) "
                        + "VALUES (1, 'SHARED', NULL, 'missing-config', 'ak', NULL, 'robot-shared', 3)"),
                "shared binding without a platform config must be rejected");

        assertEquals(1, jdbc.update(
                "INSERT INTO dingtalk_robot_binding (tenant_id, binding_mode, platform_config_id, group_id, "
                        + "app_key, credential_ref, robot_code, agent_id, route_version, confirmed_at) "
                        + "VALUES (1, 'SHARED', 60, 'legacy-group-a', 'ak', NULL, 'robot-shared', 3, 1, NOW(3))"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO dingtalk_robot_binding (tenant_id, binding_mode, platform_config_id, group_id, "
                        + "app_key, credential_ref, robot_code, agent_id) "
                        + "VALUES (1, 'SHARED', 60, 'legacy-group-a', 'ak', NULL, 'robot-shared', 3)"));
        assertEquals(1, jdbc.update(
                "INSERT INTO dingtalk_robot_binding (tenant_id, binding_mode, platform_config_id, group_id, "
                        + "app_key, credential_ref, robot_code, agent_id) "
                        + "VALUES (1, 'SHARED', 60, 'legacy-group-b', 'ak', NULL, 'robot-shared', 3)"));

        assertThrows(DataAccessException.class, () -> jdbc.update(
                "INSERT INTO feishu_robot_binding (tenant_id, binding_mode, platform_config_id, group_id, "
                        + "app_id, credential_ref, agent_id) "
                        + "VALUES (1, 'SHARED', 61, 'feishu-group', 'app-shared', 'cipher', 4)"),
                "shared binding must not carry a credential");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO feishu_robot_binding (tenant_id, binding_mode, app_id, credential_ref, agent_id) "
                        + "VALUES (1, 'LEGACY', 'app-legacy', 'cipher', 4)"),
                "legacy app identity must stay unique after uk_feishu_app is dropped");
        assertEquals(1, jdbc.update(
                "INSERT INTO feishu_robot_binding (id, tenant_id, binding_mode, platform_config_id, group_id, "
                        + "app_id, credential_ref, agent_id) "
                        + "VALUES (12, 1, 'SHARED', 61, 'feishu-group', 'app-shared', NULL, 4)"));
        assertEquals(1, jdbc.update(
                "INSERT INTO feishu_robot_binding (id, tenant_id, binding_mode, platform_config_id, group_id, "
                        + "app_id, credential_ref, agent_id) "
                        + "VALUES (13, 1, 'SHARED', 61, 'other-group', 'app-shared-other', NULL, 4)"));
        assertEquals(1, jdbc.update(
                "INSERT INTO feishu_robot_binding (id, tenant_id, binding_mode, app_id, credential_ref, agent_id) "
                        + "VALUES (14, 1, 'LEGACY', 'app-legacy-2', 'cipher', 4)"));

        // A shared receipt always references its own per-group binding row, so the composite key
        // alone decides dedupe: same config/group/message collides even across binding ids.
        assertEquals(1, jdbc.update(
                "INSERT INTO feishu_message_inbox (binding_id, tenant_id, agent_id, message_id, payload, "
                        + "platform_config_id, group_id) VALUES (12, 1, 4, 'om_shared', '{}', 61, 'feishu-group')"));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO feishu_message_inbox (binding_id, tenant_id, agent_id, message_id, payload, "
                        + "platform_config_id, group_id) VALUES (13, 1, 4, 'om_shared', '{}', 61, 'feishu-group')"),
                "shared receipts dedupe by platform config, group and message id");
        assertEquals(1, jdbc.update(
                "INSERT INTO feishu_message_inbox (binding_id, tenant_id, agent_id, message_id, payload, "
                        + "platform_config_id, group_id) VALUES (13, 1, 4, 'om_shared', '{}', 61, 'other-group')"));
        assertEquals(1, jdbc.update(
                "INSERT INTO feishu_message_inbox (binding_id, tenant_id, agent_id, message_id, payload) "
                        + "VALUES (8, 1, 4, 'om_null_scope', '{}')"));
        assertEquals(1, jdbc.update(
                "INSERT INTO feishu_message_inbox (binding_id, tenant_id, agent_id, message_id, payload) "
                        + "VALUES (14, 1, 4, 'om_null_scope', '{}')"),
                "legacy receipts keep independent NULL dedup scope");
    }

    @Test
    void deliveryFenceSerializesWithThePlatformConfigSave() throws Exception {
        Map<String, Object> before = jdbc.queryForMap(
                "SELECT app_key, credential_ref, route_version, version "
                        + "FROM platform_im_channel_config "
                        + "WHERE provider = 'FEISHU' AND purpose = 'GROUP_CHAT'");
        long beforeRoute = ((Number) before.get("route_version")).longValue();
        long beforeVersion = ((Number) before.get("version")).longValue();
        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseSend = new CountDownLatch(1);
        // The sender holds exactly what ImDeliveryFence holds for the whole external call.
        ImDeliveryFence fence = new ImDeliveryFence(
                new DataSourceTransactionManager(dataSource), jdbc);
        Thread sender = new Thread(() -> fence.run(() -> {
            jdbc.queryForList("SELECT id FROM platform_im_channel_config "
                    + "WHERE provider = 'FEISHU' AND purpose = 'GROUP_CHAT' FOR UPDATE");
            lockHeld.countDown();
            try {
                releaseSend.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        sender.start();
        try {
            assertTrue(lockHeld.await(30, TimeUnit.SECONDS), "the fence never took the config lock");

            // A save that cannot get the row lock in time fails and leaves the stored config intact.
            DataAccessException saveFailure = assertThrows(DataAccessException.class,
                    () -> jdbc.execute((ConnectionCallback<Integer>) connection -> {
                        try (Statement statement = connection.createStatement()) {
                            statement.execute("SET SESSION innodb_lock_wait_timeout = 1");
                        }
                        try (PreparedStatement update = connection.prepareStatement(
                                "UPDATE platform_im_channel_config SET app_key = ?, credential_ref = ?, "
                                        + "route_version = route_version + 1, version = version + 1 "
                                        + "WHERE provider = 'FEISHU' AND purpose = 'GROUP_CHAT'")) {
                            update.setString(1, "app-raced");
                            update.setString(2, "cipher-raced");
                            update.executeUpdate();
                        }
                        return 1;
                    }));
            assertNotNull(saveFailure);
            Map<String, Object> unchanged = jdbc.queryForMap(
                    "SELECT app_key, credential_ref, route_version, version "
                            + "FROM platform_im_channel_config "
                            + "WHERE provider = 'FEISHU' AND purpose = 'GROUP_CHAT'");
            assertEquals(before.get("app_key"), unchanged.get("app_key"));
            assertEquals(before.get("credential_ref"), unchanged.get("credential_ref"));
            assertEquals(beforeRoute, ((Number) unchanged.get("route_version")).longValue());
            assertEquals(beforeVersion, ((Number) unchanged.get("version")).longValue());
        } finally {
            releaseSend.countDown();
            sender.join(30_000);
        }
        assertFalse(sender.isAlive(), "the sender did not finish after the lock was released");

        // Once the send window is closed the same save commits and bumps the route once.
        assertEquals(1, jdbc.update(
                "UPDATE platform_im_channel_config SET app_key = ?, credential_ref = ?, "
                        + "route_version = route_version + 1, version = version + 1 "
                        + "WHERE provider = 'FEISHU' AND purpose = 'GROUP_CHAT'",
                "app-after-send", "cipher-after-send"));
        Map<String, Object> saved = jdbc.queryForMap(
                "SELECT app_key, credential_ref, route_version, version "
                        + "FROM platform_im_channel_config "
                        + "WHERE provider = 'FEISHU' AND purpose = 'GROUP_CHAT'");
        assertEquals("app-after-send", saved.get("app_key"));
        assertEquals("cipher-after-send", saved.get("credential_ref"));
        assertEquals(beforeRoute + 1, ((Number) saved.get("route_version")).longValue());
        assertEquals(beforeVersion + 1, ((Number) saved.get("version")).longValue());

        try {
            jdbc.update("UPDATE platform_im_channel_config SET app_key = ?, credential_ref = ?, "
                            + "route_version = ?, version = ? "
                            + "WHERE provider = 'FEISHU' AND purpose = 'GROUP_CHAT'",
                    before.get("app_key"), before.get("credential_ref"), beforeRoute, beforeVersion);
        } finally {
            jdbc.execute((ConnectionCallback<Integer>) connection -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET SESSION innodb_lock_wait_timeout = 50");
                }
                return 1;
            });
        }
    }

    @Test
    void migrationIsolatesNotifyFromGroupChatConfigs() {
        try (SqlSession session = factory.openSession(true)) {
            PlatformImChannelConfigDao dao = session.getMapper(PlatformImChannelConfigDao.class);
            assertEquals("cipher-notify",
                    dao.findByProviderAndPurpose("DINGTALK", "NOTIFY").getCredentialRef());
            assertEquals("cipher-group",
                    dao.findByProviderAndPurpose("DINGTALK", "GROUP_CHAT").getCredentialRef());
            assertEquals("cipher-feishu-group",
                    dao.findByProviderAndPurpose("FEISHU", "GROUP_CHAT").getCredentialRef());
        }
        assertEquals(2, jdbc.queryForObject(
                "SELECT COUNT(*) FROM platform_im_channel_config WHERE provider = 'DINGTALK'",
                Integer.class));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO platform_im_channel_config (provider, purpose) VALUES ('DINGTALK', 'GROUP_CHAT')"));
    }

    @Test
    void turnInsertDefaultsNullDeliveryAttemptAndEnforcesSourceInboundUnique() {
        try (SqlSession session = factory.openSession(true)) {
            AgentConversationTurnDao dao = session.getMapper(AgentConversationTurnDao.class);
            AgentConversationTurnDO inbound = new AgentConversationTurnDO();
            inbound.setTenantId(1L);
            inbound.setConversationId(2L);
            inbound.setDirection("IN");
            inbound.setContent("hello");
            inbound.setExternalMsgId("inbound-without-delivery-attempt");
            inbound.setStatus("DONE");

            assertEquals(1, dao.insert(inbound));
            assertNotNull(inbound.getId());
            assertNull(jdbc.queryForObject(
                    "SELECT delivery_status FROM agent_conversation_turn WHERE id = ?",
                    String.class, inbound.getId()),
                    "non-IM turns must not claim a delivery state");
            assertEquals(0, jdbc.queryForObject(
                    "SELECT delivery_attempt FROM agent_conversation_turn WHERE id = ?",
                    Integer.class, inbound.getId()),
                    "delivery_attempt must fall back to the column default");
        }

        jdbc.update("INSERT INTO agent_conversation_turn (tenant_id, conversation_id, direction, content, "
                + "status, source_inbound_turn_id, delivery_status, delivery_attempt) "
                + "VALUES (1, 2, 'OUT', 'reply-1', 'PENDING', 999, 'PENDING', 0)");
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(
                "INSERT INTO agent_conversation_turn (tenant_id, conversation_id, direction, content, "
                        + "status, source_inbound_turn_id, delivery_status, delivery_attempt) "
                        + "VALUES (1, 2, 'OUT', 'reply-2', 'PENDING', 999, 'PENDING', 0)"),
                "one inbound turn must not produce two reply rows");
        jdbc.update("INSERT INTO agent_conversation_turn (tenant_id, conversation_id, direction, content, "
                + "status, source_inbound_turn_id) VALUES (1, 2, 'OUT', 'plain-1', 'DONE', NULL)");
        jdbc.update("INSERT INTO agent_conversation_turn (tenant_id, conversation_id, direction, content, "
                + "status, source_inbound_turn_id) VALUES (1, 2, 'OUT', 'plain-2', 'DONE', NULL)");
    }

    @Test
    void deliveryClaimFinishesOnceAndRespectsLeaseLimits() {
        Date past = new Date(System.currentTimeMillis() - 60_000);
        Date future = new Date(System.currentTimeMillis() + 120_000);
        Date retryWindowStart = new Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000L);

        long pending = insertOutTurn("PENDING", 0, past, new Date());
        try (SqlSession session = factory.openSession(true)) {
            AgentConversationTurnDao dao = session.getMapper(AgentConversationTurnDao.class);
            assertTrue(dao.listDueDeliveryTurnIds(500).contains(pending));

            assertEquals(1, dao.claimPendingDelivery(pending, future));
            AgentConversationTurnDO claimed = dao.findDeliveryTurn(pending);
            assertEquals("SENDING", claimed.getDeliveryStatus());
            assertEquals(1, claimed.getDeliveryAttempt().intValue());
            assertEquals(0, dao.claimPendingDelivery(pending, future),
                    "a claimed reply must not be claimed twice");

            assertEquals(1, dao.finishDelivery(pending, 1, "SENT", null, null));
            assertEquals("SENT", dao.findDeliveryTurn(pending).getDeliveryStatus());
            assertEquals(0, dao.finishDelivery(pending, 1, "SENT", null, null),
                    "finish must be a compare-and-set on the claimed attempt");

            long expired = insertOutTurn("SENDING", 1, past, new Date());
            assertEquals(1, dao.claimExpiredDelivery(expired, future, 3, retryWindowStart));
            assertEquals(2, dao.findDeliveryTurn(expired).getDeliveryAttempt().intValue());

            jdbc.update("UPDATE agent_conversation_turn SET delivery_attempt = 3, "
                    + "delivery_available_at = ? WHERE id = ?", past, expired);
            assertEquals(0, dao.claimExpiredDelivery(expired, future, 3, retryWindowStart),
                    "an exhausted lease must not be reclaimed");

            long outsideWindow = insertOutTurn("SENDING", 1, past,
                    new Date(System.currentTimeMillis() - 48 * 60 * 60 * 1000L));
            assertEquals(0, dao.claimExpiredDelivery(outsideWindow, future, 3, retryWindowStart),
                    "a lease older than the retry window must not be reclaimed");

            long expiredLease = insertOutTurn("SENDING", 1, past, new Date());
            assertEquals(1, dao.expireUnconfirmedDelivery(expiredLease, "lease expired"));
            assertEquals("UNKNOWN", dao.findDeliveryTurn(expiredLease).getDeliveryStatus());

            long liveLease = insertOutTurn("SENDING", 1, future, new Date());
            assertEquals(0, dao.expireUnconfirmedDelivery(liveLease, "lease expired"));

            long pendingCancel = insertOutTurn("PENDING", 0, past, new Date());
            assertEquals(1, dao.cancelUndeliverableDelivery(pendingCancel, "no sink"));
            assertEquals("CANCELED", dao.findDeliveryTurn(pendingCancel).getDeliveryStatus());
            assertEquals(0, dao.cancelUndeliverableDelivery(pending, "no sink"));
        }
    }

    @Test
    void admissionGatesFollowDurableSelectionAndEventTime() {
        try (SqlSession session = factory.openSession(true)) {
            PlatformImChannelConfigDao configDao = session.getMapper(PlatformImChannelConfigDao.class);
            ImChannelAdmissionService service = new ImChannelAdmissionService(configDao);
            RecordingWriter writer = new RecordingWriter(snapshot());

            Instant eventTime = Instant.now().minusSeconds(60);
            ImChannelAdmissionService.Result accepted = service.admit("DINGTALK", eventTime, writer);
            assertEquals(ImChannelAdmissionService.Decision.ACCEPTED, accepted.decision());
            assertTrue(writer.persisted);
            assertNotNull(accepted.snapshot());

            writer.reset();
            ImChannelAdmissionService.Result wrongProvider = service.admit("FEISHU", eventTime, writer);
            assertEquals(ImChannelAdmissionService.Decision.IGNORED, wrongProvider.decision());
            assertEquals("provider_not_selected", wrongProvider.reason());
            assertFalse(writer.persisted);

            writer.reset();
            writer.duplicate = true;
            assertEquals(ImChannelAdmissionService.Decision.DUPLICATE,
                    service.admit("FEISHU", eventTime, writer).decision(),
                    "a durable receipt is a duplicate even after the channel switched");
            assertFalse(writer.persisted);

            writer.reset();
            assertEquals("invalid_event_time", service.admit("DINGTALK", null, writer).reason());

            writer.reset();
            jdbc.update("UPDATE platform_im_selection SET generation = 3, activated_at = ? WHERE id = 1",
                    Timestamp.from(Instant.now()));
            session.clearCache();
            assertEquals("stale_event", service.admit("DINGTALK", eventTime, writer).reason(),
                    "an event created before the switch must not be admitted");
            assertFalse(writer.persisted);

            writer.reset();
            assertEquals(ImChannelAdmissionService.Decision.ACCEPTED,
                    service.admit("DINGTALK", Instant.now().plusSeconds(1), writer).decision());

            writer.reset();
            writer.snapshot = null;
            assertEquals("not_admitted",
                    service.admit("DINGTALK", Instant.now().plusSeconds(1), writer).reason());

            assertTrue(service.allowsManagement("DINGTALK"));
            assertFalse(service.allowsManagement("FEISHU"));
        }
    }

    @Test
    void selectionRowLockSerializesSwitchWithAdmission() throws Exception {
        try (Connection holder = dataSource.getConnection();
                Connection contender = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            lockSelection(holder);

            contender.setAutoCommit(false);
            try (Statement statement = contender.createStatement()) {
                statement.execute("SET SESSION innodb_lock_wait_timeout = 1");
            }
            SQLException timeout = assertThrows(SQLException.class, () -> lockSelection(contender));
            assertTrue(String.valueOf(timeout.getMessage()).contains("Lock wait timeout"),
                    timeout.getMessage());
            contender.rollback();
            holder.commit();

            lockSelection(contender);
            contender.commit();
        }
    }

    @Test
    void concurrentSharedGroupBindingHasExactlyOneWinner() throws Exception {
        try (Connection holder = dataSource.getConnection();
                Connection contender = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            insertSharedBinding(holder, "race-group");

            contender.setAutoCommit(false);
            try (Statement statement = contender.createStatement()) {
                statement.execute("SET SESSION innodb_lock_wait_timeout = 1");
            }
            SQLException timeout = assertThrows(SQLException.class,
                    () -> insertSharedBinding(contender, "race-group"));
            assertTrue(String.valueOf(timeout.getMessage()).contains("Lock wait timeout"),
                    timeout.getMessage());
            contender.rollback();
            holder.commit();

            assertThrows(DataIntegrityViolationException.class,
                    () -> jdbc.update(sharedBindingSql(), 60, "race-group"));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM dingtalk_robot_binding WHERE platform_config_id = 60 "
                            + "AND group_id = 'race-group'", Integer.class));
        }
    }

    private static void lockSelection(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM platform_im_selection WHERE id = 1 FOR UPDATE");
                ResultSet resultSet = statement.executeQuery()) {
            assertTrue(resultSet.next());
        }
    }

    private static void insertSharedBinding(Connection connection, String groupId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sharedBindingSql())) {
            statement.setLong(1, 60);
            statement.setString(2, groupId);
            statement.executeUpdate();
        }
    }

    private static String sharedBindingSql() {
        return "INSERT INTO dingtalk_robot_binding (tenant_id, binding_mode, platform_config_id, group_id, "
                + "app_key, credential_ref, robot_code, agent_id) "
                + "VALUES (1, 'SHARED', ?, ?, 'ak', NULL, 'robot-shared', 3)";
    }

    private static long insertOutTurn(String deliveryStatus, int attempt, Date availableAt,
            Date createdAt) {
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO agent_conversation_turn (tenant_id, conversation_id, direction, content, "
                            + "status, delivery_status, delivery_attempt, delivery_available_at, gmt_create) "
                            + "VALUES (1, 2, 'OUT', 'reply', 'DONE', ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, deliveryStatus);
            statement.setInt(2, attempt);
            statement.setTimestamp(3, new Timestamp(availableAt.getTime()));
            statement.setTimestamp(4, new Timestamp(createdAt.getTime()));
            return statement;
        }, keys);
        return keys.getKey().longValue();
    }

    private static ImRouteSnapshot snapshot() {
        return ImRouteSnapshot.shared("DINGTALK", 7L, 60L, 1L, 1L, 1L, 3L, "group-a", 0L,
                System.currentTimeMillis());
    }

    private static boolean indexExists(String table, String index) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?",
                Integer.class, table, index);
        return count != null && count > 0;
    }

    private static void runMigration() throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            ScriptUtils.executeSqlScript(connection,
                    new FileSystemResource("docs/migration/V094__shared_group_channels.sql"));
        }
    }

    private static SqlSessionFactory buildSqlSessionFactory() throws Exception {
        org.apache.ibatis.session.Configuration configuration =
                new org.apache.ibatis.session.Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setEnvironment(new Environment("test", new JdbcTransactionFactory(), dataSource));
        parseMapper(configuration, "src/main/resources/mapping/PlatformImChannelConfigDao.xml");
        parseMapper(configuration, "src/main/resources/mapping/AgentConversationTurnDao.xml");
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static void parseMapper(org.apache.ibatis.session.Configuration configuration,
            String resource) throws Exception {
        try (var in = new FileSystemResource(resource).getInputStream()) {
            new XMLMapperBuilder(in, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }

    private static void createLegacySchema() {
        jdbc.execute("CREATE TABLE `platform_im_channel_config` ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,"
                + "provider VARCHAR(32) NOT NULL,"
                + "enabled TINYINT NOT NULL DEFAULT 0,"
                + "app_key VARCHAR(128) DEFAULT NULL,"
                + "credential_ref VARCHAR(1024) DEFAULT NULL,"
                + "robot_code VARCHAR(128) DEFAULT NULL,"
                + "base_url VARCHAR(512) DEFAULT NULL,"
                + "creator_id BIGINT UNSIGNED DEFAULT NULL,"
                + "modifier_id BIGINT UNSIGNED DEFAULT NULL,"
                + "gmt_create DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),"
                + "gmt_modified DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),"
                + "is_deleted TINYINT NOT NULL DEFAULT 0,"
                + "version INT NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (id),"
                + "UNIQUE KEY uk_platform_im_provider (provider)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE `platform_im_selection` ("
                + "id TINYINT NOT NULL PRIMARY KEY,"
                + "provider VARCHAR(32) NOT NULL"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE `dingtalk_robot_binding` ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,"
                + "tenant_id BIGINT NOT NULL,"
                + "app_key VARCHAR(128) NOT NULL,"
                + "credential_ref VARCHAR(512) NOT NULL,"
                + "robot_code VARCHAR(128) NOT NULL,"
                + "agent_id BIGINT NOT NULL,"
                + "transport_mode VARCHAR(32) NOT NULL DEFAULT 'HTTP_CALLBACK',"
                + "callback_token VARCHAR(128) NULL,"
                + "base_url VARCHAR(256) NULL,"
                + "region_id VARCHAR(64) NULL,"
                + "stream_env VARCHAR(32) NULL,"
                + "last_success_at DATETIME NULL,"
                + "last_error VARCHAR(1024) NULL,"
                + "status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',"
                + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "creator_id BIGINT NULL,"
                + "modifier_id BIGINT NULL,"
                + "is_deleted TINYINT NOT NULL DEFAULT 0,"
                + "version INT NOT NULL DEFAULT 0,"
                + "PRIMARY KEY (id),"
                + "UNIQUE KEY uk_robot (robot_code),"
                + "KEY idx_tenant_agent (tenant_id, agent_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE `feishu_robot_binding` ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,"
                + "tenant_id BIGINT NOT NULL,"
                + "app_id VARCHAR(128) NOT NULL,"
                + "credential_ref TEXT NOT NULL,"
                + "agent_id BIGINT NOT NULL,"
                + "status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',"
                + "last_success_at DATETIME NULL,"
                + "last_error VARCHAR(1024) NULL,"
                + "creator_id BIGINT NULL,"
                + "modifier_id BIGINT NULL,"
                + "version INT NOT NULL DEFAULT 0,"
                + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "gmt_modified DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (id),"
                + "UNIQUE KEY uk_feishu_app (app_id),"
                + "KEY idx_feishu_tenant_agent (tenant_id, agent_id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE `feishu_message_inbox` ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,"
                + "binding_id BIGINT NOT NULL,"
                + "tenant_id BIGINT NOT NULL,"
                + "agent_id BIGINT NOT NULL,"
                + "message_id VARCHAR(128) NOT NULL,"
                + "payload MEDIUMTEXT NOT NULL,"
                + "status VARCHAR(16) NOT NULL DEFAULT 'PENDING',"
                + "attempts INT NOT NULL DEFAULT 0,"
                + "available_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "last_error VARCHAR(1024) NULL,"
                + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (id),"
                + "UNIQUE KEY uk_feishu_inbound (binding_id, message_id),"
                + "KEY idx_feishu_inbox_poll (status, available_at, id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
        jdbc.execute("CREATE TABLE `agent_conversation_turn` ("
                + "id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,"
                + "tenant_id BIGINT NOT NULL,"
                + "conversation_id BIGINT NOT NULL,"
                + "direction VARCHAR(8) NOT NULL,"
                + "content MEDIUMTEXT NULL,"
                + "external_msg_id VARCHAR(256) NULL,"
                + "request_id VARCHAR(128) NULL,"
                + "source_context MEDIUMTEXT NULL,"
                + "submitted_user_id BIGINT NULL,"
                + "status VARCHAR(16) NOT NULL DEFAULT 'PENDING',"
                + "error VARCHAR(1024) NULL,"
                + "last_dispatch_at DATETIME NULL,"
                + "dispatch_attempt INT NOT NULL DEFAULT 0,"
                + "gmt_create DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                + "PRIMARY KEY (id),"
                + "UNIQUE KEY uk_external_msg (tenant_id, external_msg_id),"
                + "KEY idx_conversation (conversation_id),"
                + "KEY idx_conv_status_direction (tenant_id, conversation_id, direction, status, id),"
                + "KEY idx_turn_processing_dispatch (status, direction, last_dispatch_at, gmt_create, id)"
                + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
    }

    private static void seedLegacyRows() {
        jdbc.update("INSERT INTO platform_im_channel_config "
                + "(id, provider, enabled, app_key, credential_ref, robot_code, base_url) "
                + "VALUES (50, 'DINGTALK', 1, 'ak', 'cipher-notify', 'platform-robot', 'https://gw')");
        jdbc.update("INSERT INTO platform_im_selection (id, provider) VALUES (1, 'DINGTALK')");
        jdbc.update("INSERT INTO dingtalk_robot_binding "
                + "(id, tenant_id, app_key, credential_ref, robot_code, agent_id, status) "
                + "VALUES (7, 1, 'ak', 'cipher-ding', 'robot-legacy', 3, 'ENABLED')");
        jdbc.update("INSERT INTO feishu_robot_binding "
                + "(id, tenant_id, app_id, credential_ref, agent_id, status) "
                + "VALUES (8, 1, 'app-legacy', '{\"secret\":\"cipher\"}', 4, 'ENABLED')");
        jdbc.update("INSERT INTO feishu_message_inbox "
                + "(id, binding_id, tenant_id, agent_id, message_id, payload, status) "
                + "VALUES (9, 8, 1, 4, 'om_legacy', '{}', 'DONE')");
        jdbc.update("INSERT INTO agent_conversation_turn "
                + "(id, tenant_id, conversation_id, direction, content, external_msg_id, request_id, "
                + "source_context, status, error, last_dispatch_at, dispatch_attempt) "
                + "VALUES (10, 1, 2, 'IN', '旧消息', 'legacy-msg', NULL, NULL, 'DONE', NULL, NULL, 0)");
    }

    private static void seedGroupChatConfigs() {
        jdbc.update("INSERT INTO platform_im_channel_config "
                + "(id, provider, purpose, enabled, app_key, credential_ref, robot_code, base_url) "
                + "VALUES (60, 'DINGTALK', 'GROUP_CHAT', 1, 'ak', 'cipher-group', "
                + "'platform-robot', 'https://gw')");
        jdbc.update("INSERT INTO platform_im_channel_config "
                + "(id, provider, purpose, enabled, app_key, credential_ref, robot_code, base_url) "
                + "VALUES (61, 'FEISHU', 'GROUP_CHAT', 1, 'ak', 'cipher-feishu-group', "
                + "'platform-robot', 'https://gw')");
    }

    private static final class RecordingWriter implements ImChannelAdmissionService.ReceiptWriter {
        private ImRouteSnapshot snapshot;
        private boolean duplicate;
        private boolean persisted;

        private RecordingWriter(ImRouteSnapshot snapshot) {
            this.snapshot = snapshot;
        }

        @Override
        public boolean alreadyReceived(ImChannelAdmissionService.Context context) {
            return duplicate;
        }

        @Override
        public ImRouteSnapshot persist(ImChannelAdmissionService.Context context) {
            persisted = true;
            return snapshot;
        }

        private void reset() {
            duplicate = false;
            persisted = false;
        }
    }
}
