package com.aliyun.autowonder.user;

import com.aliyun.autowonder.dao.MybatisXmlConfigurationSupport;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserDaoStatementBindingTest {

    private static final String STATEMENT_ID =
            "com.aliyun.autowonder.user.UserDao.searchWorkspaceCandidates";
    private static final String LIST_ADMINS_ID =
            "com.aliyun.autowonder.user.UserDao.listSystemAdmins";
    private static final String REVOKE_ADMIN_ID =
            "com.aliyun.autowonder.user.UserDao.revokeSystemAdmin";
    private static final String SEARCH_ADMIN_CANDIDATES_ID =
            "com.aliyun.autowonder.user.UserDao.searchSystemAdminCandidates";

    @Test
    void searchWorkspaceCandidatesStatementIsRegistered() {
        Configuration configuration = MybatisXmlConfigurationSupport.loadConfigurationFromMapperXml();
        assertTrue(configuration.hasStatement(STATEMENT_ID),
                "missing MyBatis statement: " + STATEMENT_ID
                        + " (UserDao.xml statement id must match the Java method name)");
    }

    @Test
    void searchWorkspaceCandidatesSqlStillQueriesOrgMember() throws IOException {
        String xml = new String(getClass().getResourceAsStream("/mapping/UserDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);
        int start = xml.indexOf("id=\"searchWorkspaceCandidates\"");
        assertTrue(start >= 0, "UserDao.xml has no select with id=searchWorkspaceCandidates");
        int end = xml.indexOf("</select>", start);
        String statement = xml.substring(start, end);
        assertTrue(statement.contains("org_member"),
                "searchWorkspaceCandidates must keep querying org_member; table rename is reverted");
    }

    @Test
    void platformAdminStatementsAreRegistered() {
        Configuration configuration = MybatisXmlConfigurationSupport.loadConfigurationFromMapperXml();

        assertTrue(configuration.hasStatement(LIST_ADMINS_ID),
                "missing MyBatis statement: " + LIST_ADMINS_ID);
        assertTrue(configuration.hasStatement(REVOKE_ADMIN_ID),
                "missing MyBatis statement: " + REVOKE_ADMIN_ID);
        assertTrue(configuration.hasStatement(SEARCH_ADMIN_CANDIDATES_ID),
                "missing MyBatis statement: " + SEARCH_ADMIN_CANDIDATES_ID);
    }

    @Test
    void revokeSystemAdminKeepsTheGuardThatMakesARepeatDemotionANoOp() throws IOException {
        String statement = statement("revokeSystemAdmin", "update");

        assertTrue(statement.contains("SET is_admin = 0"),
                "revokeSystemAdmin must clear the flag, got: " + statement);
        assertTrue(statement.contains("is_admin = 1"),
                "revokeSystemAdmin must only match a current admin so a racing second call writes "
                        + "nothing, got: " + statement);
        assertTrue(statement.contains("is_deleted = 0"),
                "revokeSystemAdmin must not resurrect a deleted row, got: " + statement);
    }

    @Test
    void listSystemAdminsCountsExactlyWhatCountSystemAdminsCounts() throws IOException {
        String list = statement("listSystemAdmins", "select");
        String count = statement("countSystemAdmins", "select");

        // The service refuses to remove the last admin by reading countSystemAdmins, and the panel
        // disables the button by reading this roster. Different predicates would let the UI offer a
        // removal the guard then rejects, or hide one the guard would allow.
        assertTrue(list.contains("is_deleted = 0"),
                "listSystemAdmins must scope to live rows, got: " + list);
        assertTrue(list.contains("is_admin = 1"),
                "listSystemAdmins must scope to admins, got: " + list);
        assertFalse(list.contains("status = 0"),
                "listSystemAdmins must not filter on status, or a deactivated admin would vanish "
                        + "from the roster while still being counted, got: " + list);
        assertTrue(count.contains("is_deleted = 0") && count.contains("is_admin = 1"),
                "countSystemAdmins predicate changed; keep it aligned with listSystemAdmins, got: "
                        + count);
    }

    @Test
    void searchSystemAdminCandidatesOnlyOffersActiveUsersWhoAreNotAdminsYet() throws IOException {
        String statement = statement("searchSystemAdminCandidates", "select");

        assertTrue(statement.contains("status = 0"),
                "candidates must be active accounts, got: " + statement);
        assertTrue(statement.contains("is_admin = 0"),
                "candidates must exclude current admins, got: " + statement);
        assertTrue(statement.contains("LIMIT #{limit}"),
                "candidates must stay bounded, got: " + statement);
    }

    @Test
    void updateProfileStatementIsRegisteredAndOnlyTouchesProfileColumns() throws IOException {
        Configuration configuration = MybatisXmlConfigurationSupport.loadConfigurationFromMapperXml();
        String statementId = "com.aliyun.autowonder.user.UserDao.updateProfile";
        assertTrue(configuration.hasStatement(statementId),
                "missing MyBatis statement: " + statementId);

        String statement = statement("updateProfile", "update");
        assertTrue(statement.contains("nickname = #{nickname}")
                        && statement.contains("email = #{email}")
                        && statement.contains("phone = #{phone}"),
                "updateProfile must write the three self-service profile columns, got: " + statement);
        assertTrue(statement.contains("is_deleted = 0"),
                "updateProfile must not resurrect a deleted row, got: " + statement);
        assertFalse(statement.contains("password_hash"),
                "updateProfile must not touch credentials, got: " + statement);
        assertFalse(statement.contains("username")
                        || statement.contains("is_admin")
                        || statement.contains("status ="),
                "updateProfile must not touch identity/role/status columns, got: " + statement);
    }

    @Test
    void platformUserManagementStatementsAreRegistered() {
        Configuration configuration = MybatisXmlConfigurationSupport.loadConfigurationFromMapperXml();
        String prefix = "com.aliyun.autowonder.user.UserDao.";

        for (String id : List.of("findActiveByEmail", "countAllUsers", "searchUsersForAdmin",
                "countUsersForAdmin", "findByIdIncludingDeleted", "listByIdsIncludingDeleted",
                "logicalDeleteUser", "restoreUser")) {
            assertTrue(configuration.hasStatement(prefix + id),
                    "missing MyBatis statement: " + prefix + id
                            + " (UserDao.xml statement id must match the Java method name)");
        }
    }

    @Test
    void logicalDeleteTakesTheSnapshotAndReleasesBothUniqueKeysInOneUpdate() throws IOException {
        String statement = statement("logicalDeleteUser", "update");

        // The row must stay readable as an audit trail while username/email stop occupying
        // their unique indexes; a two-step delete would leave a window where a recreate
        // collides, so everything has to happen in this single UPDATE.
        assertTrue(statement.contains("deleted_username = username"),
                "the original username must be snapshotted, got: " + statement);
        assertTrue(statement.contains("deleted_email = email"),
                "the original email must be snapshotted, got: " + statement);
        assertTrue(statement.contains("username = CONCAT('#deleted-', id)"),
                "the username must be rewritten so uk_username is released, got: " + statement);
        assertTrue(statement.contains("email = NULL"),
                "the email must be nulled so uk_email is released (MySQL allows repeated NULL), got: "
                        + statement);
        assertTrue(statement.contains("deleted_at = #{deletedAt}")
                        && statement.contains("deleted_by = #{deletedBy}"),
                "the deletion trail must record when and by whom, got: " + statement);
        assertTrue(statement.contains("is_deleted = 1"),
                "the row must be marked deleted, got: " + statement);
        assertTrue(statement.contains("WHERE id = #{id} AND is_deleted = 0"),
                "the is_deleted = 0 guard makes a repeat delete a no-op, got: " + statement);
        assertFalse(statement.contains("password_hash") || statement.contains("nickname =")
                        || statement.contains("status ="),
                "logical delete must preserve credentials, profile and status for the snapshot "
                        + "display, got: " + statement);
    }

    @Test
    void restoreRebuildsIdentityFromTheSnapshotAndClearsEveryDeletionTrail() throws IOException {
        String statement = statement("restoreUser", "update");

        assertTrue(statement.contains("username = deleted_username"),
                "restore must bring the original username back, got: " + statement);
        assertTrue(statement.contains("email = deleted_email"),
                "restore must bring the original email back, got: " + statement);
        assertTrue(statement.contains("deleted_username = NULL")
                        && statement.contains("deleted_email = NULL")
                        && statement.contains("deleted_at = NULL")
                        && statement.contains("deleted_by = NULL"),
                "restore must clear the deletion snapshot, got: " + statement);
        assertTrue(statement.contains("deactivated_at = NULL")
                        && statement.contains("cooling_off_expires_at = NULL")
                        && statement.contains("deactivation_revoked_at = NULL"),
                "restore must also clear the legacy cooling-off trail so the account does not "
                        + "resurface as mid-deactivation, got: " + statement);
        assertTrue(statement.contains("is_deleted = 0"),
                "restore must lift the deletion flag, got: " + statement);
        assertTrue(statement.contains("WHERE id = #{id} AND is_deleted = 1"),
                "the is_deleted = 1 guard keeps restore from clobbering a live account, got: "
                        + statement);
    }

    @Test
    void adminListAndItsTotalShareOnePredicate() throws IOException {
        String list = statement("searchUsersForAdmin", "select");
        String count = statement("countUsersForAdmin", "select");

        assertFalse(whereClause(list).isEmpty(), "searchUsersForAdmin must carry a <where> block");
        assertEquals(whereClause(count), whereClause(list),
                "the count predicate drifted from the page predicate; the UI would then paginate "
                        + "into empty pages. Keep both <where> blocks byte-identical.");

        for (String column : List.of("username", "nickname", "email", "phone",
                "deleted_username", "deleted_email")) {
            assertTrue(list.contains(column + " LIKE CONCAT('%', #{keyword}, '%')"),
                    "keyword search must also match " + column + ", otherwise deleted users are "
                            + "unsearchable by their pre-deletion name, got: " + list);
        }
        assertTrue(list.contains("is_deleted = 0 AND (status &lt;&gt; 1 OR password_hash &lt;&gt; 'DEACTIVATED')"),
                "the NORMAL filter must exclude both deleted and legacy-deactivated rows, got: "
                        + list);
        assertTrue(list.contains("is_deleted = 1"),
                "the DELETED filter must select logically deleted rows, got: " + list);
        assertTrue(list.contains("ORDER BY id DESC"),
                "the admin list must stay in registration-descending order, got: " + list);
        assertTrue(list.contains("LIMIT #{limit} OFFSET #{offset}"),
                "the page query must stay bounded, got: " + list);
        assertFalse(count.contains("LIMIT"),
                "the total query must not paginate, got: " + count);
    }

    @Test
    void adminLookupsAndCountsScopeExactlyAsDocumented() throws IOException {
        String findByIdIncludingDeleted = statement("findByIdIncludingDeleted", "select");
        assertFalse(findByIdIncludingDeleted.contains("is_deleted"),
                "display resolution must see deleted rows too, got: " + findByIdIncludingDeleted);
        assertTrue(findByIdIncludingDeleted.contains("WHERE id = #{id}"),
                "display resolution must look the row up by id, got: " + findByIdIncludingDeleted);

        String listByIdsIncludingDeleted = statement("listByIdsIncludingDeleted", "select");
        assertFalse(listByIdsIncludingDeleted.contains("is_deleted"),
                "batch display resolution must see deleted rows too, got: "
                        + listByIdsIncludingDeleted);
        assertTrue(listByIdsIncludingDeleted.contains("<foreach collection=\"ids\""),
                "batch resolution must support the id collection, got: "
                        + listByIdsIncludingDeleted);

        String findActiveByEmail = statement("findActiveByEmail", "select");
        assertTrue(findActiveByEmail.contains("WHERE email = #{email} AND is_deleted = 0"),
                "email uniqueness checks must ignore rows whose email was released by a delete, "
                        + "got: " + findActiveByEmail);
        assertTrue(findActiveByEmail.contains("LIMIT 1"),
                "the email lookup must stay a single-row read, got: " + findActiveByEmail);

        String countAllUsers = statement("countAllUsers", "select");
        assertTrue(countAllUsers.contains("COUNT(*) FROM `user`"),
                "the header total must count the whole table, got: " + countAllUsers);
        assertFalse(countAllUsers.contains("WHERE"),
                "the header total must include deleted users, got: " + countAllUsers);
    }

    private static String whereClause(String statement) {
        int start = statement.indexOf("<where>");
        int end = statement.indexOf("</where>", start);
        assertTrue(start >= 0 && end > start, "statement has no <where> block: " + statement);
        return statement.substring(start, end).replaceAll("\\s+", " ").trim();
    }

    private String statement(String id, String tag) throws IOException {
        String xml = new String(getClass().getResourceAsStream("/mapping/UserDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);
        int start = xml.indexOf("id=\"" + id + "\"");
        assertTrue(start >= 0, "UserDao.xml has no " + tag + " with id=" + id);
        int end = xml.indexOf("</" + tag + ">", start);
        return xml.substring(start, end);
    }
}
