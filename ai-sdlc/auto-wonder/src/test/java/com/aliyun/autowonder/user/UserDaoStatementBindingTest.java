package com.aliyun.autowonder.user;

import com.aliyun.autowonder.dao.MybatisXmlConfigurationSupport;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UserDaoStatementBindingTest {

    private static final String STATEMENT_ID =
            "com.aliyun.autowonder.user.UserDao.searchWorkspaceCandidates";

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
}
