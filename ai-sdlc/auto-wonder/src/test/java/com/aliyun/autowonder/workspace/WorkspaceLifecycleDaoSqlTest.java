package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskDao;
import com.aliyun.autowonder.user.UserDao;
import org.apache.ibatis.annotations.Param;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mapper contract for the workspace lifecycle: edit (F1), logical delete (F2), the active name
 * key (F3), the recycle bin (F4) and restore (F5). These predicates carry the invariants that
 * Mockito-based tests cannot see — which columns move together in one statement, and which guards
 * make a racing call return 0 instead of overwriting a row.
 *
 * <p>{@link WorkspaceMemberDaoSqlTest} keeps {@code listByUser}, {@code findByIdForUpdate},
 * {@code updateOwner}, {@code listAllPaged} and {@code countAll}; they are not repeated here.
 */
class WorkspaceLifecycleDaoSqlTest {

    @Test
    void insertWritesTheActiveNameKeySoTheUniqueIndexCoversNewRows() throws Exception {
        String insert = statement(orgXml(), "insert", "insert");

        assertTrue(insert.contains(
                "INSERT INTO `org` (name, active_name_key, slug, description, background, owner_id, "
                        + "status, creator_id, is_deleted, version)"),
                "insert must persist active_name_key, got: " + insert);
        assertTrue(insert.contains("VALUES (#{name}, #{activeNameKey}"),
                "insert must bind activeNameKey rather than leave it NULL, got: " + insert);
    }

    @Test
    void nameUniquenessIsCheckedAgainstTheActiveKeyNotTheStoredName() throws Exception {
        String xml = orgXml();
        String findByName = statement(xml, "findByName", "select");
        String countActiveByName = statement(xml, "countActiveByName", "select");

        // D2/F3: a deleted row keeps its name for the recycle bin listing but releases
        // active_name_key to NULL, so both lookups must be keyed on that column to stay
        // inside uk_active_name and to stop matching the deleted row.
        assertTrue(findByName.contains("WHERE active_name_key = #{name}"),
                "findByName must match the active name key, got: " + findByName);
        assertTrue(countActiveByName.contains("WHERE active_name_key = #{name}"),
                "countActiveByName must match the active name key, got: " + countActiveByName);
    }

    @Test
    void theDuplicateNameCheckCanExcludeItsOwnRow() throws Exception {
        String countActiveByName = statement(orgXml(), "countActiveByName", "select");

        // F1.5: renaming a workspace must not report its own name as taken.
        assertTrue(countActiveByName.contains("<if test=\"excludeId != null\">AND id != #{excludeId}</if>"),
                "countActiveByName must skip the caller's own row when excludeId is set, got: "
                        + countActiveByName);
    }

    @Test
    void theBulkNameLookupUsesAParameterisedInList() throws Exception {
        String listActiveNames = statement(orgXml(), "listActiveNames", "select");

        assertTrue(listActiveNames.contains("SELECT active_name_key FROM `org`"),
                "listActiveNames must read the active key column, got: " + listActiveNames);
        assertTrue(listActiveNames.contains(
                        "<foreach collection=\"names\" item=\"candidate\" open=\"(\" separator=\",\" close=\")\">"
                                + "#{candidate}</foreach>"),
                "listActiveNames must expand the name list with a foreach instead of "
                        + "concatenating, got: " + listActiveNames);
    }

    @Test
    void theLifecycleFinderDeliberatelySkipsTheDeletedFilter() throws Exception {
        String xml = orgXml();
        String anyState = statement(xml, "findByIdAnyState", "select");
        String active = statement(xml, "findById", "select");

        // F5.2: restore and the recycle bin must reach rows the token-scoped finder hides.
        assertEquals("<select id=\"findByIdAnyState\" resultType=\"com.aliyun.autowonder.workspace.WorkspaceDO\"> "
                + "SELECT * FROM `org` WHERE id = #{id} LIMIT 1", anyState);
        assertFalse(anyState.contains("is_deleted"),
                "findByIdAnyState must not filter on is_deleted, got: " + anyState);
        assertTrue(active.contains("WHERE id = #{id} AND is_deleted = 0"),
                "findById must keep filtering out deleted rows, got: " + active);
    }

