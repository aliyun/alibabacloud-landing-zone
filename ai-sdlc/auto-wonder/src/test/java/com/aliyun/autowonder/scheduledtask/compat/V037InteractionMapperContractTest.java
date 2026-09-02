package com.aliyun.autowonder.scheduledtask.compat;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V037InteractionMapperContractTest {

    private static final String LEGACY = "autowonder-legacy";
    private static final String SOURCE_AWARE = "autowonder-source-aware";

    @Test
    void artifactUsesSchemaSpecificInsertAndWorkitemPaths() throws Exception {
        Configuration legacy = mybatis("ArtifactDao.xml", LEGACY);
        Configuration sourceAware = mybatis("ArtifactDao.xml", SOURCE_AWARE);
        String namespace = "com.aliyun.autowonder.artifact.ArtifactDao.";

        assertLegacyProjection(sql(legacy, namespace + "findById"));
        assertSourceAwareProjection(sql(sourceAware, namespace + "findById"));

        String legacyInsert = sql(legacy, namespace + "insert");
        assertFalse(legacyInsert.contains("source_type"));
        assertFalse(legacyInsert.contains("#{sourceType}"));
        assertFalse(legacyInsert.contains("source_type = VALUES(source_type)"));
        String sourceInsert = sql(sourceAware, namespace + "insert");
        assertTrue(sourceInsert.contains("source_type"));

        for (String id : List.of("findWorkitemByTenantAndId", "listByWorkitem",
                "listByWorkitemAndType", "deleteById")) {
            assertFalse(sql(legacy, namespace + id).contains("source_type ="), id);
            assertTrue(sql(sourceAware, namespace + id).contains("source_type = 'WORKITEM'"), id);
        }
        for (String id : List.of("findBySourceAndId", "listBySource", "deleteBySourceAndId")) {
            assertFalse(legacy.hasStatement(namespace + id), id + " must be source-aware only");
            assertTrue(sql(sourceAware, namespace + id).contains("source_type = ?"), id);
        }
        for (String id : List.of("findById", "listByDispatch", "listUsageArtifacts")) {
            assertNoWildcard(sql(legacy, namespace + id));
            assertNoWildcard(sql(sourceAware, namespace + id));
        }
    }

    @Test
    void commentsAndMentionsUseLegacyCompatibleWorkitemPaths() throws Exception {
        assertInteractionMapper("WorkitemCommentDao.xml",
                "com.aliyun.autowonder.workitem.WorkitemCommentDao.",
                List.of("findById", "listByWorkitem"),
                List.of("findBySourceAndId", "listBySource"));
        assertInteractionMapper("WorkitemCommentMentionDao.xml",
                "com.aliyun.autowonder.workitem.WorkitemCommentMentionDao.",
                List.of("listByWorkitem"), List.of("listBySource"));

        Configuration legacyComments = mybatis("WorkitemCommentDao.xml", LEGACY);
        Configuration sourceComments = mybatis("WorkitemCommentDao.xml", SOURCE_AWARE);
        String namespace = "com.aliyun.autowonder.workitem.WorkitemCommentDao.";
        assertTrue(sql(legacyComments, namespace + "insert").contains("COALESCE(?, CURRENT_TIMESTAMP(3))"));
        assertTrue(sql(sourceComments, namespace + "insert").contains("COALESCE(?, CURRENT_TIMESTAMP(3))"));
        assertFalse(sql(legacyComments, namespace + "updateExternalContent").contains("source_type"));
        assertTrue(sql(sourceComments, namespace + "updateExternalContent")
                .contains("source_type = 'WORKITEM'"));
    }

    @Test
    void guidanceUsesCompatibleSelectionsAndKeepsSharedDeliveryOperations() throws Exception {
        Configuration legacy = mybatis("GuidanceDao.xml", LEGACY);
        Configuration sourceAware = mybatis("GuidanceDao.xml", SOURCE_AWARE);
        String namespace = "com.aliyun.autowonder.guidance.GuidanceDao.";

        assertLegacyProjection(sql(legacy, namespace + "findById"));
        assertSourceAwareProjection(sql(sourceAware, namespace + "findById"));
        assertFalse(sql(legacy, namespace + "insert").contains("source_type"));
        assertTrue(sql(sourceAware, namespace + "insert").contains("source_type"));
        assertFalse(sql(legacy, namespace + "listByWorkitem").contains("source_type ="));
        assertTrue(sql(sourceAware, namespace + "listByWorkitem").contains("source_type = 'WORKITEM'"));

        for (String id : List.of("findById", "listQueuedForDispatch", "listDeliveredForExecutor")) {
            assertNoWildcard(sql(legacy, namespace + id));
            assertNoWildcard(sql(sourceAware, namespace + id));
        }
        for (String id : List.of("bindDispatch", "bindPendingDispatch", "updateStatus", "acknowledge",
                "requeueDeliveredForDispatch", "requeueForExecutorFailover", "failForDispatch",
                "bindReplyComment")) {
            assertTrue(legacy.hasStatement(namespace + id), id);
            assertTrue(sourceAware.hasStatement(namespace + id), id);
        }
    }

    private void assertInteractionMapper(String resource, String namespace,
                                         List<String> workitemIds, List<String> sourceIds) throws Exception {
        Configuration legacy = mybatis(resource, LEGACY);
        Configuration sourceAware = mybatis(resource, SOURCE_AWARE);

        assertFalse(sql(legacy, namespace + "insert").contains("source_type"));
        assertTrue(sql(sourceAware, namespace + "insert").contains("source_type"));
        for (String id : workitemIds) {
            String legacySql = sql(legacy, namespace + id);
            String sourceSql = sql(sourceAware, namespace + id);
            assertFalse(legacySql.contains("source_type ="), id);
            assertTrue(sourceSql.contains("source_type = 'WORKITEM'"), id);
            assertNoWildcard(legacySql);
            assertNoWildcard(sourceSql);
            assertLegacyProjection(legacySql);
            assertSourceAwareProjection(sourceSql);
        }
        for (String id : sourceIds) {
            assertFalse(legacy.hasStatement(namespace + id), id + " must be source-aware only");
            String sourceSql = sql(sourceAware, namespace + id);
            assertTrue(sourceSql.contains("source_type = ?"), id);
            assertNoWildcard(sourceSql);
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

    private String sql(Configuration configuration, String id) {
        MappedStatement statement = configuration.getMappedStatement(id);
        return statement.getBoundSql(arguments()).getSql().replaceAll("\\s+", " ").trim();
    }

    private Map<String, Object> arguments() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("id", 1L);
        arguments.put("workspaceId", 2L);
        arguments.put("sourceType", "SCHEDULED_TASK_RUN");
        arguments.put("sourceId", 3L);
        arguments.put("workitemId", 3L);
        arguments.put("dispatchId", 4L);
        arguments.put("executorId", 5L);
        arguments.put("type", null);
        arguments.put("usageName", "usage.json");
        arguments.put("limit", 10);
        arguments.put("offset", 0);
        return arguments;
    }

    private static void assertLegacyProjection(String sql) {
        assertTrue(sql.contains("'WORKITEM' AS source_type"), sql);
    }

    private static void assertSourceAwareProjection(String sql) {
        assertTrue(sql.matches("(?s).*\\bsource_type\\b.*"), sql);
        assertFalse(sql.contains("'WORKITEM' AS source_type"), sql);
    }

    private static void assertNoWildcard(String sql) {
        assertFalse(sql.matches("(?is).*select\\s+(?:[a-z]+\\.)?\\*.*"), sql);
    }
}
