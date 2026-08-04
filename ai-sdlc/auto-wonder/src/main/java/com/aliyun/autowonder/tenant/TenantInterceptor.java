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

import java.sql.Connection;

@Component
@Intercepts({
        @Signature(type = StatementHandler.class, method = "prepare",
                args = {Connection.class, Integer.class})
})
public class TenantInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        StatementHandler handler = (StatementHandler) invocation.getTarget();
        MetaObject meta = SystemMetaObject.forObject(handler);
        MappedStatement ms = (MappedStatement) meta.getValue("delegate.mappedStatement");
        BoundSql boundSql = (BoundSql) meta.getValue("delegate.boundSql");

        Long orgId = AutoWonderContext.get().getCurrentOrgId();
        if (orgId != null && ms.getSqlCommandType() == SqlCommandType.SELECT) {
            String original = boundSql.getSql();
            String rewritten = TenantSqlRewriter.rewriteSelect(original, orgId, TenantTables.TABLES);
            if (!rewritten.equals(original)) {
                meta.setValue("delegate.boundSql.sql", rewritten);
            }
        }
        return invocation.proceed();
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }
}
