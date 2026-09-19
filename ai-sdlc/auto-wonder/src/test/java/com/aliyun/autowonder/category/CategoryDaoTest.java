package com.aliyun.autowonder.category;

import com.aliyun.autowonder.skill.SkillDao;
import com.aliyun.autowonder.skill.SkillDO;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于 H2 的分类 DAO 回归测试，直接执行生产 CategoryDao.xml / SkillCategoryRefDao.xml /
 * SkillDao.xml 的真实 SQL，锁死：
 * 1) uk_tenant_parent_name 对 NULL 父分类不去重（顶级同名靠应用层锁定读串行化）；
 * 2) uk_tenant_asset 收敛一资产一行，upsert 幂等且可替换主分类；
 * 3) skill 列表的分类过滤 / 未分类过滤只作用于本租户且忽略墓碑关联。
 */
class CategoryDaoTest {

    static final long TENANT = 10002L;
    static final long USER = 7L;
    static final String JDBC_URL = "jdbc:h2:mem:category_dao_test;MODE=MySQL;DB_CLOSE_DELAY=-1";

    static CategoryDao categoryDao;
    static SkillCategoryRefDao refDao;
    static SkillDao skillDao;

    @BeforeAll
    static void initDb() throws Exception {
        try (InputStream in = Resources.getResourceAsStream("mybatis-category-test-config.xml")) {
            SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(in);
            SqlSessionTemplate template = new SqlSessionTemplate(factory);
            categoryDao = template.getMapper(CategoryDao.class);
            refDao = template.getMapper(SkillCategoryRefDao.class);
            skillDao = template.getMapper(SkillDao.class);
        }
        execScript("skill-schema-h2.sql");
        execScript("category-schema-h2.sql");
    }

    @BeforeEach
    void cleanTables() throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("DELETE FROM asset_category_ref");
            st.execute("DELETE FROM asset_category");
            st.execute("DELETE FROM skill");
            st.execute("ALTER TABLE asset_category ALTER COLUMN id RESTART WITH 10000");
            st.execute("ALTER TABLE asset_category_ref ALTER COLUMN id RESTART WITH 20000");
            st.execute("ALTER TABLE skill ALTER COLUMN id RESTART WITH 30000");
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

