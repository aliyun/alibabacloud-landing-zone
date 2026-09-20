package com.aliyun.autowonder.user;

import com.aliyun.autowonder.dao.MybatisXmlConfigurationSupport;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertTrue;

class UserSettingDaoStatementBindingTest {

    @Test
    void everyDaoMethodHasAMatchingMapperStatement() {
        // XML statement id 必须与接口方法名逐一对齐，否则运行期才报
        // "Invalid bound statement (not found)"，编译期发现不了。
        Configuration configuration = MybatisXmlConfigurationSupport.loadConfigurationFromMapperXml();

        for (Method method : UserSettingDao.class.getDeclaredMethods()) {
            String statementId = UserSettingDao.class.getName() + "." + method.getName();
            assertTrue(configuration.hasStatement(statementId),
                    "missing MyBatis statement: " + statementId
                            + " (UserSettingDao.xml statement id must match the Java method name)");
        }
    }

    @Test
    void statementsAreBoundToTheUserSettingMapperNamespace() {
        Configuration configuration = MybatisXmlConfigurationSupport.loadConfigurationFromMapperXml();

        assertTrue(configuration.hasStatement("com.aliyun.autowonder.user.UserSettingDao.findByUk"));
        assertTrue(configuration.hasStatement("com.aliyun.autowonder.user.UserSettingDao.findLive"));
        assertTrue(configuration.hasStatement("com.aliyun.autowonder.user.UserSettingDao.listByUser"));
        assertTrue(configuration.hasStatement("com.aliyun.autowonder.user.UserSettingDao.softDelete"));
    }
}
