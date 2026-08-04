package com.aliyun.autowonder.skill;

import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SkillControllerPermissionTest {

    @Test
    void connectionTestRequiresReadWriteAccess() throws NoSuchMethodException {
        RequireOrgAccess access = SkillController.class
                .getMethod("testConnection", Long.class, Long.class)
                .getAnnotation(RequireOrgAccess.class);

        assertNotNull(access);
        assertEquals(OrgAccessLevel.READ_WRITE, access.value());
        assertEquals("测试技能连接", access.action());
    }
}
