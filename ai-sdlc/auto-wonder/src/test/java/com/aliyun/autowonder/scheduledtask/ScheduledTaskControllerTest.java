package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ScheduledTaskControllerTest {
    @Test
    void taskReadsAreReadOnlyAndMutationsReadWrite() throws Exception {
        assertAccess(ScheduledTaskController.class, "list", WorkspaceAccessLevel.READ_ONLY);
        assertAccess(ScheduledTaskController.class, "create", WorkspaceAccessLevel.READ_WRITE);
        assertAccess(ScheduledTaskController.class, "runNow", WorkspaceAccessLevel.READ_WRITE);
    }

    private void assertAccess(Class<?> type, String name, WorkspaceAccessLevel expected) {
        Method method = java.util.Arrays.stream(type.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();
        RequireWorkspaceAccess methodAccess = AnnotatedElementUtils.findMergedAnnotation(method, RequireWorkspaceAccess.class);
        RequireWorkspaceAccess access = methodAccess == null
                ? AnnotatedElementUtils.findMergedAnnotation(type, RequireWorkspaceAccess.class) : methodAccess;
        assertNotNull(access);
        assertEquals(expected, access.value());
    }
}
