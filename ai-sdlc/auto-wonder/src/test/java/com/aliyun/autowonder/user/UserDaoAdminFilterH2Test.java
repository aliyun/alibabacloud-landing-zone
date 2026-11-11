package com.aliyun.autowonder.user;

import com.aliyun.autowonder.feature.PlatformFeatureSwitchService;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.ApplicationEventPublisher;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 平台用户管理「空间创建权限」筛选的真实 SQL 行为。
 *
 * <p>{@code password_hash} 可空（纯 SSO 账号），SQL 里的 {@code password_hash <> 'DEACTIVATED'} 对 NULL
 * 求值仍是 NULL；若两分支直接对可空谓词取反，status = 1 且无密码的停用账号会同时脱离「有权限」与
 * 「无权限」筛选。这里用 H2（MySQL 模式）执行真实语句，断言两个筛选严格互补，且每个返回行的
 * {@code canCreateWorkspace} 与筛选归属一致。
 */
class UserDaoAdminFilterH2Test {

    private static final String JDBC_URL = "jdbc:h2:mem:user_admin_filter;MODE=MySQL;DB_CLOSE_DELAY=-1";
    private static final long NORMAL = 1L;
    private static final long LEGACY_SSO_ACTIVE = 2L;
    private static final long DISABLED_NO_PASSWORD = 3L;
    private static final long DISABLED_SENTINEL = 4L;
    private static final long PENDING = 5L;
    private static final long DELETED = 6L;
    private static final long ADMIN = 7L;

    private static UserDao userDao;
    private WorkspaceCreationPermissionService permissionService;

    @BeforeAll
    static void init() throws Exception {
        try (InputStream input = Resources.getResourceAsStream("mybatis-user-admin-test-config.xml")) {
            SqlSessionTemplate template = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(input));
            userDao = template.getMapper(UserDao.class);
        }
        executeScript("user-admin-schema-h2.sql");
    }

    @BeforeEach
    void seedAccounts() throws Exception {
        execute("DELETE FROM `user`");
        insert(NORMAL, "normal", "status", "0");
        insert(LEGACY_SSO_ACTIVE, "legacy-sso", "status, password_hash, workspace_creation_granted",
                "1, 'hash', 1");
        insert(DISABLED_NO_PASSWORD, "disabled-null",
                "status, password_hash, workspace_creation_granted", "1, NULL, 1");
        insert(DISABLED_SENTINEL, "disabled-sentinel",
                "status, password_hash, workspace_creation_granted", "1, 'DEACTIVATED', 1");
        insert(PENDING, "pending", "status, workspace_creation_granted", "2, 1");
        insert(DELETED, "deleted", "status, password_hash, workspace_creation_granted, is_deleted",
                "0, 'hash', 1, 1");
        insert(ADMIN, "admin", "status, password_hash, is_admin", "0, 'hash', 1");

        permissionService = new WorkspaceCreationPermissionService(userDao,
                mock(PlatformFeatureSwitchService.class), mock(WorkspaceCreationRequestDao.class),
                mock(ApplicationEventPublisher.class));
    }

    @Test
    void theTwoPermissionFiltersPartitionEveryAccountEvenWithoutAPassword() {
        assertPartition(false,
                Set.of(NORMAL, LEGACY_SSO_ACTIVE, DISABLED_NO_PASSWORD, DISABLED_SENTINEL,
                        PENDING, DELETED, ADMIN),
                Set.of(LEGACY_SSO_ACTIVE, ADMIN),
                Set.of(NORMAL, DISABLED_NO_PASSWORD, DISABLED_SENTINEL, PENDING, DELETED));

        // 关键回归：status = 1 + NULL hash 的停用账号必须落在「无权限」，不能两个筛选都查不到。
        List<UserDO> denied = userDao.searchUsersForAdmin(
                null, null, null, false, false, 0, 100);
        assertTrue(denied.stream().anyMatch(user -> DISABLED_NO_PASSWORD == user.getId()),
                "a status=1 account without a password must still appear under the no-permission filter");
        assertFalse(permissionService.canCreate(find(denied, DISABLED_NO_PASSWORD), false),
                "the row flag must agree with the no-permission filter");
    }

    @Test
    void theGlobalSwitchOnlyWidensThePermissionSetForActiveAccounts() {
        assertPartition(true,
                Set.of(NORMAL, LEGACY_SSO_ACTIVE, DISABLED_NO_PASSWORD, DISABLED_SENTINEL,
                        PENDING, DELETED, ADMIN),
                Set.of(NORMAL, LEGACY_SSO_ACTIVE, ADMIN),
                Set.of(DISABLED_NO_PASSWORD, DISABLED_SENTINEL, PENDING, DELETED));
    }

    private void assertPartition(boolean defaultAllowed, Set<Long> all, Set<Long> expectedAllowed,
                                 Set<Long> expectedDenied) {
        List<UserDO> allowed = userDao.searchUsersForAdmin(null, null, null, true, defaultAllowed, 0, 100);
        List<UserDO> denied = userDao.searchUsersForAdmin(null, null, null, false, defaultAllowed, 0, 100);

        assertEquals(expectedAllowed, ids(allowed),
                "canCreateWorkspace=true must list exactly the accounts the row flag marks creatable");
        assertEquals(expectedDenied, ids(denied),
                "canCreateWorkspace=false must contain every remaining account");

        Set<Long> allowedIds = ids(allowed);
        Set<Long> deniedIds = ids(denied);
        Set<Long> union = new LinkedHashSet<>(allowedIds);
        union.addAll(deniedIds);
        assertEquals(all, union, "the two filters together must cover every row");
        assertTrue(allowedIds.stream().noneMatch(deniedIds::contains),
                "the filters must stay disjoint");

        long allowedTotal = userDao.countUsersForAdmin(null, null, null, true, defaultAllowed);
        long deniedTotal = userDao.countUsersForAdmin(null, null, null, false, defaultAllowed);
        assertEquals((long) allowed.size(), allowedTotal, "the total must match the page predicate");
        assertEquals((long) denied.size(), deniedTotal, "the total must match the page predicate");

        for (UserDO user : allowed) {
            assertTrue(permissionService.canCreate(user, defaultAllowed),
                    "listed as creatable but the row flag is false: " + user.getId());
        }
        for (UserDO user : denied) {
            assertFalse(permissionService.canCreate(user, defaultAllowed),
                    "listed as not creatable but the row flag is true: " + user.getId());
        }
    }

    private static UserDO find(List<UserDO> users, long id) {
        return users.stream().filter(user -> id == user.getId()).findFirst().orElseThrow();
    }

    private static Set<Long> ids(List<UserDO> users) {
        return new LinkedHashSet<>(users.stream().map(UserDO::getId).collect(Collectors.toList()));
    }

    private static void insert(long id, String username, String columns, String values) throws Exception {
        execute("INSERT INTO `user` (id, username, " + columns + ") VALUES (" + id + ", '"
                + username + "', " + values + ")");
    }

    private static void executeScript(String resource) throws Exception {
        String sql;
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        execute(sql.split(";"));
    }

    private static void execute(String... statements) throws Exception {
        try (Connection connection = DriverManager.getConnection(JDBC_URL, "sa", "");
             Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                if (!sql.isBlank()) {
                    statement.execute(sql);
                }
            }
        }
    }
}
