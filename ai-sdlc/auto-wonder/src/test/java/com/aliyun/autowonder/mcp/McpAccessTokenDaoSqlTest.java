package com.aliyun.autowonder.mcp;

import org.apache.ibatis.annotations.Param;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpAccessTokenDaoSqlTest {

    @Test
    void tokenModelDropsTheConfiguredAccessLevel() {
        assertThrows(NoSuchFieldException.class,
                () -> McpAccessTokenDO.class.getDeclaredField("accessLevel"));
    }

    @Test
    void ownerScopedLookupIsKeyedByUserOnly() throws Exception {
        var method = McpAccessTokenDao.class.getDeclaredMethod(
                "findById", Long.class, Long.class);

        assertEquals(McpAccessTokenDO.class, method.getReturnType());
        assertEquals(List.of("id", "userId"), paramNames(method));
    }

    @Test
    void mutationsAndListingAreKeyedByUserOnly() throws Exception {
        assertEquals(List.of("userId"), paramNames(
                McpAccessTokenDao.class.getDeclaredMethod("listByUser", Long.class)));
        assertEquals(List.of("id", "userId", "revokedAt", "modifierId"), paramNames(
                McpAccessTokenDao.class.getDeclaredMethod(
                        "revoke", Long.class, Long.class, java.util.Date.class, Long.class)));
    }

    @Test
    void insertPersistsTheOwnerWithoutOrganizationOrAccessLevel() throws Exception {
        String insert = statement(mapperXml(), "insert", "insert");

        assertTrue(insert.contains(
                "(user_id, name, token_hash, token_prefix, creator_id, is_deleted, version)"));
        assertTrue(insert.contains(
                "(#{userId}, #{name}, #{tokenHash}, #{tokenPrefix}, #{creatorId}, 0, 0)"));
        assertFalse(insert.contains("tenant_id"));
        assertFalse(insert.contains("access_level"));
    }

    @Test
    void everyStatementStopsFilteringByTenant() throws Exception {
        String xml = mapperXml();

        assertFalse(xml.contains("tenant_id"));
        assertFalse(xml.contains("access_level"));
    }

    @Test
    void ownerScopedStatementsRequireTokenIdAndUser() throws Exception {
        String xml = mapperXml();

        String findById = statement(xml, "findById", "select");
        assertTrue(findById.contains("id = #{id}"));
        assertTrue(findById.contains("user_id = #{userId}"));
        assertTrue(findById.contains("is_deleted = 0"));

        String listByUser = statement(xml, "listByUser", "select");
        assertTrue(listByUser.contains("user_id = #{userId}"));
        assertTrue(listByUser.contains("is_deleted = 0"));

        String revoke = statement(xml, "revoke", "update");
        assertTrue(revoke.contains("id = #{id}"));
        assertTrue(revoke.contains("user_id = #{userId}"));
        assertTrue(revoke.contains("revoked_at IS NULL"));
    }

    private static List<String> paramNames(java.lang.reflect.Method method) {
        return Arrays.stream(method.getParameters())
                .map(parameter -> parameter.getAnnotation(Param.class))
                .map(Param::value)
                .toList();
    }

    private String mapperXml() throws Exception {
        try (var stream = getClass().getResourceAsStream(
                "/mapping/McpAccessTokenDao.xml")) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ")
                    .trim();
        }
    }

    private static String statement(String xml, String id, String element) {
        String marker = "id=\"" + id + "\"";
        int idStart = xml.indexOf(marker);
        assertTrue(idStart >= 0, "missing mapper statement " + id);
        int statementStart = xml.lastIndexOf("<" + element, idStart);
        int statementEnd = xml.indexOf("</" + element + ">", idStart);
        assertTrue(statementStart >= 0);
        assertTrue(statementEnd >= 0);
        return xml.substring(statementStart, statementEnd);
    }
}
