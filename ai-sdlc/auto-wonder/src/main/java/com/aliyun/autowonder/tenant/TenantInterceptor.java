package com.aliyun.autowonder.tenant;

import com.aliyun.autowonder.context.AutoWonderContext;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.reflection.MetaObject;
import org.apache.ibatis.reflection.SystemMetaObject;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.Set;

@Component
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare",
                args = {Connection.class, Integer.class})
})
public class TenantInterceptor implements Interceptor {

    /**
     * 显式登记的跨租户语句：语句自身携带权限校验、或只回传归属标识（如工单归属解析），
     * 需要跨项目读取，因此豁免租户改写；未登记的语句一律保持隔离，禁止按前缀或表名放行。
     */
    static final Set<String> CROSS_TENANT_STATEMENTS = Set.of(
            "com.aliyun.autowonder.workitem.WorkitemDao.findTenantIdById",
            // 通知中心按“本人 +（当前空间的通知 或 本人可见的平台级通知）”读取：平台级通知以
            // tenant_id = 0 持久化，若再追加当前空间租户条件，管理员绑定/切换空间后就看不到注册审核、
            // 权限申请与开通结果提醒。这四条语句自身带 recipient_id 与 scope 归属条件，逐条登记豁免。
            "com.aliyun.autowonder.notification.NotificationDao.listByRecipient",
            "com.aliyun.autowonder.notification.NotificationDao.findByIdAndRecipient",
            "com.aliyun.autowonder.notification.NotificationDao.countUnread",
            "com.aliyun.autowonder.notification.NotificationDao.countByRecipient",
            // 空间级看板按调用者可管理的空间集合聚合：scope 由 SpaceDashboardService.resolveScope
            // 判定并作为 workspaceIds 下传，结果必须跨当前 token 绑定的租户，否则会被改写成仅当前
            // 空间。逐条精确登记，禁止按前缀或表名放行。
            "com.aliyun.autowonder.spacedashboard.SpaceDashboardDao.listScopedWorkspaces",
            "com.aliyun.autowonder.spacedashboard.SpaceDashboardDao.countAgents",
            "com.aliyun.autowonder.spacedashboard.SpaceDashboardDao.countSquads",
            "com.aliyun.autowonder.spacedashboard.SpaceDashboardDao.countDistinctHumans",
            "com.aliyun.autowonder.spacedashboard.SpaceDashboardDao.countCreatedWorkitems",
            "com.aliyun.autowonder.spacedashboard.SpaceDashboardDao.countCompletedWorkitems",
            "com.aliyun.autowonder.spacedashboard.SpaceDashboardDao.countInProgressWorkitems",
            "com.aliyun.autowonder.spacedashboard.SpaceDashboardDao.sumCredits");

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler handler = (StatementHandler) unwrapPlugin(invocation.getTarget());
        MetaObject meta = SystemMetaObject.forObject(handler);
        MappedStatement ms = (MappedStatement) meta.getValue("delegate.mappedStatement");
        BoundSql boundSql = (BoundSql) meta.getValue("delegate.boundSql");

        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId != null && ms.getSqlCommandType() == SqlCommandType.SELECT) {
            String original = boundSql.getSql();
            String rewritten = applyTenantScope(ms.getId(), original, workspaceId);
            if (!rewritten.equals(original)) {
                meta.setValue("delegate.boundSql.sql", rewritten);
            }
        }
        return invocation.proceed();
    }

    /**
     * 其他 MyBatis 插件可能包在本插件外层，此时 target 是 Plugin 的 JDK 代理而不是真实
     * StatementHandler；逐层解包后再读取 delegate，否则所有语句都会因缺少 getter 失败。
     */
    static Object unwrapPlugin(Object target) {
        Object current = target;
        for (int depth = 0; depth < 5 && current != null; depth++) {
            if (!Proxy.isProxyClass(current.getClass())) {
                return current;
            }
            InvocationHandler proxyHandler = Proxy.getInvocationHandler(current);
            if (!(proxyHandler instanceof Plugin)) {
                return current;
            }
            try {
                Field pluginTarget = Plugin.class.getDeclaredField("target");
                pluginTarget.setAccessible(true);
                current = pluginTarget.get(proxyHandler);
            } catch (ReflectiveOperationException unavailable) {
                return current;
            }
        }
        return current;
    }

    static String applyTenantScope(String statementId, String sql, long tenantId) {
        if (CROSS_TENANT_STATEMENTS.contains(statementId)) {
            return sql;
        }
        return TenantSqlRewriter.rewriteSelect(sql, tenantId, TenantTables.TABLES);
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }
}
