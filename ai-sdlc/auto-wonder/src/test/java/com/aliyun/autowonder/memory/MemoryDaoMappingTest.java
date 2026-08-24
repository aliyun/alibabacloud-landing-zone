package com.aliyun.autowonder.memory;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.ParameterMapping;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Asserts the SQL that mapping/MemoryDao.xml actually generates. Follows the existing
 * *DaoMappingTest convention, but renders each statement through MyBatis rather than matching raw
 * XML text, so clause ordering relative to LIMIT and parameter binding become verifiable.
 * Mocking MemoryService hides real SQL semantics, which is how the pagination-before-filtering
 * defect reached review.
 */
class MemoryDaoMappingTest {

    private static final String LIST = "com.aliyun.autowonder.memory.MemoryDao.list";

    private Configuration configuration;

    @BeforeEach
    void setUp() throws Exception {
        configuration = new Configuration();
        try (InputStream in = getClass().getResourceAsStream("/mapping/MemoryDao.xml")) {
            assertNotNull(in, "mapping/MemoryDao.xml must be on the classpath");
            new XMLMapperBuilder(in, configuration, "mapping/MemoryDao.xml",
                    configuration.getSqlFragments()).parse();
        }
    }

    @Test
    void listAppliesVisibilityPredicateBeforePagination() {
        String sql = sqlFor(LIST, params(args -> args.put("visibleAgentRef", 40014L)));

        int visibility = sql.indexOf("(scope <> 'AGENT' OR owner_ref = ?)");
        int limit = sql.indexOf("LIMIT");

        assertTrue(visibility > 0, () -> "visibility predicate missing: " + sql);
        assertTrue(limit > 0, () -> "LIMIT missing: " + sql);
        assertTrue(visibility < limit,
                () -> "visibility predicate must be applied before LIMIT truncates the page: " + sql);
        assertTrue(sql.indexOf("WHERE") < visibility,
                () -> "visibility predicate must live inside WHERE: " + sql);
        assertTrue(parameterNames(LIST, params(args -> args.put("visibleAgentRef", 40014L)))
                .contains("visibleAgentRef"));
    }

    @Test
    void listOmitsVisibilityPredicateWhenNoAgentScopeRequested() {
        String sql = sqlFor(LIST, params(args -> { }));

        assertFalse(sql.contains("scope <> 'AGENT'"),
                () -> "visibility predicate must stay optional: " + sql);
    }

    @Test
    void listWithoutOptionalFiltersKeepsPreExistingSql() {
        String sql = sqlFor(LIST, params(args -> { }));

        assertEquals("SELECT * FROM memory WHERE tenant_id = ? AND is_deleted = 0 "
                + "ORDER BY id DESC LIMIT ?, ?", sql);
    }

    @Test
    void listCombinesEveryOptionalFilterInsideWhere() {
        Map<String, Object> args = params(a -> {
            a.put("scope", "AGENT");
            a.put("ownerRef", 40014L);
            a.put("type", "PITFALL");
            a.put("status", "ADOPTED");
            a.put("keyword", "MyBatis");
            a.put("visibleAgentRef", 40014L);
        });
        String sql = sqlFor(LIST, args);

        assertEquals("SELECT * FROM memory WHERE tenant_id = ? AND is_deleted = 0 "
                + "AND scope = ? AND owner_ref = ? AND type = ? AND status = ? "
                + "AND (scope <> 'AGENT' OR owner_ref = ?) "
                + "AND (title LIKE CONCAT('%', ?, '%') OR content_md LIKE CONCAT('%', ?, '%')) "
                + "ORDER BY id DESC LIMIT ?, ?", sql);
        assertEquals(List.of("tenantId", "scope", "ownerRef", "type", "status",
                "visibleAgentRef", "keyword", "keyword", "offset", "limit"),
                parameterNames(LIST, args));
    }

    @Test
    void keywordIsBoundAsParameterAndNeverInlined() {
        Map<String, Object> args = params(a -> a.put("keyword", "100%_raw' OR 1=1"));
        String sql = sqlFor(LIST, args);

        assertTrue(sql.contains("LIKE CONCAT('%', ?, '%')"), () -> sql);
        assertFalse(sql.contains("OR 1=1"), () -> "keyword must never be inlined: " + sql);
    }

    @Test
    void blankKeywordDoesNotAddLikeClause() {
        assertFalse(sqlFor(LIST, params(a -> a.put("keyword", ""))).contains("LIKE"));
    }

    @Test
    void findBySourceDedupeKeyIsScopedToTenantSourceAndLiveRows() {
        String sql = sqlFor("com.aliyun.autowonder.memory.MemoryDao.findBySourceDedupeKey",
                new HashMap<>(Map.of("tenantId", 10L, "source", "MCP", "sourceDedupeKey", "dispatch:1:mcp:k")));

        assertEquals("SELECT * FROM memory WHERE tenant_id = ? AND source = ? "
                + "AND source_dedupe_key = ? AND is_deleted = 0 LIMIT 1", sql);
    }

