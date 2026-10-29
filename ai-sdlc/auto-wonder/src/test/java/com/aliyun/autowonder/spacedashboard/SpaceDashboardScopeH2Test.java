package com.aliyun.autowonder.spacedashboard;

import org.apache.ibatis.io.Resources;
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
 * 可执行的 scope 判定验证（评审 N2）：真跑 {@code SpaceDashboardDao.xml} 的
 * {@code listScopedWorkspaces}，覆盖平台管理员、owner、ADMIN / READ_WRITE / READ_ONLY、
 * 停用与删除空间、无效成员关系，以及「等级只读 workspace_role」这一迁移约束。
 */
class SpaceDashboardScopeH2Test {

    private static final String JDBC_URL = "jdbc:h2:mem:space_dashboard_scope_test;MODE=MySQL;DB_CLOSE_DELAY=-1";
    private static final long USER = 42L;
    private static final long OTHER = 43L;
    /** 与成员关系无关的空间负责人，保证 OTHER 不会因为 owner 身份进入 scope。 */
    private static final long ELSEWHERE_OWNER = 44L;

    private static SpaceDashboardDao dao;

    @BeforeAll
    static void init() throws Exception {
        try (InputStream input = Resources.getResourceAsStream("mybatis-space-dashboard-test-config.xml")) {
            dao = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(input))
                    .getMapper(SpaceDashboardDao.class);
        }
        executeScript("space-dashboard-scope-schema-h2.sql");
    }

    @BeforeEach
    void clean() throws Exception {
        execute("DELETE FROM org_member", "DELETE FROM workspace_role", "DELETE FROM `org`");
    }

    @Test
    void platformAdminSeesEveryActiveWorkspaceAndNothingElse() throws Exception {
        insertOrg(1, "active", OTHER, 0, 0);
        insertOrg(2, "disabled", OTHER, 1, 0);
        insertOrg(3, "recycled", OTHER, 0, 1);

        assertEquals(List.of(1L), ids(dao.listScopedWorkspaces(OTHER, true)));
    }

    @Test
    void membersSeeOwnedWorkspacesAndAdminRolesOnly() throws Exception {
        insertOrg(10, "owned", USER, 0, 0);
        insertOrg(11, "admin-role", ELSEWHERE_OWNER, 0, 0);
        insertOrg(12, "read-write-role", ELSEWHERE_OWNER, 0, 0);
        insertOrg(13, "read-only-role", ELSEWHERE_OWNER, 0, 0);
        insertOrg(14, "stranger", ELSEWHERE_OWNER, 0, 0);
        insertRole(100, 11, "ADMIN", 0);
        insertRole(101, 12, "READ_WRITE", 0);
        insertRole(102, 13, "READ_ONLY", 0);
        insertMember(200, 11, USER, 0, 0, 100, "ADMIN");
        insertMember(201, 12, USER, 0, 0, 101, "READ_WRITE");
        insertMember(202, 13, USER, 0, 0, 102, "READ_ONLY");

        assertEquals(List.of(11L, 10L), ids(dao.listScopedWorkspaces(USER, false)));
        assertTrue(dao.listScopedWorkspaces(OTHER, false).isEmpty());
    }

    @Test
    void disabledWorkspacesAndInvalidMembershipsAreIgnored() throws Exception {
        insertOrg(20, "disabled", OTHER, 1, 0);
        insertOrg(21, "member-removed", OTHER, 0, 0);
        insertOrg(22, "role-deleted", OTHER, 0, 0);
        insertOrg(23, "member-pending", OTHER, 0, 0);
        insertRole(100, 20, "ADMIN", 0);
        insertRole(101, 21, "ADMIN", 0);
        insertRole(102, 22, "ADMIN", 1);
        insertRole(103, 23, "ADMIN", 0);
        insertMember(200, 20, USER, 0, 0, 100, "ADMIN");
        insertMember(201, 21, USER, 0, 1, 101, "ADMIN");
        insertMember(202, 22, USER, 0, 0, 102, "ADMIN");
        insertMember(203, 23, USER, 1, 0, 103, "ADMIN");

        assertTrue(dao.listScopedWorkspaces(USER, false).isEmpty());
    }

    @Test
    void theAccessLevelIsReadFromTheRoleNotTheLegacyMemberColumn() throws Exception {
        insertOrg(30, "legacy-admin-flag", OTHER, 0, 0);
        insertOrg(31, "role-admin", OTHER, 0, 0);
        insertRole(100, 30, "READ_WRITE", 0);
        insertRole(101, 31, "ADMIN", 0);
        // org_member.access_level 是迁移保留字段：写着 ADMIN 但角色是 READ_WRITE，不得放行。
        insertMember(200, 30, USER, 0, 0, 100, "ADMIN");
        // 反向：成员行旧值 READ_ONLY，但角色是 ADMIN，必须放行。
        insertMember(201, 31, USER, 0, 0, 101, "READ_ONLY");

        assertEquals(List.of(31L), ids(dao.listScopedWorkspaces(USER, false)));
    }

    private static List<Long> ids(List<ScopedWorkspace> rows) {
        return rows.stream().map(ScopedWorkspace::getId).toList();
    }

    private static void insertOrg(long id, String name, long ownerId, int status, int isDeleted) throws Exception {
        execute("INSERT INTO `org` (id, name, owner_id, status, is_deleted) VALUES ("
                + id + ", '" + name + "', " + ownerId + ", " + status + ", " + isDeleted + ")");
    }

    private static void insertRole(long id, long tenantId, String accessLevel, int isDeleted) throws Exception {
        execute("INSERT INTO workspace_role (id, tenant_id, access_level, is_deleted) VALUES ("
                + id + ", " + tenantId + ", '" + accessLevel + "', " + isDeleted + ")");
    }

    private static void insertMember(long id, long tenantId, long userId, int status, int isDeleted,
                                     long roleId, String legacyAccessLevel) throws Exception {
        execute("INSERT INTO org_member (id, tenant_id, user_id, status, access_level, role_id, is_deleted) VALUES ("
                + id + ", " + tenantId + ", " + userId + ", " + status + ", '" + legacyAccessLevel + "', "
                + roleId + ", " + isDeleted + ")");
    }

    private static void executeScript(String resource) throws Exception {
        String sql;
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        execute(sql.split(";"));
    }

    private static void execute(String... sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement statement = connection.createStatement()) {
            for (String item : sql) {
                if (!item.isBlank()) {
                    statement.execute(item);
                }
            }
        }
    }
}
