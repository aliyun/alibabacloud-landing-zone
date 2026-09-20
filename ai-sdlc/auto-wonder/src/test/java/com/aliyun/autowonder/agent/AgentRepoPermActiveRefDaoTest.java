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
 * 基于 H2 的仓库有效引用查询回归测试，覆盖工单场景
 * “在线版本绑定仓库 → 草稿解绑（未发布仍阻止删除）→ 发布后允许删除”，
 * 以及普通历史版本、已删除数字人、已删除版本、跨工作空间数据均不计入有效引用。
 */
class AgentRepoPermActiveRefDaoTest {

    static final long TENANT = 10002L;
    static final long OTHER_TENANT = 20003L;
    static final long AGENT = 400L;
    static final long OTHER_AGENT = 401L;
    static final long REPO = 501L;
    static final long OTHER_REPO = 502L;
    static final String JDBC_URL = "jdbc:h2:mem:agent_repo_perm_ref_test;MODE=MySQL;DB_CLOSE_DELAY=-1";

    static AgentRepoPermDao dao;

    @BeforeAll
    static void initDb() throws Exception {
        try (InputStream in = Resources.getResourceAsStream("mybatis-agent-repo-perm-test-config.xml")) {
            SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(in);
            dao = new SqlSessionTemplate(factory).getMapper(AgentRepoPermDao.class);
        }
        execScript("agent-repo-perm-schema-h2.sql");
    }

