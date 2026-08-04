package com.aliyun.autowonder.guidance;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GuidanceDaoContractTest {

    @Test
    void mapperStoresOnlyCommentDeliveryState() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/GuidanceDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(xml.contains("workitem_comment_delivery"));
        assertFalse(xml.contains("workitem_guidance"));
        assertFalse(xml.contains("content_md"));
        assertFalse(xml.contains("creator_id"));
    }

    @Test
    void canonicalSchemaCreatesTheFinalDeliveryTableDirectly() throws Exception {
        String sql = tableDefinition(
                Files.readString(Path.of("docs/autowonder-schema.sql")),
                "workitem_comment_delivery");

        assertFalse(sql.contains("CREATE TABLE IF NOT EXISTS `workitem_guidance`"));
        assertFalse(sql.contains("content_md"));
        assertFalse(sql.contains("creator_id"));
    }

    @Test
    void canonicalSchemaAndMapperPersistTheReplyCommentRelation() throws Exception {
        String sql = tableDefinition(
                Files.readString(Path.of("docs/autowonder-schema.sql")),
                "workitem_comment_delivery");
        String xml = new String(
                getClass().getResourceAsStream("/mapping/GuidanceDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(sql.contains("`reply_comment_id` BIGINT UNSIGNED DEFAULT NULL"));
        assertTrue(sql.contains("KEY `idx_comment_delivery_reply` (`tenant_id`, `reply_comment_id`)"));
        assertTrue(xml.contains("reply_comment_id"));
        assertTrue(xml.contains("bindReplyComment"));
    }

    @Test
    void executorFailoverCanRecoverGuidanceAlreadyFailedByAnOlderRuntime() throws Exception {
        String xml = new String(
                getClass().getResourceAsStream("/mapping/GuidanceDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);

        assertTrue(xml.contains("status IN ('DELIVERED', 'FAILED')"));
    }

    private static String tableDefinition(String schema, String table) {
        var matcher = Pattern.compile(
                "(?is)CREATE TABLE IF NOT EXISTS `" + table + "`\\s*\\((.*?)\\)\\s*ENGINE=")
                .matcher(schema);
        assertTrue(matcher.find(), "missing canonical table " + table);
        return matcher.group(1);
    }
}
