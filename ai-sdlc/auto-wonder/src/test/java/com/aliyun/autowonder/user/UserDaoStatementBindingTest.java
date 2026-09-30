package com.aliyun.autowonder.user;

import com.aliyun.autowonder.dao.MybatisXmlConfigurationSupport;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

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

    private String statement(String id, String tag) throws IOException {
        String xml = new String(getClass().getResourceAsStream("/mapping/UserDao.xml").readAllBytes(),
                StandardCharsets.UTF_8);
        int start = xml.indexOf("id=\"" + id + "\"");
        assertTrue(start >= 0, "UserDao.xml has no " + tag + " with id=" + id);
        int end = xml.indexOf("</" + tag + ">", start);
        return xml.substring(start, end);
    }
}
