package com.aliyun.autowonder.access;

import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceAccessLevelTest {

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void allowsAccessAtOrBelowCurrentLevel() {
        assertTrue(WorkspaceAccessLevel.READ_ONLY.allows(WorkspaceAccessLevel.READ_ONLY));
        assertFalse(WorkspaceAccessLevel.READ_ONLY.allows(WorkspaceAccessLevel.READ_WRITE));
        assertFalse(WorkspaceAccessLevel.READ_ONLY.allows(WorkspaceAccessLevel.ADMIN));

        assertTrue(WorkspaceAccessLevel.READ_WRITE.allows(WorkspaceAccessLevel.READ_ONLY));
        assertTrue(WorkspaceAccessLevel.READ_WRITE.allows(WorkspaceAccessLevel.READ_WRITE));
        assertFalse(WorkspaceAccessLevel.READ_WRITE.allows(WorkspaceAccessLevel.ADMIN));

        assertTrue(WorkspaceAccessLevel.ADMIN.allows(WorkspaceAccessLevel.READ_ONLY));
        assertTrue(WorkspaceAccessLevel.ADMIN.allows(WorkspaceAccessLevel.READ_WRITE));
        assertTrue(WorkspaceAccessLevel.ADMIN.allows(WorkspaceAccessLevel.ADMIN));
    }

    @Test
    void minimumReturnsLowerAccessLevelRegardlessOfArgumentOrder() {
        assertEquals(WorkspaceAccessLevel.READ_ONLY,
                WorkspaceAccessLevel.minimum(WorkspaceAccessLevel.READ_ONLY, WorkspaceAccessLevel.ADMIN));
        assertEquals(WorkspaceAccessLevel.READ_ONLY,
                WorkspaceAccessLevel.minimum(WorkspaceAccessLevel.ADMIN, WorkspaceAccessLevel.READ_ONLY));
        assertEquals(WorkspaceAccessLevel.READ_WRITE,
                WorkspaceAccessLevel.minimum(WorkspaceAccessLevel.READ_WRITE, WorkspaceAccessLevel.ADMIN));
        assertEquals(WorkspaceAccessLevel.ADMIN,
                WorkspaceAccessLevel.minimum(WorkspaceAccessLevel.ADMIN, WorkspaceAccessLevel.ADMIN));
    }

    @Test
    void requireWorkspaceAccessHasRuntimeTypeAndMethodMetadataWithDefaults() throws Exception {
        Target target = RequireWorkspaceAccess.class.getAnnotation(Target.class);
        Retention retention = RequireWorkspaceAccess.class.getAnnotation(Retention.class);

        assertEquals(Set.of(ElementType.TYPE, ElementType.METHOD), Set.of(target.value()));
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
        assertEquals(WorkspaceAccessLevel.READ_ONLY,
                RequireWorkspaceAccess.class.getDeclaredMethod("value").getDefaultValue());
        assertEquals("访问工作空间资源",
                RequireWorkspaceAccess.class.getDeclaredMethod("action").getDefaultValue());
    }

    @Test
    void contextStoresCurrentWorkspaceAccessLevel() {
        assertNull(AutoWonderContext.get().getWorkspaceAccessLevel());

        AutoWonderContext.get().setWorkspaceAccessLevel(WorkspaceAccessLevel.READ_WRITE);

        assertEquals(WorkspaceAccessLevel.READ_WRITE, AutoWonderContext.get().getWorkspaceAccessLevel());
    }

    @Test
    void workspaceAccessErrorsHaveStableCodes() {
        assertEquals("12007", ErrorCode.WORKSPACE_ACCESS_LEVEL_INVALID.getCode());
        assertEquals("12008", ErrorCode.WORKSPACE_ACCESS_INSUFFICIENT.getCode());
        assertEquals("12009", ErrorCode.WORKSPACE_OWNER_MUTATION_PROTECTED.getCode());
        assertEquals("12010", ErrorCode.WORKSPACE_SELF_LEVEL_MUTATION_FORBIDDEN.getCode());
        assertEquals("12011", ErrorCode.WORKSPACE_OWNER_TRANSFER_INVALID.getCode());
    }
}
