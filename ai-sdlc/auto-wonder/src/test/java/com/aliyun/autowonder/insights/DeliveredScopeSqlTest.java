package com.aliyun.autowonder.insights;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 约束完成时间子查询的作用域：外层 workitem 别名只能出现在子查询 WHERE 中。
 * MySQL 8 拒绝 JOIN ... ON 引用外层查询列（Unknown column 'w.template_id' in 'on clause'），
 * 而 H2 MySQL 模式会错误放行，因此需要不依赖数据库引擎的结构回归用例。
 */
class DeliveredScopeSqlTest {

    private static final List<String> MAPPERS =
            List.of("WorkitemDao", "DeliveryOverviewDao", "DashboardDao", "RequirementDeliveryDao");

    @Test
    void deliveredSubqueriesKeepOuterTemplateConditionInWhereInsteadOfJoinOn() throws Exception {
        Configuration configuration = new Configuration();
        configuration.setDatabaseId("autowonder-source-aware");
        for (String mapper : MAPPERS) {
            try (var stream = Files.newInputStream(Path.of("src/main/resources/mapping/" + mapper + ".xml"))) {
                new XMLMapperBuilder(stream, configuration, mapper, configuration.getSqlFragments()).parse();
            }
        }

        assertSubqueryScope(configuration,
                DeliveryOverviewDao.class.getName() + ".countDeliveredWorkitems");
        assertSubqueryScope(configuration,
                RequirementDeliveryDao.class.getName() + ".listDeliveredRequirements");
    }

    private static void assertSubqueryScope(Configuration configuration, String statementId) {
        MappedStatement statement = configuration.getMappedStatement(statementId);
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", 9L);
        params.put("start", Timestamp.valueOf("2026-01-05 00:00:00"));
        params.put("end", Timestamp.valueOf("2026-01-09 00:00:00"));
        String sql = statement.getBoundSql(params).getSql().replaceAll("\\s+", " ");

        int join = sql.indexOf("JOIN status_node done_node ON ");
        assertTrue(join >= 0, statementId + " 必须以 done_node 关联状态节点: " + sql);
        int where = sql.indexOf(" WHERE e.tenant_id = w.tenant_id", join);
        assertTrue(where > join, statementId + " 的子查询必须在外层租户条件下过滤: " + sql);

        String joinCondition = sql.substring(join, where);
        assertFalse(joinCondition.contains("w."),
                statementId + " 的 JOIN ... ON 引用了外层别名，MySQL 会报 Unknown column: " + joinCondition);
        assertTrue(joinCondition.contains("done_node.tenant_id = e.tenant_id"), joinCondition);
        assertTrue(joinCondition.contains("done_node.code = e.to_val"), joinCondition);
        assertTrue(joinCondition.contains("done_node.category = 'DONE'"), joinCondition);

        int subqueryEnd = sql.indexOf(") AS delivered_at", where);
        assertTrue(subqueryEnd > where, statementId + " 必须把子查询投影为 delivered_at: " + sql);
        assertTrue(sql.substring(where, subqueryEnd).contains("done_node.template_id = w.template_id"),
                statementId + " 必须把模板关联保留在子查询 WHERE: " + sql);
    }
}
