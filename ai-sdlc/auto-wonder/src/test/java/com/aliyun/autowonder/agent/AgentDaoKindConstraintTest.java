package com.aliyun.autowonder.agent;

import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.dao.DataIntegrityViolationException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 基于 H2(MODE=MySQL) 的 agent.kind 真实 NOT NULL 约束回归测试。
 *
 * <p>工单根因：{@code SquadTemplateService.createAgentFromTemplate} 漏设 kind，
 * 而 {@code AgentDao.xml} 的 INSERT 显式绑定 #{kind}，SQL NULL 不会走列 DEFAULT，
 * 在真实 schema（agent.kind VARCHAR(20) NOT NULL DEFAULT 'STANDARD'，V055）上触发约束错误并整体回滚。
 * Mockito 单测无法暴露该问题，本测试直接执行真实 SQL 验证约束与修复后的取值。
 */
class AgentDaoKindConstraintTest {

    static final long TENANT = 10002L;
    static final String JDBC_URL = "jdbc:h2:mem:agent_kind_constraint_test;MODE=MySQL;DB_CLOSE_DELAY=-1";

    static AgentDao dao;

    @BeforeAll
    static void initDb() throws Exception {
        try (InputStream in = Resources.getResourceAsStream("mybatis-agent-kind-test-config.xml")) {
            SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(in);
            dao = new SqlSessionTemplate(factory).getMapper(AgentDao.class);
        }
        execScript("agent-kind-schema-h2.sql");
    }

    @BeforeEach
    void cleanTable() throws Exception {
        exec("DELETE FROM agent");
    }

    /** 修复前路径：kind=null 显式入 SQL → 真实 NOT NULL 约束拒绝，即模板应用失败的根因。 */
    @Test
    void insert_without_kind_violates_not_null_constraint() {
        AgentDO agent = new AgentDO();
        agent.setTenantId(TENANT);
        agent.setName("template-agent-null-kind");
        agent.setStatus("ONLINE");
        agent.setLatestVersionNo(1);
        agent.setCreatorId(7L);
        agent.setVersion(0);

        assertThrows(DataIntegrityViolationException.class, () -> dao.insert(agent));
    }

    /** 修复后路径：模板创建显式 kind=STANDARD（与 AgentService.create 一致），插入并读回成功。 */
    @Test
    void insert_with_standard_kind_succeeds() {
        AgentDO agent = new AgentDO();
        agent.setTenantId(TENANT);
        agent.setName("template-agent-standard");
        agent.setKind("STANDARD");
        agent.setStatus("ONLINE");
        agent.setLatestVersionNo(1);
        agent.setCreatorId(7L);
        agent.setVersion(0);

        dao.insert(agent);

        AgentDO loaded = dao.findById(agent.getId());
        assertNotNull(loaded);
        assertEquals("STANDARD", loaded.getKind());
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
}
