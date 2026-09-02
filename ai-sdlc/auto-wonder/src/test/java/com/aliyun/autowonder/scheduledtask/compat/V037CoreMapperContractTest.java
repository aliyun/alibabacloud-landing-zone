package com.aliyun.autowonder.scheduledtask.compat;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V037CoreMapperContractTest {

    private static final String LEGACY = "autowonder-legacy";
    private static final String SOURCE_AWARE = "autowonder-source-aware";

    @Test
    void dispatchRegistersSchemaSpecificColumnsInsertsAndWorkitemQueries() throws Exception {
        Document mapper = mapper("DispatchDao.xml");

        String legacyCols = sql(mapper, "sql", "cols", LEGACY);
        assertTrue(legacyCols.contains("'WORKITEM' AS source_type"));
        assertEquals(1, occurrences(legacyCols, "source_type"));
        assertTrue(sql(mapper, "sql", "cols", SOURCE_AWARE).contains("source_type"));

        for (String id : List.of("insert", "findMaxAttempt", "listByWorkitem",
                "listLatestByWorkitemIds", "listByWorkitemIds",
                "listLatestByWorkitemAndAgent", "listSucceededByWorkitem", "tenantFilter")) {
            Element legacy = element(mapper, id, LEGACY);
            Element sourceAware = element(mapper, id, SOURCE_AWARE);
            assertNotNull(legacy, "missing Legacy variant: " + id);
            assertNotNull(sourceAware, "missing source-aware variant: " + id);
            assertFalse(text(legacy).contains("source_type"),
                    "Legacy SQL must not reference the absent dispatch.source_type: " + id);
        }

        assertFalse(sql(mapper, "insert", "insert", LEGACY).contains("#{sourceType}"));
        assertTrue(sql(mapper, "insert", "insert", SOURCE_AWARE).contains("#{sourceType}"));

        String latest = sql(mapper, "select", "listLatestByWorkitemIds", SOURCE_AWARE);
        assertTrue(occurrences(latest, "tenant_id = #{tenantId}") >= 2);
        assertTrue(occurrences(latest, "source_type = 'WORKITEM'") >= 2);
    }

    @Test
    void dispatchKeepsScheduledSourceStatementsSourceAwareOnly() throws Exception {
        Document mapper = mapper("DispatchDao.xml");

        for (String id : List.of("findMaxAttemptBySource", "listBySource",
                "listLatestBySourceAndAgent", "listSucceededBySource", "pinScheduledAgentVersion")) {
            assertNotNull(element(mapper, id, SOURCE_AWARE), id);
            assertEquals(1, elements(mapper, id).size(), id + " must have no generic or Legacy registration");
        }
    }

    @Test
    void workitemInsertAndPendingDecisionQueryFollowSelectedSchema() throws Exception {
        Document mapper = mapper("WorkitemDao.xml");

        String legacyInsert = sql(mapper, "insert", "insert", LEGACY);
        assertFalse(legacyInsert.contains("origin_type"));
        assertFalse(legacyInsert.contains("origin_id"));
        String sourceAwareInsert = sql(mapper, "insert", "insert", SOURCE_AWARE);
        assertTrue(sourceAwareInsert.contains("origin_type"));
        assertTrue(sourceAwareInsert.contains("origin_id"));

        assertEquals(1, elements(mapper, "listByOrigin").size());
        assertNotNull(element(mapper, "listByOrigin", SOURCE_AWARE));

        String legacyPending = sql(mapper, "sql", "pendingDecisionDispatchFilter", LEGACY);
        assertFalse(legacyPending.contains("source_type"));
        assertTrue(legacyPending.contains("d2.tenant_id = w.tenant_id"));
        String sourceAwarePending = sql(mapper, "sql", "pendingDecisionDispatchFilter", SOURCE_AWARE);
        assertTrue(sourceAwarePending.contains("d2.source_type = 'WORKITEM'"));
        assertTrue(sourceAwarePending.contains("d.source_type = 'WORKITEM'"));
    }

    @Test
    void scheduledMappersRegisterEveryStatementOnlyWhenSourceAware() throws Exception {
        assertOnlySourceAware("ScheduledTaskDao.xml", Set.of(
                "cols", "insert", "findById", "findByIdForUpdate", "findAnyById", "listByWorkspace",
                "countByWorkspace", "summarizeRuns", "findDue", "claimNextFire", "update", "updateStatus"));
        assertOnlySourceAware("ScheduledTaskRunDao.xml", Set.of(
                "cols", "insert", "findByTriggerKey", "findById", "listByTask",
                "findActiveByTask", "findActiveByTaskForUpdate", "countActive",
                "countCompletedByTaskSince", "countSucceededByTaskSince", "findNextQueued",
                "listStaleStarting", "listStaleQueued", "updateStatus", "updateCurrentAssignment",
                "initializeExecution", "updateTerminalResult", "markDegraded", "markResumeSource",
                "markCancelPending", "markCancelIntent"));
    }

    @Test
    void mybatisResolvesFragmentsAndStatementsForEachDatabaseId() throws Exception {
        Configuration legacyDispatch = mybatis("DispatchDao.xml", LEGACY);
        assertTrue(legacyDispatch.hasStatement("com.aliyun.autowonder.dispatch.DispatchDao.insert"));
        assertFalse(legacyDispatch.hasStatement(
                "com.aliyun.autowonder.dispatch.DispatchDao.findMaxAttemptBySource"));
        String legacyFind = legacyDispatch
                .getMappedStatement("com.aliyun.autowonder.dispatch.DispatchDao.findById")
                .getBoundSql(Map.of("id", 1L)).getSql().replaceAll("\\s+", " ");
        assertTrue(legacyFind.contains("'WORKITEM' AS source_type"));

        Configuration sourceDispatch = mybatis("DispatchDao.xml", SOURCE_AWARE);
        assertTrue(sourceDispatch.hasStatement(
                "com.aliyun.autowonder.dispatch.DispatchDao.findMaxAttemptBySource"));
        String sourceFind = sourceDispatch
                .getMappedStatement("com.aliyun.autowonder.dispatch.DispatchDao.findById")
                .getBoundSql(Map.of("id", 1L)).getSql().replaceAll("\\s+", " ");
        assertTrue(sourceFind.contains("tenant_id, source_type, workitem_id"));

        Configuration legacyWorkitem = mybatis("WorkitemDao.xml", LEGACY);
        assertTrue(legacyWorkitem.hasStatement("com.aliyun.autowonder.workitem.WorkitemDao.insert"));
        assertFalse(legacyWorkitem.hasStatement("com.aliyun.autowonder.workitem.WorkitemDao.listByOrigin"));
        Configuration sourceWorkitem = mybatis("WorkitemDao.xml", SOURCE_AWARE);
        assertTrue(sourceWorkitem.hasStatement("com.aliyun.autowonder.workitem.WorkitemDao.listByOrigin"));

        Map<String, Object> statusFilter = new HashMap<>();
        statusFilter.put("workspaceId", 1L);
        statusFilter.put("statusCategory", "PENDING_DECISION");
        statusFilter.put("pendingDecisionOnly", false);
        statusFilter.put("currentUserId", 2L);
        statusFilter.put("offset", 0);
        statusFilter.put("limit", 20);
        String legacyStatusSql = legacyWorkitem.getMappedStatement(
                "com.aliyun.autowonder.workitem.WorkitemDao.list")
                .getBoundSql(statusFilter).getSql().replaceAll("\\s+", " ");
        String sourceStatusSql = sourceWorkitem.getMappedStatement(
                "com.aliyun.autowonder.workitem.WorkitemDao.list")
                .getBoundSql(statusFilter).getSql().replaceAll("\\s+", " ");
        assertFalse(legacyStatusSql.contains("source_type"));
        assertTrue(occurrences(sourceStatusSql, "source_type = 'WORKITEM'") >= 2);
        assertTrue(sourceStatusSql.contains("d2.tenant_id = w.tenant_id"));

        assertFalse(mybatis("ScheduledTaskDao.xml", LEGACY)
                .hasStatement("com.aliyun.autowonder.scheduledtask.ScheduledTaskDao.findById"));
        assertTrue(mybatis("ScheduledTaskDao.xml", SOURCE_AWARE)
                .hasStatement("com.aliyun.autowonder.scheduledtask.ScheduledTaskDao.findById"));
        assertFalse(mybatis("ScheduledTaskRunDao.xml", LEGACY)
                .hasStatement("com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDao.findById"));
        assertTrue(mybatis("ScheduledTaskRunDao.xml", SOURCE_AWARE)
                .hasStatement("com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDao.findById"));
    }

    private void assertOnlySourceAware(String resource, Set<String> expectedIds) throws Exception {
        Document mapper = mapper(resource);
        Set<String> actualIds = statementElements(mapper).stream()
                .map(element -> element.getAttribute("id"))
                .collect(java.util.stream.Collectors.toSet());
        assertEquals(expectedIds, actualIds, "contract must enumerate every statement in " + resource);
        for (Element statement : statementElements(mapper)) {
            assertEquals(SOURCE_AWARE, statement.getAttribute("databaseId"),
                    resource + "#" + statement.getAttribute("id"));
        }
    }

    private Document mapper(String resource) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        try (InputStream input = getClass().getResourceAsStream("/mapping/" + resource)) {
            assertNotNull(input, "missing mapper " + resource);
            return factory.newDocumentBuilder().parse(input);
        }
    }

    private Configuration mybatis(String resource, String databaseId) throws Exception {
        Configuration configuration = new Configuration();
        configuration.setDatabaseId(databaseId);
        try (InputStream input = getClass().getResourceAsStream("/mapping/" + resource)) {
            assertNotNull(input, "missing mapper " + resource);
            new XMLMapperBuilder(input, configuration, "mapping/" + resource,
                    configuration.getSqlFragments()).parse();
        }
        return configuration;
    }

    private static String sql(Document mapper, String tag, String id, String databaseId) {
        Element element = element(mapper, id, databaseId);
        assertNotNull(element, "missing " + tag + " " + id + " for " + databaseId);
        assertEquals(tag, element.getTagName());
        return text(element);
    }

    private static Element element(Document mapper, String id, String databaseId) {
        return elements(mapper, id).stream()
                .filter(element -> databaseId.equals(element.getAttribute("databaseId")))
                .findFirst().orElse(null);
    }

    private static List<Element> elements(Document mapper, String id) {
        List<Element> matches = new ArrayList<>();
        for (Element element : statementElements(mapper)) {
            if (id.equals(element.getAttribute("id"))) {
                matches.add(element);
            }
        }
        return matches;
    }

    private static List<Element> statementElements(Document mapper) {
        List<Element> statements = new ArrayList<>();
        NodeList children = mapper.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node node = children.item(i);
            if (node instanceof Element element
                    && Set.of("sql", "select", "insert", "update", "delete").contains(element.getTagName())) {
                statements.add(element);
            }
        }
        return statements;
    }

    private static String text(Element element) {
        return element.getTextContent().replaceAll("\\s+", " ").trim();
    }

    private static int occurrences(String text, String fragment) {
        int count = 0;
        for (int at = 0; (at = text.indexOf(fragment, at)) >= 0; at += fragment.length()) {
            count++;
        }
        return count;
    }
}
