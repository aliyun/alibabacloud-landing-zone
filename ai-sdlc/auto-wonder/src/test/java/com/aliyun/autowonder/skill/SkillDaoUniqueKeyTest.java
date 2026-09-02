package com.aliyun.autowonder.skill;

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
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于 H2 的 skill 唯一键 uk_type_name 回归测试，
 * 覆盖“软删除后重新上传/创建同名 skill”“多轮删除重建循环”“历史软删除数据占位”场景。
 */
class SkillDaoUniqueKeyTest {

    static final long TENANT = 10002L;
    static final long USER = 7L;
    static final String JDBC_URL = "jdbc:h2:mem:skill_uk_test;MODE=MySQL;DB_CLOSE_DELAY=-1";

    static SkillDao dao;

    @BeforeAll
    static void initDb() throws Exception {
        try (InputStream in = Resources.getResourceAsStream("mybatis-skill-test-config.xml")) {
            SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(in);
            dao = new SqlSessionTemplate(factory).getMapper(SkillDao.class);
        }
        execScript("skill-schema-h2.sql");
    }

    @BeforeEach
    void cleanTable() throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("DELETE FROM skill");
            st.execute("ALTER TABLE skill ALTER COLUMN id RESTART WITH 10000");
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

    private SkillDO insertSkill(String type, String name) {
        SkillDO skill = new SkillDO();
        skill.setTenantId(TENANT);
        skill.setType(type);
        skill.setName(name);
        skill.setInstallSpec("\"npx\"");
        skill.setDescription("desc");
        skill.setSourceType("INSTALL_SPEC");
        skill.setCreatorId(USER);
        skill.setVersion(0);
        dao.insert(skill);
        return skill;
    }

    private String rawName(long id) throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT name FROM skill WHERE id = " + id)) {
            assertTrue(rs.next());
            return rs.getString(1);
        }
    }

    @Test
    void soft_delete_releases_name_so_same_name_can_be_recreated() throws Exception {
        SkillDO original = insertSkill("SKILL", "team-tool");

        assertEquals(1, dao.softDelete(original.getId(), TENANT, 0, USER));
        // 软删除行必须释放名称占位（置为墓碑值），不再占用 uk_type_name
        assertEquals("#deleted-" + original.getId(), rawName(original.getId()));
        // 重名检查不应再命中已软删除的行
        assertNull(dao.findByTypeAndName(TENANT, "SKILL", "team-tool"));

        // 复现工单场景：删除后重新上传/创建同名 skill，不应唯一键冲突
        SkillDO recreated = insertSkill("SKILL", "team-tool");
        assertNotNull(recreated.getId());
        assertTrue(recreated.getId() > original.getId());
        assertEquals("team-tool", rawName(recreated.getId()));
    }

    @Test
    void repeated_delete_recreate_cycles_all_succeed() throws Exception {
        SkillDO first = insertSkill("MCP", "cycle-server");
        assertEquals(1, dao.softDelete(first.getId(), TENANT, 0, USER));

        SkillDO second = insertSkill("MCP", "cycle-server");
        assertEquals(1, dao.softDelete(second.getId(), TENANT, 0, USER));

        // 多轮删除-重建循环：每次软删除的墓碑值互不冲突，重建始终成功
        SkillDO third = insertSkill("MCP", "cycle-server");
        assertNotNull(third.getId());
        assertEquals("cycle-server", rawName(third.getId()));
    }

    @Test
    void legacy_soft_deleted_row_released_by_migration_dml() throws Exception {
        // 模拟历史数据：软删除行仍以原始名称占位
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO skill (tenant_id, type, name, is_deleted) "
                    + "VALUES (" + TENANT + ", 'SKILL', 'legacy-skill', 1)");
        }

        // 占位存在时，创建同名 skill 会被唯一键拦截
        assertThrows(DataIntegrityViolationException.class, () -> insertSkill("SKILL", "legacy-skill"));

        // 应用迁移 V044 的清理 DML 后，名称被释放，创建成功
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("UPDATE skill SET name = CONCAT('#deleted-', id) WHERE is_deleted = 1");
        }
        SkillDO added = insertSkill("SKILL", "legacy-skill");
        assertNotNull(added.getId());
        assertEquals("legacy-skill", rawName(added.getId()));
    }

    @Test
    void unique_key_still_enforced_between_active_rows() {
        insertSkill("SKILL", "dup-name");
        assertThrows(DataIntegrityViolationException.class, () -> insertSkill("SKILL", "dup-name"));
    }

    @Test
    void soft_delete_with_stale_version_is_rejected() throws Exception {
        SkillDO skill = insertSkill("SKILL", "version-guard");

        // 版本不匹配时软删除不生效，名称占位保持不变
        assertEquals(0, dao.softDelete(skill.getId(), TENANT, 5, USER));
        assertEquals("version-guard", rawName(skill.getId()));

        assertEquals(1, dao.softDelete(skill.getId(), TENANT, 0, USER));
        assertEquals("#deleted-" + skill.getId(), rawName(skill.getId()));
    }
}