    @Test
    void listGroupSummariesFiltersBeforeGroupingAndPagination() {
        Map<String, Object> args = groupSummaryParams(a -> {
            a.put("scope", "AGENT");
            a.put("type", "RULE");
            a.put("status", "ADOPTED");
        });
        String sql = sqlFor("com.aliyun.autowonder.memory.MemoryDao.listGroupSummaries", args);

        int statusFilter = sql.indexOf("AND status = ?");
        int groupBy = sql.indexOf("GROUP BY scope, owner_ref");
        int limit = sql.indexOf("LIMIT");
        assertTrue(statusFilter > 0 && sql.indexOf("WHERE") < statusFilter,
                () -> "filters must live inside WHERE: " + sql);
        assertTrue(statusFilter < groupBy,
                () -> "filters must apply before GROUP BY aggregates: " + sql);
        assertTrue(groupBy < limit,
                () -> "group-level pagination must come after grouping: " + sql);
        assertTrue(sql.contains("ORDER BY latest_id DESC"), () -> sql);
        assertTrue(sql.contains("COUNT(*) AS total"), () -> sql);
    }

    @Test
    void listGroupSummariesWithoutOptionalFiltersKeepsBaseSql() {
        String sql = sqlFor("com.aliyun.autowonder.memory.MemoryDao.listGroupSummaries",
                groupSummaryParams(a -> { }));

        assertEquals("SELECT scope, owner_ref, COUNT(*) AS total, MAX(id) AS latest_id FROM memory "
                + "WHERE tenant_id = ? AND is_deleted = 0 "
                + "GROUP BY scope, owner_ref ORDER BY latest_id DESC LIMIT ?, ?", sql);
    }

    @Test
    void listByGroupsHandlesNullOwnerRefWithIsNullAndBindsEverythingElse() {
        MemoryGroupSummaryDO agentGroup = new MemoryGroupSummaryDO();
        agentGroup.setScope("AGENT");
        agentGroup.setOwnerRef(30L);
        MemoryGroupSummaryDO orgGroup = new MemoryGroupSummaryDO();
        orgGroup.setScope("ORG");
        orgGroup.setOwnerRef(null);
        Map<String, Object> args = new HashMap<>(Map.of(
                "tenantId", 100L,
                "groups", List.of(agentGroup, orgGroup),
                "limit", 2000));
        String sql = sqlFor("com.aliyun.autowonder.memory.MemoryDao.listByGroups", args);

        assertTrue(sql.contains("(scope = ? AND owner_ref = ? ) OR (scope = ? AND owner_ref IS NULL )"),
                () -> "null owner_ref must match via IS NULL, never owner_ref = ?: " + sql);
        assertTrue(sql.indexOf("tenant_id = ?") < sql.indexOf("scope = ?"),
                () -> "tenant isolation must precede group matching: " + sql);
        assertTrue(sql.indexOf("ORDER BY id DESC") < sql.indexOf("LIMIT"),
                () -> "group members must sort before truncation: " + sql);
        assertFalse(sql.contains("(scope = ? AND owner_ref IS NULL ) OR (scope = ? AND owner_ref = ? )")
                        && sql.indexOf("owner_ref IS NULL") > sql.lastIndexOf("owner_ref = ?"),
                () -> "ORG group must not bind a null owner_ref as equality: " + sql);
    }

    @Test
    void listByGroupsReappliesTypeAndStatusFilters() {
        MemoryGroupSummaryDO agentGroup = new MemoryGroupSummaryDO();
        agentGroup.setScope("AGENT");
        agentGroup.setOwnerRef(30L);
        Map<String, Object> args = new HashMap<>(Map.of(
                "tenantId", 100L,
                "groups", List.of(agentGroup),
                "type", "RULE",
                "status", "ADOPTED",
                "limit", 2000));
        String sql = sqlFor("com.aliyun.autowonder.memory.MemoryDao.listByGroups", args);

        assertTrue(sql.contains("AND type = ?"), () -> sql);
        assertTrue(sql.contains("AND status = ?"), () -> sql);
        List<String> names = parameterNames("com.aliyun.autowonder.memory.MemoryDao.listByGroups", args);
        assertEquals(List.of("tenantId", "type", "status"), names.subList(0, 3),
                () -> "tenant and filters must bind before group predicates: " + names);
        assertEquals("limit", names.get(names.size() - 1),
                () -> "LIMIT must stay the final binding: " + names);
        assertTrue(names.stream().anyMatch(n -> n.contains("scope")),
                () -> "foreach group keys must bind as parameters: " + names);
    }

    private Map<String, Object> groupSummaryParams(java.util.function.Consumer<Map<String, Object>> customizer) {
        Map<String, Object> args = new HashMap<>();
        args.put("tenantId", 100L);
        args.put("offset", 0);
        args.put("limit", 10);
        customizer.accept(args);
        return args;
    }

    private Map<String, Object> params(java.util.function.Consumer<Map<String, Object>> customizer) {
        Map<String, Object> args = new HashMap<>();
        args.put("tenantId", 100L);
        args.put("offset", 0);
        args.put("limit", 20);
        customizer.accept(args);
        return args;
    }

    private String sqlFor(String statementId, Map<String, Object> args) {
        MappedStatement statement = configuration.getMappedStatement(statementId);
        return statement.getBoundSql(args).getSql().replaceAll("\\s+", " ").trim();
    }

    private List<String> parameterNames(String statementId, Map<String, Object> args) {
        MappedStatement statement = configuration.getMappedStatement(statementId);
        BoundSql boundSql = statement.getBoundSql(args);
        return boundSql.getParameterMappings().stream()
                .map(ParameterMapping::getProperty)
                .toList();
    }
}
