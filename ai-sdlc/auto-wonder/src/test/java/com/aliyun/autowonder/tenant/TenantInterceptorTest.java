package com.aliyun.autowonder.tenant;

import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.notification.NotificationDao;
import com.aliyun.autowonder.spacedashboard.SpaceDashboardDao;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.apache.ibatis.executor.statement.RoutingStatementHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantInterceptorTest {

    private static final long TENANT_ID = 42L;

    /** 空间级看板的语句按 scope 跨空间聚合，必须逐条登记豁免。 */
    private static final List<String> SPACE_DASHBOARD_STATEMENTS = List.of(
            SpaceDashboardDao.class.getName() + ".listScopedWorkspaces",
            SpaceDashboardDao.class.getName() + ".countAgents",
            SpaceDashboardDao.class.getName() + ".countSquads",
            SpaceDashboardDao.class.getName() + ".countDistinctHumans",
            SpaceDashboardDao.class.getName() + ".countCreatedWorkitems",
            SpaceDashboardDao.class.getName() + ".countCompletedWorkitems",
            SpaceDashboardDao.class.getName() + ".countInProgressWorkitems",
            SpaceDashboardDao.class.getName() + ".sumCredits");

    /**
     * 平台级通知以 tenant_id = 0 持久化；读取语句自身按 recipient_id + scope 限定归属，
     * 若再被租户改写追加当前空间条件，管理员绑定或切换空间后就看不到本人平台通知。
     */
    private static final List<String> NOTIFICATION_READ_STATEMENTS = List.of(
            NotificationDao.class.getName() + ".listByRecipient",
            NotificationDao.class.getName() + ".findByIdAndRecipient",
            NotificationDao.class.getName() + ".countUnread",
            NotificationDao.class.getName() + ".countByRecipient");

    @Test
    void ownershipLookupIsExemptFromTenantRewrite() {
        String sql = "SELECT tenant_id FROM workitem WHERE id = ? AND is_deleted = 0 LIMIT 1";

        String scoped = TenantInterceptor.applyTenantScope(
                WorkitemDao.class.getName() + ".findTenantIdById", sql, TENANT_ID);

        assertSame(sql, scoped, "the registered ownership lookup must keep its cross-workspace reach");
    }

    @Test
    void unregisteredWorkitemReadsStillReceiveTenantScope() {
        String sql = "SELECT * FROM workitem WHERE id = ? AND is_deleted = 0";

        String scoped = TenantInterceptor.applyTenantScope(
                WorkitemDao.class.getName() + ".findById", sql, TENANT_ID);

        assertTrue(scoped.contains("tenant_id = 42"), scoped);
    }

    @Test
    void nonTenantStatementsStayUntouched() {
        String sql = "SELECT * FROM org WHERE id = ?";

        String scoped = TenantInterceptor.applyTenantScope(
                "com.aliyun.autowonder.workspace.WorkspaceDao.findById", sql, TENANT_ID);

        assertEquals(sql, scoped);
    }

    @Test
    void allowlistIsExplicitAndContainsNoPrefixOrWildcard() {
        Set<String> expected = new HashSet<>(SPACE_DASHBOARD_STATEMENTS);
        expected.add(WorkitemDao.class.getName() + ".findTenantIdById");
        expected.addAll(NOTIFICATION_READ_STATEMENTS);
        assertEquals(expected, TenantInterceptor.CROSS_TENANT_STATEMENTS,
                "every cross-tenant statement must be added deliberately together with its own authorization checks");
        for (String statement : TenantInterceptor.CROSS_TENANT_STATEMENTS) {
            assertFalse(statement.endsWith(".*") || statement.contains("*"),
                    "wildcards would silently exempt whole mappers: " + statement);
        }
    }

    @Test
    void platformNotificationReadsKeepTheirCrossWorkspaceReachWhenAWorkspaceIsBound() throws Throwable {
        AutoWonderContext.get().setCurrentWorkspaceId(TENANT_ID);
        try {
            String sql = "SELECT * FROM notification WHERE recipient_id = ? "
                    + "AND ((scope = 'WORKSPACE' AND tenant_id = ?) OR scope = 'PLATFORM')";
            for (String statementId : NOTIFICATION_READ_STATEMENTS) {
                StatementHandler handler = statementHandler(statementId, sql, SqlCommandType.SELECT);

                new TenantInterceptor().intercept(invocationOn(handler));

                assertEquals(sql, handler.getBoundSql().getSql(),
                        statementId + " must not gain the current workspace filter: platform "
                                + "notifications stay visible after switching workspaces");
            }
        } finally {
            AutoWonderContext.destroy();
        }
    }

    @Test
    void unregisteredNotificationReadsStillReceiveTenantScope() {
        String sql = "SELECT * FROM notification WHERE recipient_id = ?";

        String scoped = TenantInterceptor.applyTenantScope(
                NotificationDao.class.getName() + ".listForAdmin", sql, TENANT_ID);

        assertTrue(scoped.contains("tenant_id = 42"),
                "only the four ownership-checked reads are exempt, got: " + scoped);
    }

    @Test
    void interceptKeepsTheRegisteredOwnershipLookupCrossWorkspaceInTheRealPluginPath() throws Throwable {
        String sql = "SELECT tenant_id FROM workitem WHERE id = ? AND is_deleted = 0 LIMIT 1";
        AutoWonderContext.get().setCurrentWorkspaceId(TENANT_ID);
        try {
            StatementHandler handler = statementHandler(
                    WorkitemDao.class.getName() + ".findTenantIdById", sql, SqlCommandType.SELECT);

            new TenantInterceptor().intercept(invocationOn(handler));

            assertEquals(sql, handler.getBoundSql().getSql(),
                    "the plugin must keep the registered ownership lookup out of the tenant rewrite");
        } finally {
            AutoWonderContext.destroy();
        }
    }

    @Test
    void interceptScopesUnregisteredSelectsAndLeavesOtherStatementsAlone() throws Throwable {
        AutoWonderContext.get().setCurrentWorkspaceId(TENANT_ID);
        try {
            String workitemSelect = "SELECT * FROM workitem WHERE id = ? AND is_deleted = 0";
            StatementHandler scoped = statementHandler(
                    WorkitemDao.class.getName() + ".findById", workitemSelect, SqlCommandType.SELECT);
            new TenantInterceptor().intercept(invocationOn(scoped));
            assertTrue(scoped.getBoundSql().getSql().contains("tenant_id = 42"),
                    scoped.getBoundSql().getSql());

            String sharedSelect = "SELECT * FROM org WHERE id = ?";
            StatementHandler untouched = statementHandler(
                    "com.aliyun.autowonder.workspace.WorkspaceDao.findById", sharedSelect,
                    SqlCommandType.SELECT);
            new TenantInterceptor().intercept(invocationOn(untouched));
            assertEquals(sharedSelect, untouched.getBoundSql().getSql(),
                    "non-tenant tables come back unchanged, so no setValue happens");

            String insert = "INSERT INTO workitem_comment (id) VALUES (?)";
            StatementHandler write = statementHandler(
                    "com.aliyun.autowonder.workitem.WorkitemCommentDao.insert", insert,
                    SqlCommandType.INSERT);
            new TenantInterceptor().intercept(invocationOn(write));
            assertEquals(insert, write.getBoundSql().getSql(), "writes are never rewritten");
        } finally {
            AutoWonderContext.destroy();
        }
    }

    @Test
    void spaceDashboardReadsKeepCrossWorkspaceReachWhenACurrentWorkspaceIsBound() throws Throwable {
        AutoWonderContext.get().setCurrentWorkspaceId(TENANT_ID);
        try {
            String sql = "SELECT a.tenant_id, COUNT(*) FROM agent a "
                    + "WHERE a.tenant_id IN (1, 2) GROUP BY a.tenant_id";
            for (String statementId : SPACE_DASHBOARD_STATEMENTS) {
                StatementHandler handler = statementHandler(statementId, sql, SqlCommandType.SELECT);
                new TenantInterceptor().intercept(invocationOn(handler));
                assertEquals(sql, handler.getBoundSql().getSql(),
                        statementId + " must aggregate every workspace in the caller's scope");
            }
        } finally {
            AutoWonderContext.destroy();
        }
    }

    @Test
    void everySpaceDashboardStatementIsRegisteredExplicitly() {
        assertTrue(TenantInterceptor.CROSS_TENANT_STATEMENTS.containsAll(SPACE_DASHBOARD_STATEMENTS),
                "the dashboard scope replaces the tenant rewrite, so all of its statements stay registered");
    }

    @Test
    void interceptLeavesSelectsAloneWithoutACurrentWorkspace() throws Throwable {
        String sql = "SELECT * FROM workitem WHERE id = ? AND is_deleted = 0";
        StatementHandler handler = statementHandler(
                WorkitemDao.class.getName() + ".findById", sql, SqlCommandType.SELECT);

        new TenantInterceptor().intercept(invocationOn(handler));

        assertEquals(sql, handler.getBoundSql().getSql());
    }

    /** 真实 plugin 路径：delegate.mappedStatement / delegate.boundSql 由 MetaObject 解析，与生产一致。 */
    private static StatementHandler statementHandler(String statementId, String sql,
                                                     SqlCommandType commandType) {
        Configuration configuration = new Configuration();
        MappedStatement statement = new MappedStatement.Builder(configuration, statementId,
                parameterObject -> new BoundSql(configuration, sql, List.of(), parameterObject),
                commandType).build();
        return new RoutingStatementHandler(null, statement, null, RowBounds.DEFAULT, null,
                statement.getBoundSql(null));
    }

    private static Invocation invocationOn(Object handler) {
        Invocation invocation = mock(Invocation.class);
        when(invocation.getTarget()).thenReturn(handler);
        return invocation;
    }

    @Test
    void interceptUnwrapsAnotherPluginThatWrapsTheHandler() throws Throwable {
        AutoWonderContext.get().setCurrentWorkspaceId(TENANT_ID);
        try {
            String sql = "SELECT * FROM workitem WHERE id = ? AND is_deleted = 0";
            StatementHandler handler = statementHandler(
                    WorkitemDao.class.getName() + ".findById", sql, SqlCommandType.SELECT);
            Object wrapped = Plugin.wrap(handler, new PassThroughInterceptor());

            new TenantInterceptor().intercept(invocationOn(wrapped));

            assertTrue(handler.getBoundSql().getSql().contains("tenant_id = 42"),
                    "另一个插件在外层时仍然必须完成租户改写");
        } finally {
            AutoWonderContext.destroy();
        }
    }

    @Intercepts({
            @Signature(type = StatementHandler.class, method = "prepare",
                    args = {Connection.class, Integer.class})
    })
    private static final class PassThroughInterceptor implements Interceptor {
        @Override
        public Object intercept(Invocation invocation) throws Throwable {
            return invocation.proceed();
        }

        @Override
        public Object plugin(Object target) {
            return Plugin.wrap(target, this);
        }

        @Override
        public void setProperties(Properties properties) {
        }
    }
}