    @Test
    void theAuthFilterReadsLiveUsabilityStraightFromTheRow() throws Exception {
        String countUsable = statement(orgXml(), "countUsable", "select");

        // F6.1/F6.2: an already-issued token stops working as soon as the workspace is
        // deleted or disabled, without waiting for the jti blacklist to propagate.
        assertTrue(countUsable.contains("SELECT COUNT(*) FROM `org`"),
                "countUsable must count rows, got: " + countUsable);
        assertTrue(countUsable.contains("WHERE id = #{id} AND is_deleted = 0 AND status = 0"),
                "countUsable must require both is_deleted = 0 and status = 0, got: " + countUsable);
    }

    @Test
    void theDetailUpdateKeepsBothNameColumnsInStepAndIsVersionGuarded() throws Exception {
        String updateDetail = statement(orgXml(), "updateDetail", "update");

        assertTrue(updateDetail.contains("SET name = #{name}, active_name_key = #{activeNameKey}"),
                "updateDetail must write name and active_name_key together so the unique index "
                        + "still covers the renamed row, got: " + updateDetail);
        assertTrue(updateDetail.contains("description = #{description}"));
        assertTrue(updateDetail.contains("background = #{background}"));
        assertTrue(updateDetail.contains("modifier_id = #{modifierId}"));
        assertTrue(updateDetail.contains("gmt_modified = NOW(3)"));
        // F1.5: the version guard is what turns a concurrent edit into 0 rows rather than a
        // silent overwrite.
        assertTrue(updateDetail.contains("version = version + 1"),
                "updateDetail must bump version so the next stale caller loses, got: " + updateDetail);
        assertTrue(updateDetail.contains("WHERE id = #{id} AND is_deleted = 0 AND version = #{expectedVersion}"),
                "updateDetail must guard on is_deleted and the expected version, got: " + updateDetail);
    }

    @Test
    void theLogicalDeleteReleasesTheNameInTheSameStatementAndIsIdempotent() throws Exception {
        String softDelete = statement(orgXml(), "softDelete", "update");

        // F2.2: no physical delete, so is_deleted flips and every other column stays put.
        assertTrue(softDelete.contains("SET is_deleted = 1"),
                "softDelete must logically delete rather than remove the row, got: " + softDelete);
        assertFalse(softDelete.contains("DELETE FROM"),
                "softDelete must never delete rows physically, got: " + softDelete);
        // F3.3: the name is released in the same statement, so there is no window where a
        // deleted workspace still blocks its own name.
        assertTrue(softDelete.contains("active_name_key = NULL"),
                "softDelete must release active_name_key in the same statement, got: " + softDelete);
        assertFalse(softDelete.contains("name = NULL"),
                "softDelete must keep the stored name so the recycle bin can list it, got: "
                        + softDelete);
        assertTrue(softDelete.contains("deleted_at = NOW(3)"),
                "softDelete must stamp the deletion time, got: " + softDelete);
        assertTrue(softDelete.contains("deleted_by = #{deletedBy}"),
                "softDelete must record who deleted it, got: " + softDelete);
        assertTrue(softDelete.contains("version = version + 1"));
        assertTrue(softDelete.contains("WHERE id = #{id} AND is_deleted = 0"),
                "the is_deleted = 0 guard is what makes a duplicate delete return 0 instead of "
                        + "re-stamping deleted_at/deleted_by, got: " + softDelete);
    }