    @BeforeEach
    void cleanTables() throws Exception {
        execute("DELETE FROM agent_repo_perm", "DELETE FROM agent_version", "DELETE FROM agent");
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

    private static void execute(String... statements) throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement st = c.createStatement()) {
            for (String stmt : statements) {
                st.execute(stmt);
            }
        }
    }

    private void insertAgent(long id, long tenantId, String name, Long onlineVersionId,
                             Long editingVersionId, int isDeleted) throws Exception {
        execute("INSERT INTO agent (id, tenant_id, name, status, online_version_id, editing_version_id,"
                + " latest_version_no, is_deleted, version) VALUES (" + id + ", " + tenantId + ", '" + name
                + "', 'ONLINE', " + literal(onlineVersionId) + ", " + literal(editingVersionId)
                + ", 0, " + isDeleted + ", 0)");
    }

    private void insertVersion(long id, long tenantId, long agentId, int versionNo, int isDeleted)
            throws Exception {
        execute("INSERT INTO agent_version (id, tenant_id, agent_id, version_no, status, is_deleted, version)"
                + " VALUES (" + id + ", " + tenantId + ", " + agentId + ", " + versionNo
                + ", 'APPROVED', " + isDeleted + ", 0)");
    }

    private void insertPerm(long tenantId, long agentVersionId, long repoId) throws Exception {
        execute("INSERT INTO agent_repo_perm (tenant_id, agent_version_id, repo_id, perm_level)"
                + " VALUES (" + tenantId + ", " + agentVersionId + ", " + repoId + ", 'READ')");
    }

    private static String literal(Long value) {
        return value == null ? "NULL" : String.valueOf(value);
    }

    @Test
    void onlineVersionReferenceIsReported() throws Exception {
        insertVersion(900L, TENANT, AGENT, 3, 0);
        insertAgent(AGENT, TENANT, "AW全栈开发", 900L, null, 0);
        insertPerm(TENANT, 900L, REPO);

        List<AgentRepoRefDO> refs = dao.listActiveRefsByRepoId(REPO, TENANT);

        assertEquals(1, refs.size());
        AgentRepoRefDO ref = refs.get(0);
        assertEquals(AGENT, ref.getAgentId().longValue());
        assertEquals("AW全栈开发", ref.getAgentName());
        assertEquals(900L, ref.getAgentVersionId().longValue());
        assertEquals(3, ref.getVersionNo().intValue());
        assertEquals(AgentRepoRefDO.TYPE_ONLINE, ref.getRefType());
    }

    @Test
    void editingDraftReferenceIsReported() throws Exception {
        insertVersion(901L, TENANT, AGENT, 4, 0);
        insertAgent(AGENT, TENANT, "AW全栈开发", null, 901L, 0);
        insertPerm(TENANT, 901L, REPO);

        List<AgentRepoRefDO> refs = dao.listActiveRefsByRepoId(REPO, TENANT);

        assertEquals(1, refs.size());
        assertEquals(AgentRepoRefDO.TYPE_EDITING, refs.get(0).getRefType());
        assertEquals(4, refs.get(0).getVersionNo().intValue());
    }

    @Test
    void branchPatternsRoundTripThroughInsertAndScopedUpdate() {
        AgentRepoPermDO permission = new AgentRepoPermDO();
        permission.setTenantId(TENANT);
        permission.setAgentVersionId(900L);
        permission.setRepoId(REPO);
        permission.setPermLevel("WRITE");
        permission.setAllowedBranchPatterns("[\"develop\",\"release/*\"]");

        dao.insert(permission);
        AgentRepoPermDO inserted = dao.listByVersion(900L).get(0);
        assertEquals("[\"develop\",\"release/*\"]", inserted.getAllowedBranchPatterns());
        AgentRepoPermDO locked = dao.findByVersionAndRepoForUpdate(900L, REPO, TENANT);
        assertEquals("WRITE", locked.getPermLevel());

        inserted.setPermLevel("READ");
        inserted.setAllowedBranchPatterns("[\"main\"]");
        assertEquals(1, dao.update(inserted));
        AgentRepoPermDO updated = dao.listByVersion(900L).get(0);
        assertEquals("READ", updated.getPermLevel());
        assertEquals("[\"main\"]", updated.getAllowedBranchPatterns());
    }

    @Test
    void onlineAndEditingReferencesAreBothReported() throws Exception {
        insertVersion(900L, TENANT, AGENT, 3, 0);
        insertVersion(901L, TENANT, AGENT, 4, 0);
        insertAgent(AGENT, TENANT, "AW全栈开发", 900L, 901L, 0);
        insertPerm(TENANT, 900L, REPO);
        insertPerm(TENANT, 901L, REPO);

        List<AgentRepoRefDO> refs = dao.listActiveRefsByRepoId(REPO, TENANT);

        assertEquals(2, refs.size());
        assertEquals(AgentRepoRefDO.TYPE_ONLINE, refs.get(0).getRefType());
        assertEquals(AgentRepoRefDO.TYPE_EDITING, refs.get(1).getRefType());
    }

    @Test
    void draftUnbindBeforePublishStillBlockedByOnlineVersion() throws Exception {
        // 工单验收 1：在线版本 v1 仍绑定仓库，草稿 v2 已解绑但尚未发布
        insertVersion(900L, TENANT, AGENT, 1, 0);
        insertVersion(901L, TENANT, AGENT, 2, 0);
        insertAgent(AGENT, TENANT, "AW全栈开发", 900L, 901L, 0);
        insertPerm(TENANT, 900L, REPO);

        List<AgentRepoRefDO> refs = dao.listActiveRefsByRepoId(REPO, TENANT);

        assertEquals(1, refs.size());
        assertEquals(AgentRepoRefDO.TYPE_ONLINE, refs.get(0).getRefType());
        assertEquals(900L, refs.get(0).getAgentVersionId().longValue());
    }

    @Test
    void publishedUnbindVersionAllowsDeleteEvenIfHistoryKeepsPerm() throws Exception {
        // 工单验收 2：解绑后的 v2 已发布为在线版本，历史 v1 仍保留权限快照
        insertVersion(900L, TENANT, AGENT, 1, 0);
        insertVersion(901L, TENANT, AGENT, 2, 0);
        insertAgent(AGENT, TENANT, "AW全栈开发", 901L, null, 0);
        insertPerm(TENANT, 900L, REPO);

        assertTrue(dao.listActiveRefsByRepoId(REPO, TENANT).isEmpty());
    }

    @Test
    void historicalVersionOnlyDoesNotBlock() throws Exception {
        // 工单验收 3：仅普通历史版本引用仓库
        insertVersion(900L, TENANT, AGENT, 1, 0);
        insertVersion(901L, TENANT, AGENT, 2, 0);
        insertVersion(902L, TENANT, AGENT, 3, 0);
        insertAgent(AGENT, TENANT, "AW全栈开发", 902L, null, 0);
        insertPerm(TENANT, 900L, REPO);
        insertPerm(TENANT, 901L, REPO);

        assertTrue(dao.listActiveRefsByRepoId(REPO, TENANT).isEmpty());
    }

    @Test
    void deletedAgentDoesNotBlock() throws Exception {
        // 工单验收 4：已删除数字人的权限记录不计入有效引用
        insertVersion(900L, TENANT, AGENT, 1, 0);
        insertAgent(AGENT, TENANT, "已删除数字人", 900L, null, 1);
        insertPerm(TENANT, 900L, REPO);

        assertTrue(dao.listActiveRefsByRepoId(REPO, TENANT).isEmpty());
    }

    @Test
    void deletedAgentVersionDoesNotBlock() throws Exception {
        // 工单验收 4：已删除数字人版本的权限记录不计入有效引用
        insertVersion(900L, TENANT, AGENT, 1, 1);
        insertAgent(AGENT, TENANT, "AW全栈开发", 900L, null, 0);
        insertPerm(TENANT, 900L, REPO);

        assertTrue(dao.listActiveRefsByRepoId(REPO, TENANT).isEmpty());
    }

    @Test
    void referencesFromOtherWorkspaceAreIgnored() throws Exception {
        // 工单验收 6：另一工作空间的数字人与权限记录不影响当前工作空间的判定
        insertVersion(910L, OTHER_TENANT, OTHER_AGENT, 1, 0);
        insertAgent(OTHER_AGENT, OTHER_TENANT, "他空间数字人", 910L, null, 0);
        insertPerm(OTHER_TENANT, 910L, REPO);

        insertVersion(900L, TENANT, AGENT, 1, 0);
        insertAgent(AGENT, TENANT, "AW全栈开发", 900L, null, 0);
        insertPerm(OTHER_TENANT, 900L, REPO);

        assertTrue(dao.listActiveRefsByRepoId(REPO, TENANT).isEmpty());
    }

    @Test
    void permissionsOnOtherRepoAreIgnored() throws Exception {
        insertVersion(900L, TENANT, AGENT, 1, 0);
        insertAgent(AGENT, TENANT, "AW全栈开发", 900L, null, 0);
        insertPerm(TENANT, 900L, OTHER_REPO);

        assertTrue(dao.listActiveRefsByRepoId(REPO, TENANT).isEmpty());
        assertEquals(1, dao.listActiveRefsByRepoId(OTHER_REPO, TENANT).size());
    }
}
