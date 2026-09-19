package com.aliyun.autowonder.conversation;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于 H2 的平台管家会话持久层回归测试。断言全部围绕一条不可让步的边界：
 * SQL 本身就必须把 tenant / channel / owner_user_id 钉死，任何越权读取或改写都要在 DAO 层直接落空，
 * 而不是指望上层每次都记得再判一次 Owner。
 */
class PlatformConversationDaoTest {

    static final long TENANT = 1L;
    static final long AGENT = 40013L;
    static final long OWNER = 7001L;
    static final long INTRUDER = 7002L;
    static final String JDBC_URL =
            "jdbc:h2:mem:platform_conversation_test;MODE=MySQL;DB_CLOSE_DELAY=-1";

    static AgentConversationDao conversationDao;
    static ConversationShareDao shareDao;
    static ConversationTurnArtifactDao turnArtifactDao;

    @BeforeAll
    static void initDb() throws Exception {
        try (InputStream in = Resources.getResourceAsStream(
                "mybatis-platform-conversation-test-config.xml")) {
            SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(in);
            SqlSessionTemplate template = new SqlSessionTemplate(factory);
            conversationDao = template.getMapper(AgentConversationDao.class);
            shareDao = template.getMapper(ConversationShareDao.class);
            turnArtifactDao = template.getMapper(ConversationTurnArtifactDao.class);
        }
        execScript("platform-conversation-schema-h2.sql");
    }

