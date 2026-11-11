package com.aliyun.autowonder.workitem.duplicate;

import org.apache.ibatis.executor.statement.RoutingStatementHandler;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.apache.ibatis.scripting.defaults.RawSqlSource;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class IntentCandidateStreamingInterceptorTest {

    private final IntentCandidateStreamingInterceptor interceptor = new IntentCandidateStreamingInterceptor();

    @Test
    void appliesStreamingFetchSizeOnlyToTheIntentQueryOnMysql() throws Exception {
        Statement statement = mock(Statement.class);

        interceptor.configure(statement, connection("MySQL"), IntentCandidateStreamingInterceptor.STATEMENT_ID);

        verify(statement).setFetchSize(Integer.MIN_VALUE);
    }

    @Test
    void leavesOtherStatementsDatabasesAndMissingConnectionsUntouched() throws Exception {
        Statement statement = mock(Statement.class);

        interceptor.configure(statement, connection("MySQL"), "com.aliyun.autowonder.workitem.WorkitemDao.listByIds");
        interceptor.configure(statement, connection("H2"), IntentCandidateStreamingInterceptor.STATEMENT_ID);
        interceptor.configure(statement, connection(null), IntentCandidateStreamingInterceptor.STATEMENT_ID);
        interceptor.configure(statement, null, IntentCandidateStreamingInterceptor.STATEMENT_ID);

        verifyNoInteractions(statement);
    }

    @Test
    void interceptSkipsResultsThatAreNotStatements() throws Throwable {
        Invocation invocation = mock(Invocation.class);
        when(invocation.proceed()).thenReturn("not-a-statement");

        assertSame("not-a-statement", interceptor.intercept(invocation));
    }

    @Test
    void interceptAppliesStreamingThroughTheMappedStatementId() throws Throwable {
        StatementHandler handler = handler(IntentCandidateStreamingInterceptor.STATEMENT_ID);
        Statement statement = mock(Statement.class);
        Invocation invocation = invocation(handler, statement, connection("MySQL"));

        assertSame(statement, interceptor.intercept(invocation));

        verify(statement).setFetchSize(Integer.MIN_VALUE);
    }

    @Test
    void interceptIgnoresOtherMappedStatements() throws Throwable {
        StatementHandler handler = handler("com.aliyun.autowonder.workitem.WorkitemDao.listByIds");
        Statement statement = mock(Statement.class);
        Invocation invocation = invocation(handler, statement, connection("MySQL"));

        assertSame(statement, interceptor.intercept(invocation));

        verifyNoInteractions(statement);
    }

    @Test
    void interceptUnwrapsOtherPluginsAroundTheHandler() throws Throwable {
        StatementHandler handler = handler(IntentCandidateStreamingInterceptor.STATEMENT_ID);
        Object wrapped = Plugin.wrap(handler, new NoopInterceptor());
        Statement statement = mock(Statement.class);
        Connection connection = connection("MySQL");
        Invocation invocation = mock(Invocation.class);
        when(invocation.proceed()).thenReturn(statement);
        when(invocation.getTarget()).thenReturn(wrapped);
        when(invocation.getArgs()).thenReturn(new Object[]{connection, 0});

        assertSame(statement, interceptor.intercept(invocation));

        verify(statement).setFetchSize(Integer.MIN_VALUE);
    }

    @Test
    void unknownTargetsYieldNoMappedStatement() throws Throwable {
        Statement statement = mock(Statement.class);
        Connection connection = connection("MySQL");
        Invocation invocation = mock(Invocation.class);
        when(invocation.proceed()).thenReturn(statement);
        when(invocation.getTarget()).thenReturn("plain-object");
        when(invocation.getArgs()).thenReturn(new Object[]{connection, 0});

        assertSame(statement, interceptor.intercept(invocation));

        verifyNoInteractions(statement);
    }

    @Test
    void interceptNeverBreaksTheStatementWhenFetchSizeCannotBeApplied() throws Throwable {
        StatementHandler handler = handler(IntentCandidateStreamingInterceptor.STATEMENT_ID);
        Statement statement = mock(Statement.class);
        Connection connection = mock(Connection.class);
        when(connection.getMetaData()).thenThrow(new SQLException("metadata unavailable"));
        Invocation invocation = mock(Invocation.class);
        when(invocation.proceed()).thenReturn(statement);
        when(invocation.getTarget()).thenReturn(handler);
        when(invocation.getArgs()).thenReturn(new Object[]{connection, 0});

        assertSame(statement, interceptor.intercept(invocation));

        verifyNoInteractions(statement);
    }

    @Test
    void proxiesWithForeignHandlersAreIgnored() throws Throwable {
        StatementHandler foreign = (StatementHandler) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{StatementHandler.class},
                (proxy, method, args) -> null);
        Statement statement = mock(Statement.class);
        Connection connection = connection("MySQL");
        Invocation invocation = mock(Invocation.class);
        when(invocation.proceed()).thenReturn(statement);
        when(invocation.getTarget()).thenReturn(foreign);
        when(invocation.getArgs()).thenReturn(new Object[]{connection, 0});

        assertSame(statement, interceptor.intercept(invocation));

        verifyNoInteractions(statement);
    }

    @Test
    void pluginWrapsTheStatementHandlerForMyBatis() {
        StatementHandler handler = handler(IntentCandidateStreamingInterceptor.STATEMENT_ID);

        Object wrapped = interceptor.plugin(handler);

        assertSame(wrapped.getClass(), Plugin.wrap(handler, interceptor).getClass());
        interceptor.setProperties(new Properties());
    }

    private static Invocation invocation(StatementHandler handler, Statement statement, Connection connection) {
        Invocation invocation = mock(Invocation.class);
        try {
            when(invocation.proceed()).thenReturn(statement);
        } catch (Throwable impossible) {
            throw new AssertionError(impossible);
        }
        when(invocation.getTarget()).thenReturn(handler);
        when(invocation.getArgs()).thenReturn(new Object[]{connection, 0});
        return invocation;
    }

    private static StatementHandler handler(String statementId) {
        Configuration configuration = new Configuration();
        MappedStatement statement = new MappedStatement.Builder(configuration, statementId,
                new RawSqlSource(configuration, "select 1", Object.class), SqlCommandType.SELECT).build();
        return new RoutingStatementHandler(null, statement, null, RowBounds.DEFAULT, null, null);
    }

    private static Connection connection(String product) throws Exception {
        Connection connection = mock(Connection.class);
        DatabaseMetaData metaData = mock(DatabaseMetaData.class);
        when(connection.getMetaData()).thenReturn(metaData);
        when(metaData.getDatabaseProductName()).thenReturn(product);
        return connection;
    }

    @Intercepts({
            @Signature(type = StatementHandler.class, method = "prepare",
                    args = {Connection.class, Integer.class})
    })
    private static final class NoopInterceptor implements Interceptor {
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
