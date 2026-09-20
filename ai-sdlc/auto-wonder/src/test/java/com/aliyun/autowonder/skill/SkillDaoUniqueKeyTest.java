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
import java.util.List;

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
        return insertSkill(TENANT, type, name);
    }

    private SkillDO insertSkill(long tenantId, String type, String name) {
        SkillDO skill = new SkillDO();
        skill.setTenantId(tenantId);
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

    @Test
    void release_soft_deleted_name_frees_legacy_placeholder_so_insert_succeeds() throws Exception {
        // 模拟历史数据：软删除行仍以原始名称占位，未执行 V044 清理
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO skill (tenant_id, type, name, is_deleted) "
                    + "VALUES (" + TENANT + ", 'SKILL', 'legacy-skill', 1)");
        }

        assertEquals(1, dao.releaseSoftDeletedName(TENANT, "SKILL", "legacy-skill"));

        SkillDO added = insertSkill("SKILL", "legacy-skill");
        assertNotNull(added.getId());
        assertEquals("legacy-skill", rawName(added.getId()));
    }

    @Test
    void release_soft_deleted_name_never_touches_active_rows() throws Exception {
        SkillDO active = insertSkill("SKILL", "in-use");

        assertEquals(0, dao.releaseSoftDeletedName(TENANT, "SKILL", "in-use"));
        assertEquals("in-use", rawName(active.getId()));
    }

    @Test
    void release_soft_deleted_name_is_idempotent_and_scoped_to_tenant_and_type() throws Exception {
        SkillDO skill = insertSkill("MCP", "scoped");
        assertEquals(1, dao.softDelete(skill.getId(), TENANT, 0, USER));

        // 已墓碑化后再次释放是幂等空操作
        assertEquals(0, dao.releaseSoftDeletedName(TENANT, "MCP", "scoped"));
        // type 不匹配、tenant 不匹配都不应命中
        assertEquals(0, dao.releaseSoftDeletedName(TENANT, "SKILL", "scoped"));
        assertEquals(0, dao.releaseSoftDeletedName(999L, "MCP", "scoped"));
        assertEquals("#deleted-" + skill.getId(), rawName(skill.getId()));
    }

    @Test
    void count_is_scoped_to_tenant_and_type_and_excludes_soft_deleted() {
        insertSkill("SKILL", "s1");
        insertSkill("MCP", "m1");
        SkillDO removed = insertSkill("SKILL", "s2");
        assertEquals(1, dao.softDelete(removed.getId(), TENANT, 0, USER));

        // 不区分类型：活跃 2 条（s1 + m1），软删除的 s2 不计入
        assertEquals(2L, dao.count(TENANT, null, null, false));
        // 按类型收敛
        assertEquals(1L, dao.count(TENANT, "SKILL", null, false));
        assertEquals(1L, dao.count(TENANT, "MCP", null, false));
    }

    @Test
    void count_ignores_other_tenant_rows_sharing_type_and_name() {
        // uk_type_name 以 (tenant_id,type,name) 为键，跨租户可同名；count 必须只数本租户，避免跨租户串数
        insertSkill(TENANT, "SKILL", "shared-name");
        insertSkill(999L, "SKILL", "shared-name");

        assertEquals(1L, dao.count(TENANT, "SKILL", null, false));
        assertEquals(1L, dao.count(999L, "SKILL", null, false));
        assertEquals(0L, dao.count(12345L, "SKILL", null, false));
    }

    // ---- QA-1 回归护栏：直接执行生产 SkillDao.xml 的 list 语句（H2 真实 SQL），锁死本次修复的 tenant_id 过滤 ----

    @Test
    void list_is_scoped_to_tenant_and_never_leaks_other_tenant_rows() {
        SkillDO mine = insertSkill(TENANT, "SKILL", "mine");
        insertSkill(999L, "SKILL", "other-tenant");

        // 本租户只看到自己的记录；若删掉 list 里的 AND tenant_id，这里会串出 2 条，用例即失败
        List<SkillDO> result = dao.list(TENANT, null, null, false, 0, 20);
        assertEquals(1, result.size());
        assertEquals("mine", result.get(0).getName());
        assertEquals(mine.getId(), result.get(0).getId());

        // 另一租户同样只看到自己的记录，跨租户互不泄漏
        List<SkillDO> other = dao.list(999L, null, null, false, 0, 20);
        assertEquals(1, other.size());
        assertEquals("other-tenant", other.get(0).getName());

        // 无任何记录的租户返回空列表
        assertTrue(dao.list(12345L, null, null, false, 0, 20).isEmpty());
    }

    @Test
    void list_pages_by_id_desc_within_tenant_and_is_not_squeezed_out_by_other_tenant_high_ids() {
        // 本租户 3 条，id 依次递增：page-a(10000) < page-b(10001) < page-c(10002)
        SkillDO a = insertSkill(TENANT, "SKILL", "page-a");
        SkillDO b = insertSkill(TENANT, "SKILL", "page-b");
        SkillDO c = insertSkill(TENANT, "SKILL", "page-c");

        // 首页（limit=2）按 id DESC 取最新的 c、b
        List<SkillDO> page1 = dao.list(TENANT, null, null, false, 0, 2);
        assertEquals(2, page1.size());
        assertEquals(c.getId(), page1.get(0).getId());
        assertEquals(b.getId(), page1.get(1).getId());

        // 第 2 页能翻到最早那条 a —— 复现并锁死工单原症状「有记录却翻不到」的修复点
        List<SkillDO> page2 = dao.list(TENANT, null, null, false, 2, 1);
        assertEquals(1, page2.size());
        assertEquals(a.getId(), page2.get(0).getId());

        // 另一租户插入 5 条更大 id 的记录：加了 tenant_id 过滤后，本租户分页结果完全不受挤占
        for (int i = 0; i < 5; i++) {
            insertSkill(999L, "SKILL", "noise-" + i);
        }
        List<SkillDO> page1After = dao.list(TENANT, null, null, false, 0, 2);
        assertEquals(2, page1After.size());
        assertEquals(c.getId(), page1After.get(0).getId());
        assertEquals(b.getId(), page1After.get(1).getId());
        List<SkillDO> page2After = dao.list(TENANT, null, null, false, 2, 1);
        assertEquals(1, page2After.size());
        assertEquals(a.getId(), page2After.get(0).getId());
        // 本租户总数仍为 3，未被其它租户的高 id 记录抬高
        assertEquals(3L, dao.count(TENANT, null, null, false));
    }

    @Test
    void list_and_count_share_the_same_where_for_tenant_type_and_soft_delete() {
        insertSkill(TENANT, "SKILL", "s1");
        insertSkill(TENANT, "SKILL", "s2");
        insertSkill(TENANT, "MCP", "m1");
        SkillDO removed = insertSkill(TENANT, "SKILL", "s3");
        insertSkill(999L, "SKILL", "other");
        assertEquals(1, dao.softDelete(removed.getId(), TENANT, 0, USER));

        // 不分类型：list 与 count 必须一致（活跃 s1/s2/m1=3；s3 软删除、other 跨租户都不计）
        List<SkillDO> all = dao.list(TENANT, null, null, false, 0, 100);
        assertEquals(3, all.size());
        assertEquals(dao.count(TENANT, null, null, false), (long) all.size());

        // 按类型：list 与 count 仍共用同一 where（is_deleted=0 AND tenant_id AND type）
        List<SkillDO> skills = dao.list(TENANT, "SKILL", null, false, 0, 100);
        assertEquals(2, skills.size());
        assertEquals(dao.count(TENANT, "SKILL", null, false), (long) skills.size());
        List<SkillDO> mcps = dao.list(TENANT, "MCP", null, false, 0, 100);
        assertEquals(1, mcps.size());
        assertEquals(dao.count(TENANT, "MCP", null, false), (long) mcps.size());
    }

    @Test
    void list_excludes_soft_deleted_rows() {
        SkillDO keep = insertSkill(TENANT, "SKILL", "keep");
        SkillDO gone = insertSkill(TENANT, "SKILL", "gone");
        assertEquals(2, dao.list(TENANT, null, null, false, 0, 20).size());

        assertEquals(1, dao.softDelete(gone.getId(), TENANT, 0, USER));

        List<SkillDO> after = dao.list(TENANT, null, null, false, 0, 20);
        assertEquals(1, after.size());
        assertEquals(keep.getId(), after.get(0).getId());
        // 软删除的记录（已被墓碑化改名）绝不出现在 list 结果里
        assertTrue(after.stream().noneMatch(s -> s.getId().equals(gone.getId())));
    }
}