    private void exec(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private long rawCount(String sql) throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            assertTrue(rs.next());
            return rs.getLong(1);
        }
    }

    private CategoryDO insertCategory(Long parentId, String name) {
        return insertCategory(TENANT, parentId, name);
    }

    private CategoryDO insertCategory(long tenantId, Long parentId, String name) {
        CategoryDO node = new CategoryDO();
        node.setTenantId(tenantId);
        node.setParentId(parentId);
        node.setName(name);
        node.setCreatorId(USER);
        categoryDao.insert(node);
        return node;
    }

    private SkillDO insertSkill(String type, String name) {
        SkillDO skill = new SkillDO();
        skill.setTenantId(TENANT);
        skill.setType(type);
        skill.setName(name);
        skill.setInstallSpec("\"npx\"");
        skill.setSourceType("INSTALL_SPEC");
        skill.setCreatorId(USER);
        skill.setVersion(0);
        skillDao.insert(skill);
        return skill;
    }

    @Test
    void insertGeneratesIdAndRoundTripsThroughLockedRead() {
        CategoryDO node = insertCategory(null, "编码");

        assertNotNull(node.getId());
        CategoryDO loaded = categoryDao.findByIdAndTenantForUpdate(node.getId(), TENANT);
        assertEquals("编码", loaded.getName());
        assertNull(loaded.getParentId());
        assertEquals(0, loaded.getVersion());
    }

    @Test
    void findByIdScopesToTenantAndExcludesSoftDeletedRows() throws Exception {
        CategoryDO node = insertCategory(null, "编码");

        assertNull(categoryDao.findByIdAndTenantForUpdate(node.getId(), 999L));
        assertNull(categoryDao.findByIdAndTenant(node.getId(), 999L));

        exec("UPDATE asset_category SET is_deleted = 1 WHERE id = " + node.getId());
        assertNull(categoryDao.findByIdAndTenantForUpdate(node.getId(), TENANT));
        assertNull(categoryDao.findByIdAndTenant(node.getId(), TENANT));
    }

    @Test
    void listByTenantScopesToTenantAndOrdersByNameThenId() {
        insertCategory(null, "b");
        CategoryDO first = insertCategory(null, "a");
        CategoryDO second = insertCategory(null, "a");
        insertCategory(null, "c");
        insertCategory(999L, null, "别的租户");

        List<CategoryDO> rows = categoryDao.listByTenant(TENANT);

        assertEquals(4, rows.size());
        assertEquals(first.getId(), rows.get(0).getId());
        assertEquals(second.getId(), rows.get(1).getId());
        assertEquals("b", rows.get(2).getName());
        assertEquals("c", rows.get(3).getName());
    }

    @Test
    void findSiblingByNameDistinguishesNullParentFromExplicitParent() {
        CategoryDO root = insertCategory(null, "前端");
        CategoryDO child = insertCategory(root.getId(), "前端");

        assertEquals(root.getId(), categoryDao.findSiblingByNameForUpdate(TENANT, null, "前端").getId());
        assertEquals(child.getId(),
                categoryDao.findSiblingByNameForUpdate(TENANT, root.getId(), "前端").getId());
        assertNull(categoryDao.findSiblingByNameForUpdate(TENANT, null, "不存在"));
        assertNull(categoryDao.findSiblingByNameForUpdate(999L, null, "前端"));
    }

    @Test
    void uniqueKeyRejectsDuplicateSiblingUnderSameExplicitParent() {
        CategoryDO parent = insertCategory(null, "编码");
        insertCategory(parent.getId(), "前端");

        assertThrows(DataIntegrityViolationException.class,
                () -> insertCategory(parent.getId(), "前端"));
    }

    @Test
    void topLevelDuplicateNamesRelyOnAppLevelGuardBecauseUkSkipsNullParent() {
        // MySQL 唯一键对 NULL parent_id 不去重：顶级同名在 DB 层可以共存，
        // 判重只能靠 findSiblingByNameForUpdate 的锁定读 + 写事务串行化
        insertCategory(null, "顶级");
        insertCategory(null, "顶级");

        assertEquals(2, categoryDao.listByTenant(TENANT).size());
        assertNotNull(categoryDao.findSiblingByNameForUpdate(TENANT, null, "顶级"));
    }

    @Test
    void countChildrenCountsOnlyLiveChildrenOfSameParentInTenant() throws Exception {
        CategoryDO parent = insertCategory(null, "编码");
        CategoryDO other = insertCategory(null, "测试");
        insertCategory(parent.getId(), "前端");
        insertCategory(parent.getId(), "工程");
        insertCategory(other.getId(), "前端");
        insertCategory(999L, parent.getId(), "跨租户");
        CategoryDO deleted = insertCategory(parent.getId(), "废弃");
        exec("UPDATE asset_category SET is_deleted = 1 WHERE id = " + deleted.getId());

        assertEquals(2, categoryDao.listChildIdsForUpdate(TENANT, parent.getId()).size());
    }

    @Test
    void updateBumpsVersionAndRejectsStaleVersion() {
        CategoryDO node = insertCategory(null, "编码");

        assertEquals(1, categoryDao.update(node.getId(), TENANT, null, "研发", "说明", 0, USER));

        CategoryDO reloaded = categoryDao.findByIdAndTenantForUpdate(node.getId(), TENANT);
        assertEquals("研发", reloaded.getName());
        assertEquals("说明", reloaded.getDescription());
        assertEquals(1, reloaded.getVersion());

        // 旧版本号再更新：0 行生效，内容不变
        assertEquals(0, categoryDao.update(node.getId(), TENANT, null, "旧", null, 0, USER));
        assertEquals("研发", categoryDao.findByIdAndTenantForUpdate(node.getId(), TENANT).getName());
    }

    @Test
    void deleteIsPhysicalAndFreesTheNameSlotImmediately() {
        CategoryDO parent = insertCategory(null, "编码");
        CategoryDO child = insertCategory(parent.getId(), "前端");

        assertEquals(1, categoryDao.delete(child.getId(), TENANT));
        assertNull(categoryDao.findByIdAndTenantForUpdate(child.getId(), TENANT));

        // 空节点物理删除不留墓碑，同名槽位立即可复用
        CategoryDO revived = insertCategory(parent.getId(), "前端");
        assertNotEquals(child.getId(), revived.getId());
    }

    @Test
    void upsertInsertsReplacesAndIsIdempotentPerUniqueAssetRow() {
        SkillDO skill = insertSkill("SKILL", "s");
        CategoryDO cat1 = insertCategory(null, "一");
        CategoryDO cat2 = insertCategory(null, "二");

        assertEquals(1, refDao.upsert(TENANT, "SKILL", skill.getId(), cat1.getId(), USER));
        // 重复设置同一分类命中 uk_tenant_asset 的 UPDATE 分支；H2 与 MySQL 的受影响行数语义不同
        // （MySQL 更新算 2 行），所以断言可观察的不变量而不是返回行数
        refDao.upsert(TENANT, "SKILL", skill.getId(), cat1.getId(), USER);
        assertEquals(cat1.getId(), refDao.findByAsset(TENANT, "SKILL", skill.getId()).getCategoryId());
        assertEquals(1L, rawCountByAsset(skill.getId()));

        // 替换主分类：同一资产仍只有一行关联
        refDao.upsert(TENANT, "SKILL", skill.getId(), cat2.getId(), USER);
        assertEquals(cat2.getId(), refDao.findByAsset(TENANT, "SKILL", skill.getId()).getCategoryId());
        assertTrue(refDao.listIdsByCategoryForUpdate(TENANT, cat1.getId()).isEmpty());
        assertEquals(1, refDao.listIdsByCategoryForUpdate(TENANT, cat2.getId()).size());
        assertEquals(1L, rawCountByAsset(skill.getId()));
    }

    private long rawCountByAsset(long assetId) {
        try {
            return rawCount("SELECT COUNT(*) FROM asset_category_ref WHERE asset_id = " + assetId);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void findByAssetFiltersTenantAndAssetType() {
        SkillDO skill = insertSkill("SKILL", "s");
        CategoryDO cat = insertCategory(null, "一");
        refDao.upsert(TENANT, "SKILL", skill.getId(), cat.getId(), USER);
        refDao.upsert(TENANT, "MCP", skill.getId(), cat.getId(), USER);

        assertNotNull(refDao.findByAsset(TENANT, "SKILL", skill.getId()));
        assertNotNull(refDao.findByAsset(TENANT, "MCP", skill.getId()));
        assertNull(refDao.findByAsset(999L, "SKILL", skill.getId()));
    }

    @Test
    void listByAssetsReturnsOnlyRequestedLiveAssets() {
        CategoryDO cat = insertCategory(null, "一");
        SkillDO s1 = insertSkill("SKILL", "s1");
        SkillDO s2 = insertSkill("SKILL", "s2");
        SkillDO s3 = insertSkill("SKILL", "s3");
        refDao.upsert(TENANT, "SKILL", s1.getId(), cat.getId(), USER);
        refDao.upsert(TENANT, "SKILL", s3.getId(), cat.getId(), USER);

        List<SkillCategoryRefDO> refs = refDao.listByAssets(TENANT, "SKILL",
                List.of(s1.getId(), s2.getId(), s3.getId()));

        assertEquals(2, refs.size());
        assertTrue(refs.stream().allMatch(ref -> cat.getId().equals(ref.getCategoryId())));
        // 空列表必须返回空结果，而不是全表
        assertTrue(refDao.listByAssets(TENANT, "SKILL", List.of()).isEmpty());
    }

    @Test
    void deleteByAssetRemovesTagAndRetagReinserts() {
        SkillDO skill = insertSkill("SKILL", "s");
        CategoryDO cat = insertCategory(null, "一");
        refDao.upsert(TENANT, "SKILL", skill.getId(), cat.getId(), USER);

        // 跨租户删除互不影响
        assertEquals(0, refDao.deleteByAsset(999L, "SKILL", skill.getId()));
        assertNotNull(refDao.findByAsset(TENANT, "SKILL", skill.getId()));

        assertEquals(1, refDao.deleteByAsset(TENANT, "SKILL", skill.getId()));
        assertNull(refDao.findByAsset(TENANT, "SKILL", skill.getId()));
        assertTrue(refDao.listIdsByCategoryForUpdate(TENANT, cat.getId()).isEmpty());

        // 取消打标物理删除，重新打标再次插入
        refDao.upsert(TENANT, "SKILL", skill.getId(), cat.getId(), USER);
        assertEquals(cat.getId(), refDao.findByAsset(TENANT, "SKILL", skill.getId()).getCategoryId());
    }

    @Test
    void countByCategoryScopesToTenant() {
        CategoryDO cat = insertCategory(null, "一");
        SkillDO mine = insertSkill("SKILL", "mine");
        SkillDO otherTenant = insertSkill("SKILL", "other");
        refDao.upsert(TENANT, "SKILL", mine.getId(), cat.getId(), USER);
        refDao.upsert(999L, "SKILL", otherTenant.getId(), cat.getId(), USER);

        assertEquals(1, refDao.listIdsByCategoryForUpdate(TENANT, cat.getId()).size());
    }

    @Test
    void skillListFiltersByCategoryIdsThroughRealSql() {
        CategoryDO cat = insertCategory(null, "编码");
        SkillDO tagged1 = insertSkill("SKILL", "t1");
        SkillDO tagged2 = insertSkill("MCP", "t2");
        SkillDO untagged = insertSkill("SKILL", "u");
        refDao.upsert(TENANT, "SKILL", tagged1.getId(), cat.getId(), USER);
        refDao.upsert(TENANT, "SKILL", tagged2.getId(), cat.getId(), USER);

        List<SkillDO> rows = skillDao.list(TENANT, null, List.of(cat.getId()), false, 0, 20);
        assertEquals(2, rows.size());
        assertTrue(rows.stream().noneMatch(s -> s.getId().equals(untagged.getId())));

        // 分类过滤与类型过滤叠加：MCP 的打标技能不计入 SKILL 类型
        assertEquals(1L, skillDao.count(TENANT, "SKILL", List.of(cat.getId()), false));
        assertEquals(1, skillDao.list(TENANT, "SKILL", List.of(cat.getId()), false, 0, 20).size());

        // 分类集合过滤（含子分类展开后的多个 id）同样生效
        CategoryDO cat2 = insertCategory(null, "测试");
        List<SkillDO> both = skillDao.list(TENANT, null, List.of(cat.getId(), cat2.getId()), false, 0, 20);
        assertEquals(2, both.size());
    }

    @Test
    void skillListUncategorizedReturnsOnlyUntaggedRows() {
        CategoryDO cat = insertCategory(null, "编码");
        SkillDO tagged = insertSkill("SKILL", "t1");
        SkillDO untagged = insertSkill("SKILL", "u");
        refDao.upsert(TENANT, "SKILL", tagged.getId(), cat.getId(), USER);

        List<SkillDO> rows = skillDao.list(TENANT, null, null, true, 0, 20);

        assertEquals(1, rows.size());
        assertEquals(untagged.getId(), rows.get(0).getId());
        assertEquals(1L, skillDao.count(TENANT, null, null, true));
    }

    @Test
    void skillListWithoutCategoryFiltersKeepsLegacyBehavior() {
        CategoryDO cat = insertCategory(null, "编码");
        SkillDO tagged = insertSkill("SKILL", "t1");
        SkillDO untagged = insertSkill("SKILL", "u");
        refDao.upsert(TENANT, "SKILL", tagged.getId(), cat.getId(), USER);

        assertEquals(2, skillDao.list(TENANT, null, null, false, 0, 20).size());
        assertEquals(2L, skillDao.count(TENANT, null, null, false));
    }

    @Test
    void softDeletedRefsAreIgnoredByCategoryFilters() throws Exception {
        CategoryDO cat = insertCategory(null, "编码");
        SkillDO tagged = insertSkill("SKILL", "t1");
        refDao.upsert(TENANT, "SKILL", tagged.getId(), cat.getId(), USER);
        exec("UPDATE asset_category_ref SET is_deleted = 1 WHERE asset_id = " + tagged.getId());

        // 墓碑关联不命中分类过滤
        assertTrue(skillDao.list(TENANT, null, List.of(cat.getId()), false, 0, 20).isEmpty());
        assertEquals(0L, skillDao.count(TENANT, null, List.of(cat.getId()), false));
        assertTrue(refDao.listIdsByCategoryForUpdate(TENANT, cat.getId()).isEmpty());
        // 也不把技能算作“已分类”：未分类筛选要把它找回来
        assertEquals(1, skillDao.list(TENANT, null, null, true, 0, 20).size());
    }
}
