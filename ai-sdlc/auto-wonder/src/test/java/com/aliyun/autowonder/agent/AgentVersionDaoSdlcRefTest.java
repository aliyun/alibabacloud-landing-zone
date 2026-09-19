package com.aliyun.autowonder.agent;

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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于 H2(MODE=MySQL) 的 {@code AgentVersionDao.listAgentIdsBySdlcId} 真实查询回归测试。
 *
 * <p>校验 SDLC 删除的数字人占用口径：仅统计每个数字人“当前生效版本”(agent.online_version_id 指向的版本)
 * 的 SDLC 引用；历史版本、非生效草稿、已软删除的数字人/版本均不计入。覆盖工单验收标准 1–6，
 * 直接执行 {@code mapping/AgentVersionDao.xml} 的真实 SQL（非 mock）。
 */
class AgentVersionDaoSdlcRefTest {

    static final long TENANT = 10002L;
    static final long SDLC_A = 900L;
    static final long SDLC_B = 901L;
    static final String JDBC_URL = "jdbc:h2:mem:agent_version_sdlc_ref_test;MODE=MySQL;DB_CLOSE_DELAY=-1";

    static AgentVersionDao dao;

    @BeforeAll
    static void initDb() throws Exception {
        try (InputStream in = Resources.getResourceAsStream("mybatis-agent-version-test-config.xml")) {
            SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(in);
            dao = new SqlSessionTemplate(factory).getMapper(AgentVersionDao.class);
        }
        execScript("agent-version-schema-h2.sql");
    }

