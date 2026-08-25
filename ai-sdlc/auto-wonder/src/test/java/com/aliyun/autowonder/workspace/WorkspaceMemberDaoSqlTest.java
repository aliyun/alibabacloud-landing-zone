package com.aliyun.autowonder.workspace;

import org.apache.ibatis.annotations.Param;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceMemberDaoSqlTest {

    @Test
    void memberModelExposesAccessLevelAndIdentityTagsAsText() throws Exception {
        assertEquals(String.class, WorkspaceMemberDO.class.getDeclaredField("accessLevel").getType());
        assertEquals(String.class, WorkspaceMemberDO.class.getDeclaredField("identityTags").getType());
    }

    @Test
    void daoExposesMemberAccessTagAndLockOperations() throws Exception {
        assertParamNames("updateAccessLevel",
                List.of("tenantId", "userId", "accessLevel", "modifierId"),
                Long.class, Long.class, String.class, Long.class);
        assertParamNames("updateIdentityTags",
                List.of("tenantId", "userId", "identityTags", "modifierId"),
                Long.class, Long.class, String.class, Long.class);
        assertParamNames("findByWorkspaceAndUserForUpdate",
                List.of("tenantId", "userId"),
                Long.class, Long.class);
        Method softDelete = WorkspaceMemberDao.class.getDeclaredMethod(
                "softDelete", Long.class, Long.class, Long.class);
        assertEquals(int.class, softDelete.getReturnType());
        assertParamNames("softDelete",
                List.of("tenantId", "userId", "modifierId"),
                Long.class, Long.class, Long.class);
    }

    @Test
    void workspaceDaoExposesLockAndConditionalOwnerUpdateOperations() throws Exception {
        assertWorkspaceParamNames("findByIdForUpdate", List.of("id"), Long.class);
        assertWorkspaceParamNames("updateOwner",
                List.of("id", "oldOwnerId", "newOwnerId", "modifierId"),
                Long.class, Long.class, Long.class, Long.class);
    }

    @Test
    void insertsPersistAccessLevelAndIdentityTagsAndReactivationUsesCallerValues() throws Exception {
        String xml = mapperXml();
        String insert = statement(xml, "insert", "insert");
        String insertOrActivate = statement(xml, "insertOrActivate", "insert");

        assertTrue(insert.contains(
                "INSERT INTO org_member (tenant_id, user_id, status, joined_at, access_level, identity_tags, creator_id, is_deleted)"));
        assertTrue(insert.contains(
                "VALUES (#{tenantId}, #{userId}, #{status}, NOW(3), #{accessLevel}, #{identityTags}, #{creatorId}, 0)"));

        assertTrue(insertOrActivate.contains(
                "INSERT INTO org_member (tenant_id, user_id, status, joined_at, access_level, identity_tags, creator_id, modifier_id, is_deleted)"));
        assertTrue(insertOrActivate.contains(
                "VALUES (#{tenantId}, #{userId}, #{status}, NOW(3), #{accessLevel}, #{identityTags}, #{creatorId}, #{modifierId}, 0)"));
        assertTrue(insertOrActivate.contains("access_level = #{accessLevel}"));
        assertTrue(insertOrActivate.contains("identity_tags = #{identityTags}"));
        assertTrue(insertOrActivate.contains(
                "joined_at = IF(is_deleted = 1 OR status != 0, NOW(3), joined_at)"));
    }

    @Test
    void accessAndTagUpdatesOnlyModifyActiveNondeletedMemberships() throws Exception {
        String xml = mapperXml();
        String accessUpdate = statement(xml, "updateAccessLevel", "update");
        String tagsUpdate = statement(xml, "updateIdentityTags", "update");

        assertActiveUpdateContract(accessUpdate, "access_level = #{accessLevel}");
        assertActiveUpdateContract(tagsUpdate, "identity_tags = #{identityTags}");
    }

    @Test
    void removalOnlySoftDeletesActiveNondeletedMemberships() throws Exception {
        String softDelete = statement(mapperXml(), "softDelete", "update");

        assertTrue(softDelete.contains("UPDATE org_member SET is_deleted = 1"));
        assertTrue(softDelete.contains("modifier_id = #{modifierId}"));
        assertTrue(softDelete.contains("tenant_id = #{tenantId}"));
        assertTrue(softDelete.contains("user_id = #{userId}"));
        assertTrue(softDelete.contains("status = 0"));
        assertTrue(softDelete.contains("is_deleted = 0"));
    }

    @Test
    void workspaceListOnlyIncludesActiveNondeletedMembershipsAndWorkspaces()
            throws Exception {
        String listByUser = statement(orgMapperXml(), "listByUser", "select");

        assertTrue(listByUser.contains("m.user_id = #{userId}"));
        assertTrue(listByUser.contains("m.status = 0"));
        assertTrue(listByUser.contains("m.is_deleted = 0"));
        assertTrue(listByUser.contains("o.is_deleted = 0"));
    }

    @Test
    void reactivationEvaluatesJoinedAtBeforeResettingStatus() throws Exception {
        String insertOrActivate = statement(mapperXml(), "insertOrActivate", "insert");
        int joinedAtAssignment = insertOrActivate.indexOf(
                "joined_at = IF(is_deleted = 1 OR status != 0, NOW(3), joined_at)");
        int statusAssignment = insertOrActivate.indexOf("status = 0");

        assertTrue(joinedAtAssignment >= 0, "missing joined_at reactivation assignment");
        assertTrue(statusAssignment >= 0, "missing status reactivation assignment");
        assertTrue(joinedAtAssignment < statusAssignment,
                "joined_at must inspect the persisted status before status is reset");
    }

    @Test
    void membershipLockSelectsTheRequestedNondeletedRowForUpdate() throws Exception {
        String lockSelect = statement(mapperXml(), "findByWorkspaceAndUserForUpdate", "select");

        assertTrue(lockSelect.contains("SELECT * FROM org_member"));
        assertTrue(lockSelect.contains("tenant_id = #{tenantId}"));
        assertTrue(lockSelect.contains("user_id = #{userId}"));
        assertTrue(lockSelect.contains("is_deleted = 0"));
        assertTrue(lockSelect.contains("FOR UPDATE"));
    }

    @Test
    void workspaceLockAndOwnerUpdateUseNondeletedRowAndOldOwnerPredicate() throws Exception {
        String xml = orgMapperXml();
        String lockSelect = statement(xml, "findByIdForUpdate", "select");
        String ownerUpdate = statement(xml, "updateOwner", "update");

        assertTrue(lockSelect.contains("SELECT * FROM `org`"));
        assertTrue(lockSelect.contains("id = #{id}"));
        assertTrue(lockSelect.contains("is_deleted = 0"));
        assertTrue(lockSelect.contains("FOR UPDATE"));

        assertTrue(ownerUpdate.contains("UPDATE `org` SET owner_id = #{newOwnerId}"));
        assertTrue(ownerUpdate.contains("modifier_id = #{modifierId}"));
        assertTrue(ownerUpdate.contains("gmt_modified = NOW(3)"));
        assertTrue(ownerUpdate.contains("id = #{id}"));
        assertTrue(ownerUpdate.contains("owner_id = #{oldOwnerId}"));
        assertTrue(ownerUpdate.contains("is_deleted = 0"));
    }

    private static void assertActiveUpdateContract(String sql, String assignment) {
        assertTrue(sql.contains("UPDATE org_member SET " + assignment));
        assertTrue(sql.contains("modifier_id = #{modifierId}"));
        assertTrue(sql.contains("gmt_modified = NOW(3)"));
        assertTrue(sql.contains("tenant_id = #{tenantId}"));
        assertTrue(sql.contains("user_id = #{userId}"));
        assertTrue(sql.contains("status = 0"));
        assertTrue(sql.contains("is_deleted = 0"));
    }

    private static void assertParamNames(String methodName, List<String> expectedNames,
                                         Class<?>... parameterTypes) throws Exception {
        Method method = WorkspaceMemberDao.class.getDeclaredMethod(methodName, parameterTypes);
        List<String> actualNames = Arrays.stream(method.getParameters())
                .map(WorkspaceMemberDaoSqlTest::paramName)
                .toList();
        assertEquals(expectedNames, actualNames);
    }

    private static void assertWorkspaceParamNames(String methodName, List<String> expectedNames,
                                            Class<?>... parameterTypes) throws Exception {
        Method method = WorkspaceDao.class.getDeclaredMethod(methodName, parameterTypes);
        List<String> actualNames = Arrays.stream(method.getParameters())
                .map(WorkspaceMemberDaoSqlTest::paramName)
                .toList();
        assertEquals(expectedNames, actualNames);
    }

    private static String paramName(Parameter parameter) {
        Param annotation = parameter.getAnnotation(Param.class);
        assertNotNull(annotation, "missing @Param on " + parameter);
        return annotation.value();
    }

    private String mapperXml() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mapping/WorkspaceMemberDao.xml")) {
            assertNotNull(stream);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ")
                    .trim();
        }
    }

    private String orgMapperXml() throws Exception {
        try (var stream = getClass().getResourceAsStream("/mapping/WorkspaceDao.xml")) {
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
        assertTrue(statementStart >= 0, "missing opening " + element + " for " + id);
        assertTrue(statementEnd >= 0, "missing closing " + element + " for " + id);
        return xml.substring(statementStart, statementEnd);
    }
}
