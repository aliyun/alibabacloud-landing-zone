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

class OrgAccessLevelTest {

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void allowsAccessAtOrBelowCurrentLevel() {
        assertTrue(OrgAccessLevel.READ_ONLY.allows(OrgAccessLevel.READ_ONLY));
        assertFalse(OrgAccessLevel.READ_ONLY.allows(OrgAccessLevel.READ_WRITE));
        assertFalse(OrgAccessLevel.READ_ONLY.allows(OrgAccessLevel.ADMIN));

        assertTrue(OrgAccessLevel.READ_WRITE.allows(OrgAccessLevel.READ_ONLY));
        assertTrue(OrgAccessLevel.READ_WRITE.allows(OrgAccessLevel.READ_WRITE));
        assertFalse(OrgAccessLevel.READ_WRITE.allows(OrgAccessLevel.ADMIN));

        assertTrue(OrgAccessLevel.ADMIN.allows(OrgAccessLevel.READ_ONLY));
        assertTrue(OrgAccessLevel.ADMIN.allows(OrgAccessLevel.READ_WRITE));
        assertTrue(OrgAccessLevel.ADMIN.allows(OrgAccessLevel.ADMIN));
    }

    @Test
    void minimumReturnsLowerAccessLevelRegardlessOfArgumentOrder() {
        assertEquals(OrgAccessLevel.READ_ONLY,
                OrgAccessLevel.minimum(OrgAccessLevel.READ_ONLY, OrgAccessLevel.ADMIN));
        assertEquals(OrgAccessLevel.READ_ONLY,
                OrgAccessLevel.minimum(OrgAccessLevel.ADMIN, OrgAccessLevel.READ_ONLY));
        assertEquals(OrgAccessLevel.READ_WRITE,
                OrgAccessLevel.minimum(OrgAccessLevel.READ_WRITE, OrgAccessLevel.ADMIN));
        assertEquals(OrgAccessLevel.ADMIN,
                OrgAccessLevel.minimum(OrgAccessLevel.ADMIN, OrgAccessLevel.ADMIN));
    }

    @Test
    void requireOrgAccessHasRuntimeTypeAndMethodMetadataWithDefaults() throws Exception {
        Target target = RequireOrgAccess.class.getAnnotation(Target.class);
        Retention retention = RequireOrgAccess.class.getAnnotation(Retention.class);

        assertEquals(Set.of(ElementType.TYPE, ElementType.METHOD), Set.of(target.value()));
        assertEquals(RetentionPolicy.RUNTIME, retention.value());
        assertEquals(OrgAccessLevel.READ_ONLY,
                RequireOrgAccess.class.getDeclaredMethod("value").getDefaultValue());
        assertEquals("访问组织资源",
                RequireOrgAccess.class.getDeclaredMethod("action").getDefaultValue());
    }

    @Test
    void contextStoresCurrentOrganizationAccessLevel() {
        assertNull(AutoWonderContext.get().getOrgAccessLevel());

        AutoWonderContext.get().setOrgAccessLevel(OrgAccessLevel.READ_WRITE);

        assertEquals(OrgAccessLevel.READ_WRITE, AutoWonderContext.get().getOrgAccessLevel());
    }

    @Test
    void organizationAccessErrorsHaveStableCodes() {
        assertEquals("12007", ErrorCode.ORG_ACCESS_LEVEL_INVALID.getCode());
        assertEquals("12008", ErrorCode.ORG_ACCESS_INSUFFICIENT.getCode());
        assertEquals("12009", ErrorCode.ORG_OWNER_MUTATION_PROTECTED.getCode());
        assertEquals("12010", ErrorCode.ORG_SELF_LEVEL_MUTATION_FORBIDDEN.getCode());
        assertEquals("12011", ErrorCode.ORG_OWNER_TRANSFER_INVALID.getCode());
    }
}