    @Test
    void theRestoreReactivatesRebindsTheNameAndClearsTheDeletionTrail() throws Exception {
        String restore = statement(orgXml(), "restore", "update");

        assertTrue(restore.contains("SET is_deleted = 0"),
                "restore must reactivate the row, got: " + restore);
        // F5.3: name and active_name_key are both rebound, so D4's rename-on-restore lands
        // inside the unique index in one statement.
        assertTrue(restore.contains("name = #{name}, active_name_key = #{activeNameKey}"),
                "restore must rebind name and active_name_key together, got: " + restore);
        assertTrue(restore.contains("deleted_at = NULL"),
                "restore must clear the deletion time, got: " + restore);
        assertTrue(restore.contains("deleted_by = NULL"),
                "restore must clear the deleter, got: " + restore);
        assertTrue(restore.contains("modifier_id = #{modifierId}"));
        assertTrue(restore.contains("version = version + 1"));
        // F5.6: conditional on is_deleted = 1, so a concurrent second restore updates 0 rows
        // and the caller can report success idempotently.
        assertTrue(restore.contains("WHERE id = #{id} AND is_deleted = 1"),
                "restore must be conditional on is_deleted = 1 so a duplicate restore is a "
                        + "no-op, got: " + restore);
    }

    @Test
    void theMembershipListCarriesTheOwnerColumnTheCardsNeed() throws Exception {
        String listMemberships = statement(orgXml(), "listMembershipsByUser", "select");

        // D8: there is no OWNER access level, so ownership is read off org.owner_id and the
        // bulk membership read has to select it or isOwner cannot be derived in one query.
        assertTrue(listMemberships.contains("o.owner_id"),
                "listMembershipsByUser must select owner_id so isOwner needs no extra query, got: "
                        + listMemberships);
        assertTrue(listMemberships.contains("m.access_level"));
        assertTrue(listMemberships.contains("o.is_deleted = 0"),
                "listMembershipsByUser must hide deleted workspaces, got: " + listMemberships);
    }

    @Test
    void recycleBinPageAndTotalShareOneIdentityFilter() throws Exception {
        String xml = orgXml();
        String page = statement(xml, "pageRecycleBin", "select");
        String count = statement(xml, "countRecycleBin", "select");

        assertTrue(page.contains("<include refid=\"recycleBinFilter\"/>"),
                "pageRecycleBin must include the shared filter instead of inlining the "
                        + "visibility predicate, got: " + page);
        assertTrue(count.contains("<include refid=\"recycleBinFilter\"/>"),
                "countRecycleBin must include the same filter so the total matches the page, got: "
                        + count);
    }

    @Test
    void recycleBinVisibilityIsDecidedInSqlNotInMemory() throws Exception {
        String filter = statement(orgXml(), "recycleBinFilter", "sql");

        assertTrue(filter.contains("WHERE o.is_deleted = 1"),
                "the recycle bin must only list deleted workspaces, got: " + filter);
        // F4.2: identity filtering has to happen in the database, otherwise a non-member
        // guessing an id sees rows they must not, and the total no longer matches the page.
        assertTrue(filter.contains("<if test=\"!systemAdmin\">"),
                "the ownership predicate must be dropped wholesale for platform admins, got: "
                        + filter);
        assertTrue(filter.contains("o.owner_id = #{userId}"),
                "the original owner must see their deleted workspaces, got: " + filter);
        assertTrue(filter.contains("EXISTS (SELECT 1 FROM org_member m"),
                "the original ADMIN must see them through their preserved membership row, got: "
                        + filter);
        assertTrue(filter.contains("m.access_level = 'ADMIN'"),
                "only ADMIN memberships may see the recycle bin, got: " + filter);
        assertTrue(filter.contains("m.status = 0") && filter.contains("m.is_deleted = 0"),
                "a deactivated or removed ADMIN must lose visibility, got: " + filter);
        assertTrue(filter.contains("m.tenant_id = o.id") && filter.contains("m.user_id = #{userId}"),
                "the membership subquery must be scoped to this workspace and this user, got: "
                        + filter);
    }

    @Test
    void recycleBinSearchAndOrderingArePartOfTheSharedFilter() throws Exception {
        String xml = orgXml();
        String filter = statement(xml, "recycleBinFilter", "sql");
        String page = statement(xml, "pageRecycleBin", "select");

        assertTrue(filter.contains(
                        "<if test=\"keyword != null and keyword != ''\"> AND o.name LIKE CONCAT('%', #{keyword}, '%') </if>"),
                "the keyword search must be parameterised and optional, got: " + filter);
        // F4.6: newest deletion first, with a unique tiebreaker because deleted_at is
        // DATETIME(3) and bulk deletes tie.
        assertTrue(page.contains("ORDER BY o.deleted_at DESC, o.id DESC"),
                "pageRecycleBin must order by deleted_at DESC with id as tiebreaker, got: " + page);
        assertTrue(page.contains("LIMIT #{limit} OFFSET #{offset}"),
                "pageRecycleBin must paginate in SQL, got: " + page);
    }

