package com.aliyun.autowonder.artifact;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactDaoSourceContractTest {

    @Test
    void sourceQueriesAndDeletesAreTenantAndOwnerScoped() throws Exception {
        String xml = normalizedXml();

        assertTrue(xml.contains("id=\"listBySource\""));
        assertTrue(statement(xml, "select", "listBySource").contains(
                "tenant_id = #{tenantId} AND source_type = #{sourceType} AND workitem_id = #{sourceId}"));
        assertTrue(xml.contains("id=\"findBySourceAndId\""));
        assertTrue(xml.contains("id=\"deleteBySourceAndId\""));
        assertTrue(statement(xml, "select", "listByWorkitem").contains(
                "source_type = 'WORKITEM' AND workitem_id = #{workitemId}"));
        assertTrue(statement(xml, "delete", "deleteById").contains("source_type = 'WORKITEM'"));
        String legacyFind = statement(xml, "select", "findWorkitemByTenantAndId");
        assertTrue(legacyFind.contains("tenant_id = #{tenantId}"));
        assertTrue(legacyFind.contains("source_type = 'WORKITEM'"));
        assertTrue(legacyFind.contains("id = #{id}"));
    }

    @Test
    void insertExplicitlyPersistsNonNullSourceType() throws Exception {
        String xml = normalizedXml();

        assertTrue(xml.contains("INSERT INTO artifact (tenant_id, source_type, workitem_id"));
        assertTrue(xml.contains("VALUES (#{tenantId}, #{sourceType}, #{workitemId}"));
        assertTrue(new ArtifactDO().getSourceType().equals("WORKITEM"));
    }

    private String normalizedXml() throws Exception {
        return Files.readString(Path.of("src/main/resources/mapping/ArtifactDao.xml"))
                .replaceAll("\\s+", " ");
    }

    private String statement(String xml, String tag, String id) {
        int start = xml.indexOf("<" + tag + " id=\"" + id + "\"");
        assertTrue(start >= 0, "Missing mapper statement " + id);
        int end = xml.indexOf("</" + tag + ">", start);
        assertTrue(end > start, "Unclosed mapper statement " + id);
        return xml.substring(start, end);
    }
}
