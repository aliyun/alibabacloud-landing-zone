package com.aliyun.autowonder.tenant;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;

import java.util.Set;

public final class TenantSqlRewriter {

    private TenantSqlRewriter() {}

    public static String rewriteSelect(String sql, long tenantId, Set<String> tenantTables) {
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (!(stmt instanceof Select)) {
                return sql;
            }
            Select select = (Select) stmt;
            if (!(select.getSelectBody() instanceof PlainSelect)) {
                return sql;
            }
            PlainSelect ps = (PlainSelect) select.getSelectBody();
            if (!(ps.getFromItem() instanceof Table)) {
                return sql;
            }
            Table table = (Table) ps.getFromItem();
            String name = table.getName().replace("`", "").toLowerCase();
            if (!tenantTables.contains(name)) {
                return sql;
            }
            EqualsTo eq = new EqualsTo();
            eq.setLeftExpression(new Column(table, "tenant_id"));
            eq.setRightExpression(new LongValue(tenantId));
            Expression where = ps.getWhere();
            ps.setWhere(where == null ? eq : new AndExpression(where, eq));
            return select.toString();
        } catch (Exception e) {
            return sql;
        }
    }
}
