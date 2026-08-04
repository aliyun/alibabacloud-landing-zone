package com.aliyun.autowonder.integration.dingtalk;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class DingtalkRobotBindingDaoTest {
    @Test
    void mapperMethodsResolve() {
        assertNotNull(DingtalkRobotBindingDao.class.getDeclaredMethods());
    }
}
