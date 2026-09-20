package com.aliyun.autowonder.sdlc;

import com.aliyun.autowonder.sdlc.dto.SdlcStepCount;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于 H2 的 countBySdlcIds 聚合回归测试：列表页的「步骤数」依赖它，
 * 覆盖分组计数、软删除排除、无步骤 SDLC 缺席、以及空/ null 入参不产生 "IN ()" 语法错误。
 */
class SdlcStepDaoCountTest {

    static final long TENANT = 10002L;
    static final long USER = 7L;
    // 与 SdlcStepDaoUniqueKeyTest 共用同一份建表脚本和 mapper 配置；两个类都在每个用例前清空表
    static final String JDBC_URL = "jdbc:h2:mem:sdlc_step_uk_test;MODE=MySQL;DB_CLOSE_DELAY=-1";

    static SdlcStepDao dao;

    @BeforeAll
    static void initDb() throws Exception {
        try (InputStream in = Resources.getResourceAsStream("mybatis-sdlc-step-test-config.xml")) {
            SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(in);
            dao = new SqlSessionTemplate(factory).getMapper(SdlcStepDao.class);
        }
        execScript("sdlc-step-schema-h2.sql");
    }

    @BeforeEach
    void cleanTable() throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("DELETE FROM sdlc_step");
            st.execute("ALTER TABLE sdlc_step ALTER COLUMN id RESTART WITH 10000");
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

    private SdlcStepDO insertStep(long sdlcId, int order) {
        SdlcStepDO step = new SdlcStepDO();
        step.setTenantId(TENANT);
        step.setSdlcId(sdlcId);
        step.setStepOrder(order);
        step.setName("step-" + order);
        step.setRequired(Boolean.TRUE);
        step.setCreatorId(USER);
        dao.insert(step);
        return step;
    }

    private static Map<Long, Integer> countsBySdlcId(List<SdlcStepCount> rows) {
        Map<Long, Integer> counts = new HashMap<>();
        for (SdlcStepCount row : rows) {
            counts.put(row.getSdlcId(), row.getCnt());
        }
        return counts;
    }

    @Test
    void groups_active_step_counts_per_sdlc_in_one_query() {
        insertStep(9L, 1);
        insertStep(9L, 2);
        insertStep(9L, 3);
        insertStep(10L, 1);

        // 整页一次查询，结果按 sdlc_id 分组；未出现在结果里的 11L 由服务端补 0
        Map<Long, Integer> counts = countsBySdlcId(dao.countBySdlcIds(List.of(9L, 10L, 11L)));

        assertEquals(Map.of(9L, 3, 10L, 1), counts);
    }

    @Test
    void excludes_soft_deleted_steps_from_the_count() {
        insertStep(9L, 1);
        SdlcStepDO deleted = insertStep(9L, 2);
        insertStep(9L, 3);

        assertEquals(1, dao.softDelete(deleted.getId(), TENANT, USER));

        // 验收口径：软删除的步骤不计入步骤数，与详情页 listBySdlc 的可见步骤一致
        assertEquals(2, countsBySdlcId(dao.countBySdlcIds(List.of(9L))).get(9L));
        assertEquals(2, dao.listBySdlc(9L).size());
    }

    @Test
    void sdlc_without_any_step_produces_no_group_row() {
        insertStep(9L, 1);

        List<SdlcStepCount> rows = dao.countBySdlcIds(List.of(999L));

        assertTrue(rows.isEmpty());
    }

    @Test
    void empty_id_collection_matches_no_row_instead_of_rendering_in() {
        insertStep(9L, 1);

        // 空集合会渲染成 "IN ()"，MySQL/H2 都是语法错误；mapper 用 IN (NULL) 兜底
        assertTrue(dao.countBySdlcIds(List.of()).isEmpty());
    }

    @Test
    void null_id_collection_matches_no_row() {
        insertStep(9L, 1);

        assertTrue(dao.countBySdlcIds(null).isEmpty());
    }
}
