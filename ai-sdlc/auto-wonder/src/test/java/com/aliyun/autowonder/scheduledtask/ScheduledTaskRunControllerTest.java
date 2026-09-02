package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ScheduledTaskRunControllerTest {
    @Test
    void commentsRequireWriteAccess() {
        Method method = java.util.Arrays.stream(ScheduledTaskRunController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("comment")).findFirst().orElseThrow();
        RequireWorkspaceAccess access = AnnotatedElementUtils.findMergedAnnotation(method, RequireWorkspaceAccess.class);
        assertNotNull(access);
        assertEquals(WorkspaceAccessLevel.READ_WRITE, access.value());
    }
}
