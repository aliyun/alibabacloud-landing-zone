package com.aliyun.autowonder.sdlc;

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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 基于 H2 的 sdlc_step 唯一键 uk_sdlc_order 回归测试，
 * 覆盖“删除末尾步骤后新增”“删除中间步骤后重排”“历史软删除数据占位”场景。
 */
class SdlcStepDaoUniqueKeyTest {

    static final long TENANT = 10002L;
    static final long SDLC_ID = 9L;
    static final long USER = 7L;
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

    private SdlcStepDO insertActiveStep(int order) {
        SdlcStepDO step = new SdlcStepDO();
        step.setTenantId(TENANT);
        step.setSdlcId(SDLC_ID);
        step.setStepOrder(order);
        step.setName("step-" + order);
        step.setRequired(Boolean.TRUE);
        step.setCreatorId(USER);
        dao.insert(step);
        return step;
    }

    private int rawStepOrder(long id) throws Exception {
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT step_order FROM sdlc_step WHERE id = " + id)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }

    @Test
    void soft_delete_releases_positive_order_so_add_after_tail_delete_succeeds() throws Exception {
        insertActiveStep(1);
        insertActiveStep(2);
        SdlcStepDO tail = insertActiveStep(3);

        assertEquals(1, dao.softDelete(tail.getId(), TENANT, USER));
        // 软删除行必须释放正数序号（置为负数），不再占用 uk_sdlc_order
        assertTrue(rawStepOrder(tail.getId()) < 0);

        // 复现工单场景：删除末尾步骤后再次新增同序号步骤，不应唯一键冲突
        SdlcStepDO replacement = insertActiveStep(3);
        assertTrue(replacement.getId() > 0);
        List<SdlcStepDO> active = dao.listBySdlc(SDLC_ID);
        assertEquals(3, active.size());
        assertEquals(List.of(1, 2, 3),
                active.stream().map(SdlcStepDO::getStepOrder).toList());
    }

    @Test
    void delete_middle_then_renumber_keeps_orders_consecutive_and_unique() throws Exception {
        insertActiveStep(1);
        SdlcStepDO middle = insertActiveStep(2);
        SdlcStepDO last = insertActiveStep(3);

        assertEquals(1, dao.softDelete(middle.getId(), TENANT, USER));
        assertTrue(rawStepOrder(middle.getId()) < 0);

        // 服务端重排：把剩余步骤规整为连续序号，不应触发唯一键冲突
        assertEquals(1, dao.updateOrder(last.getId(), TENANT, 2, USER));

        List<SdlcStepDO> active = dao.listBySdlc(SDLC_ID);
        assertEquals(List.of(1, 2),
                active.stream().map(SdlcStepDO::getStepOrder).toList());
    }

    @Test
    void reorder_two_pass_swap_avoids_transient_unique_key_conflict() {
        SdlcStepDO first = insertActiveStep(1);
        SdlcStepDO second = insertActiveStep(2);

        // 与服务端 reorderSteps 一致的两段式：先进负数临时区，再赋目标序号
        assertEquals(1, dao.updateOrder(second.getId(), TENANT, Integer.MIN_VALUE, USER));
        assertEquals(1, dao.updateOrder(first.getId(), TENANT, Integer.MIN_VALUE + 1, USER));
        assertEquals(1, dao.updateOrder(second.getId(), TENANT, 1, USER));
        assertEquals(1, dao.updateOrder(first.getId(), TENANT, 2, USER));

        List<SdlcStepDO> active = dao.listBySdlc(SDLC_ID);
        assertEquals(List.of(second.getId(), first.getId()),
                active.stream().map(SdlcStepDO::getId).toList());
        assertEquals(List.of(1, 2),
                active.stream().map(SdlcStepDO::getStepOrder).toList());
    }

    @Test
    void legacy_soft_deleted_placeholder_released_by_migration_dml() throws Exception {
        insertActiveStep(1);
        // 模拟历史数据：软删除行仍以正数序号占位
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("INSERT INTO sdlc_step (tenant_id, sdlc_id, step_order, name, required, is_deleted) "
                    + "VALUES (" + TENANT + ", " + SDLC_ID + ", 2, 'legacy-deleted', 1, 1)");
        }

        // 占位存在时，新增同序号步骤会被唯一键拦截
        assertThrows(DataIntegrityViolationException.class, () -> insertActiveStep(2));

        // 应用迁移 V038 的清理 DML 后，序号被释放，新增成功
        try (Connection c = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement st = c.createStatement()) {
            st.execute("UPDATE sdlc_step SET step_order = -id WHERE is_deleted = 1 AND step_order > 0");
        }
        SdlcStepDO added = insertActiveStep(2);
        assertTrue(added.getId() > 0);
        assertEquals(2, dao.listBySdlc(SDLC_ID).size());
    }

    @Test
    void unique_key_still_enforced_between_active_rows() {
        insertActiveStep(1);
        assertThrows(DataIntegrityViolationException.class, () -> insertActiveStep(1));
    }
}