    @Test
    void workspaceDaoDeclaresTheLifecycleParameterNames() throws Exception {
        assertWorkspaceParamNames("findByIdAnyState", List.of("id"), Long.class);
        assertWorkspaceParamNames("countUsable", List.of("id"), Long.class);
        assertWorkspaceParamNames("findByName", List.of("name"), String.class);
        assertWorkspaceParamNames("updateDetail",
                List.of("id", "name", "activeNameKey", "description", "background",
                        "expectedVersion", "modifierId"),
                Long.class, String.class, String.class, String.class, String.class,
                Integer.class, Long.class);
        assertWorkspaceParamNames("softDelete", List.of("id", "deletedBy"), Long.class, Long.class);
        assertWorkspaceParamNames("restore",
                List.of("id", "name", "activeNameKey", "modifierId"),
                Long.class, String.class, String.class, Long.class);
        assertWorkspaceParamNames("countActiveByName", List.of("name", "excludeId"),
                String.class, Long.class);
        assertWorkspaceParamNames("listActiveNames", List.of("names"), Collection.class);
        assertWorkspaceParamNames("listMembershipsByUser", List.of("userId"), Long.class);
        assertWorkspaceParamNames("pageRecycleBin",
                List.of("userId", "systemAdmin", "keyword", "offset", "limit"),
                Long.class, boolean.class, String.class, int.class, int.class);
        assertWorkspaceParamNames("countRecycleBin",
                List.of("userId", "systemAdmin", "keyword"),
                Long.class, boolean.class, String.class);
    }

    @Test
    void workspaceDaoReturnsRowCountsSoCallersCanDetectALostRace() throws Exception {
        assertEquals(int.class, WorkspaceDao.class.getDeclaredMethod(
                "updateDetail", Long.class, String.class, String.class, String.class, String.class,
                Integer.class, Long.class).getReturnType());
        assertEquals(int.class, WorkspaceDao.class.getDeclaredMethod(
                "softDelete", Long.class, Long.class).getReturnType());
        assertEquals(int.class, WorkspaceDao.class.getDeclaredMethod(
                "restore", Long.class, String.class, String.class, Long.class).getReturnType());
        assertEquals(long.class, WorkspaceDao.class.getDeclaredMethod(
                "countActiveByName", String.class, Long.class).getReturnType());
        assertEquals(long.class, WorkspaceDao.class.getDeclaredMethod(
                "countRecycleBin", Long.class, boolean.class, String.class).getReturnType());
    }

    @Test
    void deletingPausesEveryActiveTimerOfTheWorkspaceInOneStatement() throws Exception {
        String xml = mapperXml("/mapping/ScheduledTaskDao.xml");
        String pause = statement(xml, "pauseActiveByWorkspace", "update");

        // F2.4: the pause must stay inside the delete transaction, so it has to be a single
        // bulk statement rather than a read-modify-write loop.
        assertEquals("<update id=\"pauseActiveByWorkspace\" databaseId=\"autowonder-source-aware\"> "
                + "UPDATE scheduled_task SET status = 'PAUSED', modifier_id = #{modifierId}, "
                + "version = version + 1 WHERE workspace_id = #{workspaceId} "
                + "AND status = 'ACTIVE' AND is_deleted = 0", pause);
        assertParamNames(ScheduledTaskDao.class, "pauseActiveByWorkspace",
                List.of("workspaceId", "modifierId"), Long.class, Long.class);
    }

