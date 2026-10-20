package com.aliyun.autowonder.im;

import com.aliyun.autowonder.dao.MybatisXmlConfigurationSupport;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserImIdentityDaoContractTest {

    @Test
    void bulkSoftDeleteStatementIsRegistered() {
        Configuration configuration = MybatisXmlConfigurationSupport.loadConfigurationFromMapperXml();
        String statementId = "com.aliyun.autowonder.im.UserImIdentityDao.softDeleteAllByUser";

        assertTrue(configuration.hasStatement(statementId),
                "missing MyBatis statement: " + statementId
                        + " (UserImIdentityDao.xml statement id must match the Java method name)");
    }

    @Test
    void bulkSoftDeleteBumpsTheVersionAndOnlyTouchesLiveRows() throws Exception {
        String statement = statement("softDeleteAllByUser", "update");

        assertTrue(statement.contains("UPDATE user_im_identity"),
                "account deletion must clear the IM bindings, got: " + statement);
        assertTrue(statement.contains("is_deleted = 1"),
                "the bindings must be logically deleted, got: " + statement);
        assertTrue(statement.contains("version = version + 1"),
                "the rows are version-guarded; a soft delete that skips the bump would let a "
                        + "stale editor overwrite the tombstone, got: " + statement);
        assertTrue(statement.contains("modifier_id = #{modifierId}"),
                "the operator must be recorded, got: " + statement);
        assertTrue(statement.contains("WHERE user_id = #{userId} AND is_deleted = 0"),
                "the update must be scoped to the target user's live rows so a repeat call is a "
                        + "no-op, got: " + statement);
    }

    @Test
    void bulkSoftDeleteTakesTheTargetUserAndOperator() throws Exception {
        Method method = UserImIdentityDao.class.getDeclaredMethod(
                "softDeleteAllByUser", long.class, long.class);

        assertEquals(int.class, method.getReturnType());
        Param userId = method.getParameters()[0].getAnnotation(Param.class);
        Param modifierId = method.getParameters()[1].getAnnotation(Param.class);
        assertNotNull(userId, "missing @Param on the target user");
        assertNotNull(modifierId, "missing @Param on the operator");
        assertEquals("userId", userId.value());
        assertEquals("modifierId", modifierId.value());
    }

    private String statement(String id, String tag) throws Exception {
        try (var stream = getClass().getResourceAsStream("/mapping/UserImIdentityDao.xml")) {
            assertNotNull(stream);
            String xml = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                    .replaceAll("\\s+", " ")
                    .trim();
            int idStart = xml.indexOf("id=\"" + id + "\"");
            assertTrue(idStart >= 0, "missing mapper statement " + id);
            int statementStart = xml.lastIndexOf("<" + tag, idStart);
            int statementEnd = xml.indexOf("</" + tag + ">", idStart);
            assertTrue(statementStart >= 0 && statementEnd > statementStart,
                    "missing opening or closing " + tag + " for " + id);
            return xml.substring(statementStart, statementEnd);
        }
    }
}
