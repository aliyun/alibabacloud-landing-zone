package com.aliyun.autowonder.tenant;

import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.apache.ibatis.executor.statement.RoutingStatementHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TenantInterceptorTest {

    private static final long TENANT_ID = 42L;

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
        assertEquals(Set.of(WorkitemDao.class.getName() + ".findTenantIdById"),
                TenantInterceptor.CROSS_TENANT_STATEMENTS,
                "every cross-tenant statement must be added deliberately together with its own authorization checks");
        for (String statement : TenantInterceptor.CROSS_TENANT_STATEMENTS) {
            assertFalse(statement.endsWith(".*") || statement.contains("*"),
                    "wildcards would silently exempt whole mappers: " + statement);
        }
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

    private static Invocation invocationOn(StatementHandler handler) {
        Invocation invocation = mock(Invocation.class);
        when(invocation.getTarget()).thenReturn(handler);
        return invocation;
    }
}