    @BeforeEach
    void cleanTables() throws Exception {
        exec("DELETE FROM agent");
        exec("DELETE FROM agent_version");
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

    private static void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private static void insertAgent(long id, Long onlineVersionId, int isDeleted) throws Exception {
        String online = onlineVersionId == null ? "NULL" : String.valueOf(onlineVersionId);
        exec("INSERT INTO agent (id, tenant_id, name, status, online_version_id, latest_version_no, is_deleted, version) "
                + "VALUES (" + id + ", " + TENANT + ", 'agent-" + id + "', 'ONLINE', " + online + ", 0, " + isDeleted + ", 0)");
    }

    private static void insertVersion(long id, long agentId, int versionNo, Long sdlcId, int isDeleted) throws Exception {
        String sdlc = sdlcId == null ? "NULL" : String.valueOf(sdlcId);
        exec("INSERT INTO agent_version (id, tenant_id, agent_id, version_no, status, sdlc_id, is_deleted, version) "
                + "VALUES (" + id + ", " + TENANT + ", " + agentId + ", " + versionNo + ", 'APPROVED', " + sdlc + ", " + isDeleted + ", 0)");
    }

    private static void setOnlineVersion(long agentId, Long onlineVersionId) throws Exception {
        String online = onlineVersionId == null ? "NULL" : String.valueOf(onlineVersionId);
        exec("UPDATE agent SET online_version_id = " + online + " WHERE id = " + agentId);
    }

    /** 验收 1 & 3：历史 v1 引 A、当前生效 v2 改绑 B → A 不再被占用，B 被占用。 */
    @Test
    void historicalVersionReference_doesNotBlock_onlyCurrentOnlineVersionCounts() throws Exception {
        insertVersion(201L, 101L, 1, SDLC_A, 0); // 历史 v1 引 A
        insertVersion(202L, 101L, 2, SDLC_B, 0); // 当前生效 v2 引 B
        insertAgent(101L, 202L, 0);

        assertTrue(dao.listAgentIdsBySdlcId(SDLC_A).isEmpty());
        assertEquals(List.of(101L), dao.listAgentIdsBySdlcId(SDLC_B));
    }

    /** 验收 2：当前生效版本仍引 A → 占用；未发布草稿改绑 B 不解除生效版本对 A 的占用，且草稿引 B 不算占用。 */
    @Test
    void currentOnlineVersionStillReferencing_blocksDelete_unpublishedDraftDoesNotRelease() throws Exception {
        insertVersion(212L, 102L, 1, SDLC_A, 0); // 生效版本引 A
        insertVersion(213L, 102L, 2, SDLC_B, 0); // 未发布草稿引 B
        insertAgent(102L, 212L, 0);
        exec("UPDATE agent SET editing_version_id = 213 WHERE id = 102");

        assertEquals(List.of(102L), dao.listAgentIdsBySdlcId(SDLC_A));
        assertTrue(dao.listAgentIdsBySdlcId(SDLC_B).isEmpty());
    }

    /** 验收 3：仅历史版本与非生效草稿引用 A、生效版本引 B → A 可删除。 */
    @Test
    void onlyHistoricalAndDraftReference_doesNotBlock() throws Exception {
        insertVersion(221L, 103L, 1, SDLC_A, 0); // 历史引 A
        insertVersion(222L, 103L, 2, SDLC_A, 0); // 草稿引 A
        insertVersion(223L, 103L, 3, SDLC_B, 0); // 生效引 B
        insertAgent(103L, 223L, 0);

        assertTrue(dao.listAgentIdsBySdlcId(SDLC_A).isEmpty());
        assertEquals(List.of(103L), dao.listAgentIdsBySdlcId(SDLC_B));
    }

    /** 验收 4：多个数字人中只要有一个当前生效版本引 A → A 被占用并准确返回该引用方；他人历史 A 引用不计入。 */
    @Test
    void multipleAgents_blockedWhenAnyOnlineVersionReferences() throws Exception {
        insertVersion(231L, 104L, 1, SDLC_A, 0);
        insertAgent(104L, 231L, 0);              // 104 生效引 A
        insertVersion(232L, 105L, 1, SDLC_B, 0);
        insertVersion(233L, 105L, 2, SDLC_A, 0); // 105 历史引 A（非生效）
        insertAgent(105L, 232L, 0);              // 105 生效引 B

        assertEquals(List.of(104L), dao.listAgentIdsBySdlcId(SDLC_A));
        assertEquals(List.of(105L), dao.listAgentIdsBySdlcId(SDLC_B));
    }

    /** 验收 5：回滚后按实际生效指针判断，不按最大版本号；生效指针指回引 A 的 v1 后，A 占用、B 释放。 */
    @Test
    void rollback_judgedByOnlinePointer_notMaxVersionNo() throws Exception {
        insertVersion(241L, 106L, 1, SDLC_A, 0); // v1 引 A
        insertVersion(242L, 106L, 2, SDLC_B, 0); // v2 引 B（版本号更大）
        insertAgent(106L, 242L, 0);              // 初始生效 v2

        assertEquals(List.of(106L), dao.listAgentIdsBySdlcId(SDLC_B));
        assertTrue(dao.listAgentIdsBySdlcId(SDLC_A).isEmpty());

        setOnlineVersion(106L, 241L);            // 回滚到 v1

        assertEquals(List.of(106L), dao.listAgentIdsBySdlcId(SDLC_A));
        assertTrue(dao.listAgentIdsBySdlcId(SDLC_B).isEmpty());
    }

    /** 验收 6：当前生效版本已解除 SDLC 引用(sdlc_id=NULL) 时，历史版本对 A 的引用不得隐式恢复。 */
    @Test
    void currentOnlineVersionReleasedSdlc_historicalDoesNotResurrect() throws Exception {
        insertVersion(250L, 107L, 1, SDLC_A, 0); // 历史引 A
        insertVersion(251L, 107L, 2, null, 0);   // 生效版本已解除引用
        insertAgent(107L, 251L, 0);

        assertTrue(dao.listAgentIdsBySdlcId(SDLC_A).isEmpty());
    }

    /** 已软删除的数字人(a.is_deleted=1) 与已软删除的版本(av.is_deleted=1) 均不计入占用。 */
    @Test
    void softDeletedAgentAndVersion_areExcluded() throws Exception {
        insertVersion(261L, 108L, 1, SDLC_A, 0);
        insertAgent(108L, 261L, 1);              // 数字人已软删除
        insertVersion(262L, 109L, 1, SDLC_A, 1); // 生效版本已软删除
        insertAgent(109L, 262L, 0);

        assertTrue(dao.listAgentIdsBySdlcId(SDLC_A).isEmpty());
    }

    /** 引用方较多时保持 DISTINCT + ORDER BY a.id + LIMIT 5 的既有语义（最多返回 5 个，按 id 升序）。 */
    @Test
    void limit5_returnsAtMostFiveReferencingAgentsOrderedById() throws Exception {
        for (int i = 0; i < 6; i++) {
            long agentId = 110L + i;
            long versionId = 270L + i;
            insertVersion(versionId, agentId, 1, SDLC_A, 0);
            insertAgent(agentId, versionId, 0);
        }

        List<Long> refs = dao.listAgentIdsBySdlcId(SDLC_A);
        assertEquals(5, refs.size());
        assertEquals(List.of(110L, 111L, 112L, 113L, 114L), refs);
    }

    /** NB-1：生效指针指向他人数字人的版本(av.agent_id != a.id)时不计入占用，与 AgentSdlcResolver 的 agentId 守卫同口径。 */
    @Test
    void onlinePointerToForeignAgentVersion_isNotCounted_ownershipGuard() throws Exception {
        insertVersion(280L, 999L, 1, SDLC_A, 0); // 版本 280 实属他人 999 且引 A
        insertAgent(120L, 280L, 0);              // 120 生效指针误指向他人版本 280（脏数据）
        insertVersion(281L, 121L, 1, SDLC_A, 0); // 121 生效指针指向自身版本 281 引 A
        insertAgent(121L, 281L, 0);

        // av.agent_id = a.id 守卫排除 120（指针指向他人版本），仅 121 计入；未加守卫时会误返回 [120,121]。
        assertEquals(List.of(121L), dao.listAgentIdsBySdlcId(SDLC_A));
    }
}
