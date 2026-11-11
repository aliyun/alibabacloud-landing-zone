package com.aliyun.autowonder.workitem.duplicate;

import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.Properties;

/**
 * 同意图候选读取必须是真流式：Connector/J 只有 fetchSize=Integer.MIN_VALUE 才逐行拉取，
 * 否则驱动会先缓冲全部行。H2 等测试库拒绝负 fetchSize，因此只在 MySQL 连接上、
 * 只对 {@code WorkitemDao.streamIntentCandidates} 这一条语句设置。
 */
@Component
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare",
                args = {Connection.class, Integer.class})
})
public class IntentCandidateStreamingInterceptor implements Interceptor {

    private static final Logger LOG = LoggerFactory.getLogger(IntentCandidateStreamingInterceptor.class);

    static final String STATEMENT_ID =
            "com.aliyun.autowonder.workitem.WorkitemDao.streamIntentCandidates";

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        Object prepared = invocation.proceed();
        if (!(prepared instanceof Statement)) {
            return prepared;
        }
        try {
            configure((Statement) prepared, (Connection) invocation.getArgs()[0],
                    mappedStatementId(invocation.getTarget()));
        } catch (SQLException | RuntimeException ignored) {
            // 设置失败不阻断查询：候选输入仍受应用字节上限与查询窗口保护。
            LOG.warn("duplicate intent streaming fetch size was not applied: {}", ignored.getMessage());
        }
        return prepared;
    }

    void configure(Statement statement, Connection connection, String statementId) throws SQLException {
        if (!STATEMENT_ID.equals(statementId) || connection == null) {
            return;
        }
        String product = connection.getMetaData().getDatabaseProductName();
        if (product != null && product.toLowerCase(Locale.ROOT).contains("mysql")) {
            statement.setFetchSize(Integer.MIN_VALUE);
        }
    }

    private static String mappedStatementId(Object target) {
        Object current = target;
        for (int depth = 0; depth < 5 && current != null; depth++) {
            // 其他插件的 JDK 代理同样实现了 StatementHandler，必须先解包再读内部字段。
            if (Proxy.isProxyClass(current.getClass())) {
                current = pluginTarget(current);
                continue;
            }
            if (!(current instanceof StatementHandler)) {
                return null;
            }
            try {
                Object mapped = SystemMetaObject.forObject(current).getValue("delegate.mappedStatement");
                return mapped instanceof MappedStatement ? ((MappedStatement) mapped).getId() : null;
            } catch (RuntimeException unavailable) {
                return null;
            }
        }
        return null;
    }

    /** 其他 MyBatis 插件在外层时，target 是 Plugin 的 JDK 代理；逐层解包找到真实 handler。 */
    private static Object pluginTarget(Object candidate) {
        if (!Proxy.isProxyClass(candidate.getClass())) {
            return null;
        }
        InvocationHandler handler = Proxy.getInvocationHandler(candidate);
        if (!(handler instanceof Plugin)) {
            return null;
        }
        try {
            Field target = Plugin.class.getDeclaredField("target");
            target.setAccessible(true);
            return target.get(handler);
        } catch (ReflectiveOperationException unavailable) {
            return null;
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
    }
}