    @BeforeEach
    void cleanTables() throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("DELETE FROM agent_conversation");
            st.execute("DELETE FROM conversation_share");
            st.execute("DELETE FROM conversation_turn_artifact");
        }
    }

    private static void execScript(String resource) throws Exception {
        String sql;
        try (InputStream in = Resources.getResourceAsStream(resource)) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement st = c.createStatement()) {
            for (String stmt : sql.split(";")) {
                if (!stmt.isBlank()) {
                    st.execute(stmt);
                }
            }
        }
    }

    private AgentConversationDO platform(String opaqueId, long ownerUserId, long lastTurnMillisAgo) {
        AgentConversationDO row = new AgentConversationDO();
        row.setTenantId(TENANT);
        row.setOwnerUserId(ownerUserId);
        row.setAgentId(AGENT);
        row.setChannel(PlatformConversationChannel.CHANNEL);
        row.setChannelConversationId(opaqueId);
        row.setTitle("会话 " + opaqueId);
        row.setTitleSource("AUTO");
        row.setStatus("ACTIVE");
        row.setLastTurnAt(new Date(System.currentTimeMillis() - lastTurnMillisAgo));
        return row;
    }

    private long insertPlatform(String opaqueId, long ownerUserId, long lastTurnMillisAgo) {
        AgentConversationDO row = platform(opaqueId, ownerUserId, lastTurnMillisAgo);
        assertEquals(1, conversationDao.insert(row));
        assertNotNull(row.getId());
        return row.getId();
    }

    /** insert 必须把 owner_user_id 落库，否则 Owner 边界从第一行就丢了。 */
    @Test
    void insertPersistsImmutableOwnerAndTitle() {
        long id = insertPlatform("conv-owner", OWNER, 0);

        AgentConversationDO loaded = conversationDao.findById(TENANT, id);
        assertEquals(OWNER, loaded.getOwnerUserId());
        assertEquals(PlatformConversationChannel.CHANNEL, loaded.getChannel());
        assertEquals("会话 conv-owner", loaded.getTitle());
        assertEquals("AUTO", loaded.getTitleSource());
        assertNull(loaded.getArchivedAt());
        assertNull(loaded.getDeletedAt());
    }

    /** 历史列表是最高频的读取路径，别人的会话、别的渠道、已删除的都不能出现。 */
    @Test
    void listPlatformByOwnerOnlySeesTheCallersOwnLiveConversations() {
        long mine = insertPlatform("conv-mine", OWNER, 0);
        insertPlatform("conv-theirs", INTRUDER, 0);
        long deleted = insertPlatform("conv-deleted", OWNER, 0);
        assertEquals(1, conversationDao.markPlatformDeleted(TENANT, deleted, OWNER, new Date()));

        AgentConversationDO legacy = new AgentConversationDO();
        legacy.setTenantId(TENANT);
        legacy.setAgentId(AGENT);
        legacy.setChannel("WORKITEM_CLARIFICATION");
        legacy.setChannelConversationId("conv-legacy");
        legacy.setStatus("ACTIVE");
        assertEquals(1, conversationDao.insert(legacy));

        List<AgentConversationDO> rows =
                conversationDao.listPlatformByOwner(TENANT, OWNER, null, null, 20, 0);

        assertEquals(1, rows.size());
        assertEquals(mine, rows.get(0).getId());
    }

    /** 跨租户 / 跨 Owner 猜测都必须落空，这是分享链接被转发后唯一的兜底。 */
    @Test
    void listPlatformByOwnerIsScopedToTenantAndOwner() {
        insertPlatform("conv-mine", OWNER, 0);

        assertTrue(conversationDao.listPlatformByOwner(TENANT + 1, OWNER, null, null, 20, 0).isEmpty());
        assertTrue(conversationDao.listPlatformByOwner(TENANT, INTRUDER, null, null, 20, 0).isEmpty());
    }

    @Test
    void listPlatformByOwnerSplitsArchivedFromActive() {
        long active = insertPlatform("conv-active", OWNER, 0);
        long archived = insertPlatform("conv-archived", OWNER, 0);
        conversationDao.updatePlatformMetadata(TENANT, archived, OWNER, null, null, new Date());

        assertEquals(List.of(active), ids(conversationDao.listPlatformByOwner(TENANT, OWNER, false, null, 20, 0)));
        assertEquals(List.of(archived), ids(conversationDao.listPlatformByOwner(TENANT, OWNER, true, null, 20, 0)));
        assertEquals(List.of(archived, active),
                ids(conversationDao.listPlatformByOwner(TENANT, OWNER, null, null, 20, 0)));
    }

    /** 搜索只按标题与 opaque id 模糊匹配，且不能借搜索绕开 Owner 过滤。 */
    @Test
    void listPlatformByOwnerFiltersByKeywordWithinTheOwner() {
        long alpha = insertPlatform("conv-alpha", OWNER, 0);
        long beta = insertPlatform("conv-beta", OWNER, 0);
        conversationDao.updatePlatformMetadata(TENANT, beta, OWNER, "月度发布计划", "USER", null);
        insertPlatform("conv-alpha-intruder", INTRUDER, 0);

        // 同名的入侵者会话必须被 Owner 过滤挡掉，只剩自己的那一行。
        assertEquals(List.of(alpha),
                ids(conversationDao.listPlatformByOwner(TENANT, OWNER, null, "conv-alpha", 20, 0)));
        assertEquals(List.of(beta),
                ids(conversationDao.listPlatformByOwner(TENANT, OWNER, null, "发布计划", 20, 0)));
        assertTrue(conversationDao.listPlatformByOwner(TENANT, OWNER, null, "intruder", 20, 0).isEmpty());
        // 空串等价于不过滤；空白串的裁剪是服务层职责，不在 DAO 断言。
        assertEquals(2, conversationDao.listPlatformByOwner(TENANT, OWNER, null, "", 20, 0).size());
    }

    /** 侧边栏顺序必须是最近一轮在前；翻页不能重复也不能漏。 */
    @Test
    void listPlatformByOwnerOrdersByLastTurnAndPages() {
        long stale = insertPlatform("conv-stale", OWNER, 60_000);
        long fresh = insertPlatform("conv-fresh", OWNER, 0);

        assertEquals(List.of(fresh, stale),
                ids(conversationDao.listPlatformByOwner(TENANT, OWNER, null, null, 20, 0)));
        assertEquals(List.of(fresh),
                ids(conversationDao.listPlatformByOwner(TENANT, OWNER, null, null, 1, 0)));
        assertEquals(List.of(stale),
                ids(conversationDao.listPlatformByOwner(TENANT, OWNER, null, null, 1, 1)));
    }

    /** 重命名把 title_source 记成 USER，之后自动标题不得再覆盖用户的手改。 */
    @Test
    void updatePlatformMetadataRenamesAndMarksUserSource() {
        long id = insertPlatform("conv-rename", OWNER, 0);

        assertEquals(1, conversationDao.updatePlatformMetadata(TENANT, id, OWNER, "我的标题", "USER", null));

        AgentConversationDO renamed = conversationDao.findById(TENANT, id);
        assertEquals("我的标题", renamed.getTitle());
        assertEquals("USER", renamed.getTitleSource());
        assertNull(renamed.getArchivedAt());
    }

    /** 归档与取消归档共用同一条语句：传时间即归档，传 null 即恢复。 */
    @Test
    void updatePlatformMetadataArchivesAndRestores() {
        long id = insertPlatform("conv-archive", OWNER, 0);
        Date archivedAt = new Date();

        assertEquals(1, conversationDao.updatePlatformMetadata(TENANT, id, OWNER, null, null, archivedAt));
        assertNotNull(conversationDao.findById(TENANT, id).getArchivedAt());

        assertEquals(1, conversationDao.updatePlatformMetadata(TENANT, id, OWNER, null, null, null));
        assertNull(conversationDao.findById(TENANT, id).getArchivedAt());
    }

    /** 非 Owner 的改写必须影响 0 行，即使他拿到了会话 ID。 */
    @Test
    void updatePlatformMetadataRefusesNonOwner() {
        long id = insertPlatform("conv-guard", OWNER, 0);

        assertEquals(0, conversationDao.updatePlatformMetadata(TENANT, id, INTRUDER, "劫持", "USER", null));
        assertEquals(0, conversationDao.updatePlatformMetadata(TENANT + 1, id, OWNER, "劫持", "USER", null));
        assertEquals("会话 conv-guard", conversationDao.findById(TENANT, id).getTitle());
    }

    /** 遗留渠道没有 Owner，平台侧的改写语句必须对它完全无效。 */
    @Test
    void updatePlatformMetadataDoesNotTouchLegacyChannels() {
        AgentConversationDO legacy = new AgentConversationDO();
        legacy.setTenantId(TENANT);
        legacy.setAgentId(AGENT);
        legacy.setChannel("WORKITEM_CLARIFICATION");
        legacy.setChannelConversationId("conv-legacy");
        legacy.setStatus("ACTIVE");
        assertEquals(1, conversationDao.insert(legacy));

        assertEquals(0, conversationDao.updatePlatformMetadata(TENANT, legacy.getId(), OWNER, "劫持", "USER", null));
        assertEquals(0, conversationDao.markPlatformDeleted(TENANT, legacy.getId(), OWNER, new Date()));
        assertNull(conversationDao.findById(TENANT, legacy.getId()).getDeletedAt());
    }

    @Test
    void markPlatformDeletedIsOwnerScopedAndIdempotent() {
        long id = insertPlatform("conv-delete", OWNER, 0);

        assertEquals(0, conversationDao.markPlatformDeleted(TENANT, id, INTRUDER, new Date()));
        assertEquals(1, conversationDao.markPlatformDeleted(TENANT, id, OWNER, new Date()));
        // 第二次删除必须 0 行，重复点击不能把 deleted_at 往后推。
        assertEquals(0, conversationDao.markPlatformDeleted(TENANT, id, OWNER, new Date()));
        assertNotNull(conversationDao.findById(TENANT, id).getDeletedAt());
        assertTrue(conversationDao.listPlatformByOwner(TENANT, OWNER, null, null, 20, 0).isEmpty());
    }

    /** 重复分享同一人不能撞 uk_conversation_grantee，必须原地复活那行。 */
    @Test
    void upsertReadShareKeepsOneRowPerGrantee() {
        long conversation = insertPlatform("conv-share", OWNER, 0);

        assertEquals(1, shareDao.upsertReadShare(TENANT, conversation, INTRUDER, OWNER));
        shareDao.upsertReadShare(TENANT, conversation, INTRUDER, OWNER);

        List<ConversationShareDO> shares = shareDao.listActive(TENANT, conversation);
        assertEquals(1, shares.size());
        assertEquals("READ", shares.get(0).getPermission());
        assertEquals(OWNER, shares.get(0).getCreatedBy());
    }

    /** 撤销后再分享是常见操作，必须复用同一行并把 revoked_at 清空。 */
    @Test
    void revokeThenReshareReusesTheSameRow() {
        long conversation = insertPlatform("conv-reshare", OWNER, 0);
        shareDao.upsertReadShare(TENANT, conversation, INTRUDER, OWNER);

        assertEquals(1, shareDao.revoke(TENANT, conversation, INTRUDER, OWNER, new Date()));
        assertNull(shareDao.findActive(TENANT, conversation, INTRUDER));
        assertTrue(shareDao.listActive(TENANT, conversation).isEmpty());

        // 命中已撤销那行时 ON DUPLICATE KEY UPDATE 返回 2（MySQL 语义：更新算 2 行），
        // 所以断言可观察的不变量而不是受影响行数：复活后仍然只有一行且 revoked_at 已清空。
        shareDao.upsertReadShare(TENANT, conversation, INTRUDER, OWNER);

        List<ConversationShareDO> reshares = shareDao.listActive(TENANT, conversation);
        assertEquals(1, reshares.size());
        assertNull(reshares.get(0).getRevokedAt());
        assertNotNull(shareDao.findActive(TENANT, conversation, INTRUDER));
    }

    /** 只有 Owner（created_by）能撤销；被分享人不能自己解除，也不能替别人解除。 */
    @Test
    void revokeIsOnlyPossibleForTheShareCreator() {
        long conversation = insertPlatform("conv-revoke", OWNER, 0);
        shareDao.upsertReadShare(TENANT, conversation, INTRUDER, OWNER);

        assertEquals(0, shareDao.revoke(TENANT, conversation, INTRUDER, INTRUDER, new Date()));
        assertEquals(0, shareDao.revoke(TENANT + 1, conversation, INTRUDER, OWNER, new Date()));
        assertNotNull(shareDao.findActive(TENANT, conversation, INTRUDER));
        assertEquals(1, shareDao.revoke(TENANT, conversation, INTRUDER, OWNER, new Date()));
        // 已撤销的再撤一次必须 0 行，重复点击不能刷新 revoked_at。
        assertEquals(0, shareDao.revoke(TENANT, conversation, INTRUDER, OWNER, new Date()));
    }

    @Test
    void findActiveShareIsScopedToConversationAndGrantee() {
        long conversation = insertPlatform("conv-scope", OWNER, 0);
        long other = insertPlatform("conv-other", OWNER, 0);
        shareDao.upsertReadShare(TENANT, conversation, INTRUDER, OWNER);

        assertNotNull(shareDao.findActive(TENANT, conversation, INTRUDER));
        assertNull(shareDao.findActive(TENANT, other, INTRUDER));
        assertNull(shareDao.findActive(TENANT, conversation, OWNER));
        assertNull(shareDao.findActive(TENANT + 1, conversation, INTRUDER));
    }

    private ConversationTurnArtifactDO reference(long turnId, long artifactId, String direction,
            String mode, int order, String manifest) {
        ConversationTurnArtifactDO row = new ConversationTurnArtifactDO();
        row.setTenantId(TENANT);
        row.setConversationId(1L);
        row.setTurnId(turnId);
        row.setArtifactId(artifactId);
        row.setDirection(direction);
        row.setReferenceMode(mode);
        row.setDisplayOrder(order);
        row.setManifestJson(manifest);
        return row;
    }

    /** 同一 Turn 内 INPUT 先于 OUTPUT，同方向按 display_order 排，前端才能按原顺序渲染。 */
    @Test
    void listByTurnKeepsInputBeforeOutputInDisplayOrder() {
        turnArtifactDao.insert(reference(100L, 11L, "OUTPUT", "GENERATED", 0, "{\"n\":\"out-0\"}"));
        turnArtifactDao.insert(reference(100L, 12L, "OUTPUT", "GENERATED", 1, "{\"n\":\"out-1\"}"));
        turnArtifactDao.insert(reference(100L, 13L, "INPUT", "UPLOAD", 1, "{\"n\":\"in-1\"}"));
        turnArtifactDao.insert(reference(100L, 14L, "INPUT", "SELECTED", 0, "{\"n\":\"in-0\"}"));
        turnArtifactDao.insert(reference(101L, 15L, "INPUT", "MENTION", 0, "{\"n\":\"other-turn\"}"));

        List<ConversationTurnArtifactDO> rows = turnArtifactDao.listByTurn(TENANT, 100L);

        assertEquals(List.of(14L, 13L, 11L, 12L), rows.stream()
                .map(ConversationTurnArtifactDO::getArtifactId).toList());
        assertEquals("{\"n\":\"in-0\"}", rows.get(0).getManifestJson());
        assertEquals("SELECTED", rows.get(0).getReferenceMode());
        assertTrue(turnArtifactDao.listByTurn(TENANT + 1, 100L).isEmpty());
    }

    /**
     * uk_turn_artifact_direction 是重投与重试的兜底：同一 Turn 同一文件同一方向只能有一行，
     * 但 INPUT 与 OUTPUT 各自独立，不能互相顶掉。
     */
    @Test
    void turnArtifactUniqueKeyBlocksDuplicateDirectionOnly() {
        turnArtifactDao.insert(reference(100L, 11L, "INPUT", "UPLOAD", 0, "{\"v\":1}"));

        assertThrows(Exception.class,
                () -> turnArtifactDao.insert(reference(100L, 11L, "INPUT", "UPLOAD", 0, "{\"v\":2}")));
        assertEquals(1, turnArtifactDao.insert(reference(100L, 11L, "OUTPUT", "GENERATED", 0, "{\"v\":3}")));

        List<ConversationTurnArtifactDO> rows = turnArtifactDao.listByTurn(TENANT, 100L);
        assertEquals(2, rows.size());
        // 被拒绝的重复插入不能改写已经固化的清单。
        assertEquals("{\"v\":1}", rows.get(0).getManifestJson());
    }

    @Test
    void listByConversationPagesNewestTurnFirst() {
        turnArtifactDao.insert(reference(100L, 11L, "INPUT", "UPLOAD", 0, "{}"));
        turnArtifactDao.insert(reference(200L, 12L, "INPUT", "UPLOAD", 0, "{}"));
        turnArtifactDao.insert(reference(200L, 13L, "OUTPUT", "GENERATED", 0, "{}"));

        assertEquals(List.of(12L, 13L, 11L), turnArtifactDao.listByConversation(TENANT, 1L, 20, 0)
                .stream().map(ConversationTurnArtifactDO::getArtifactId).toList());
        assertEquals(List.of(12L, 13L), turnArtifactDao.listByConversation(TENANT, 1L, 2, 0)
                .stream().map(ConversationTurnArtifactDO::getArtifactId).toList());
        assertEquals(List.of(11L), turnArtifactDao.listByConversation(TENANT, 1L, 2, 2)
                .stream().map(ConversationTurnArtifactDO::getArtifactId).toList());
        assertTrue(turnArtifactDao.listByConversation(TENANT, 2L, 20, 0).isEmpty());
    }

    private List<Long> ids(List<AgentConversationDO> rows) {
        return rows.stream().map(AgentConversationDO::getId).toList();
    }
}