    @Test
    void theDispatchLinkageFetchesOnlyInFlightRowsAndBoundsTheBatch() throws Exception {
        String xml = mapperXml("/mapping/DispatchDao.xml");
        String list = statement(xml, "listInFlightByTenant", "select");

        // The caller passes the in-flight status set explicitly, so the mapper must not
        // hardcode a narrower one and silently leave dispatches running after a delete.
        assertTrue(list.contains("WHERE tenant_id = #{tenantId} AND is_deleted = 0 AND status IN"),
                "listInFlightByTenant must scope to the workspace and the caller's statuses, got: "
                        + list);
        assertTrue(list.contains("<foreach collection=\"statuses\" item=\"s\" open=\"(\" "
                        + "separator=\",\" close=\")\">#{s}</foreach>"),
                "the status list must be expanded with a foreach, got: " + list);
        // A delete must not fan out to an unbounded number of remote pause calls.
        assertTrue(list.contains("ORDER BY id ASC LIMIT #{limit}"),
                "listInFlightByTenant must bound and stabilise the batch, got: " + list);
        assertParamNames(DispatchDao.class, "listInFlightByTenant",
                List.of("tenantId", "statuses", "limit"), Long.class, Collection.class, int.class);
    }

    @Test
    void platformAdminFlaggingIsGuardedSoSelfHealIsIdempotent() throws Exception {
        String xml = mapperXml("/mapping/UserDao.xml");
        String count = statement(xml, "countSystemAdmins", "select");
        String mark = statement(xml, "markSystemAdmin", "update");

        // D3: is_admin is a new column on the existing table, not a new table.
        assertEquals("<select id=\"countSystemAdmins\" resultType=\"long\"> "
                + "SELECT COUNT(*) FROM `user` WHERE is_deleted = 0 AND is_admin = 1", count);
        assertEquals("<update id=\"markSystemAdmin\"> UPDATE `user` SET is_admin = 1, "
                + "gmt_modified = NOW() WHERE id = #{id} AND is_deleted = 0 AND is_admin = 0", mark);
        assertParamNames(UserDao.class, "markSystemAdmin", List.of("id"), Long.class);
    }

    @Test
    void theLegacyPlatformAdminFallbackPicksTheLowestActiveIdDeterministically() throws Exception {
        String first = statement(mapperXml("/mapping/UserDao.xml"), "findFirstActiveUserId", "select");

        // Concurrent self-heal callers must all resolve the same user, otherwise two of them
        // get promoted and isSystemAdmin stops being a single-owner check.
        assertEquals("<select id=\"findFirstActiveUserId\" resultType=\"java.lang.Long\"> "
                + "SELECT id FROM `user` WHERE is_deleted = 0 AND status = 0 ORDER BY id ASC LIMIT 1",
                first);
        Method method = UserDao.class.getDeclaredMethod("findFirstActiveUserId");
        assertEquals(Long.class, method.getReturnType());
        assertEquals(0, method.getParameterCount());
    }

    private static void assertWorkspaceParamNames(String methodName, List<String> expectedNames,
                                                  Class<?>... parameterTypes) throws Exception {
        assertParamNames(WorkspaceDao.class, methodName, expectedNames, parameterTypes);
    }

    private static void assertParamNames(Class<?> dao, String methodName, List<String> expectedNames,
                                         Class<?>... parameterTypes) throws Exception {
        Method method = dao.getDeclaredMethod(methodName, parameterTypes);
        List<String> actualNames = Arrays.stream(method.getParameters())
                .map(WorkspaceLifecycleDaoSqlTest::paramName)
                .toList();
        assertEquals(expectedNames, actualNames,
                dao.getSimpleName() + "." + methodName + " parameter names must match the mapper");
    }

    private static String paramName(Parameter parameter) {
        Param annotation = parameter.getAnnotation(Param.class);
        assertNotNull(annotation, "missing @Param on " + parameter);
        return annotation.value();
    }

    private String orgXml() throws Exception {
        return mapperXml("/mapping/WorkspaceDao.xml");
    }

    private String mapperXml(String resource) throws Exception {
        try (var stream = getClass().getResourceAsStream(resource)) {
            assertNotNull(stream, "missing mapper " + resource);
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
        // Trimmed so a whole-statement comparison is not thrown off by the indentation that
        // sits between the last SQL token and the closing tag.
        return xml.substring(statementStart, statementEnd).trim();
    }
}
