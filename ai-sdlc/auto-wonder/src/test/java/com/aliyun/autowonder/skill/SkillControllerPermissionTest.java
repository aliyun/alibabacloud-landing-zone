package com.aliyun.autowonder.skill;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class SkillControllerPermissionTest {

    @Test
    void connectionTestRequiresReadWriteAccess() throws NoSuchMethodException {
        RequireWorkspaceAccess access = SkillController.class
                .getMethod("testConnection", Long.class, Long.class)
                .getAnnotation(RequireWorkspaceAccess.class);

        assertNotNull(access);
        assertEquals(WorkspaceAccessLevel.READ_WRITE, access.value());
        assertEquals("测试技能连接", access.action());
    }
}
