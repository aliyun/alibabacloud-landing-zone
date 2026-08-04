package com.aliyun.autowonder.im;

import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import com.aliyun.autowonder.im.dto.UserImIdentityVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserImIdentityControllerTest {

    @AfterEach
    void cleanup() {
        AutoWonderContext.destroy();
    }

    @Test
    void classDoesNotRequireOrgAccessSoPersonalListWorksWithoutCurrentOrg() throws Exception {
        RequireOrgAccess classAccess =
                UserImIdentityController.class.getAnnotation(RequireOrgAccess.class);
        assertNull(classAccess,
                "class-level @RequireOrgAccess must be removed so /profile/settings "
                        + "IM tab remains usable when currentOrgId is null or stale (design §10.1)");
    }

    @Test
    void listReturnsOkWhenCurrentOrgIdIsNull() {
        UserImIdentityService service = mock(UserImIdentityService.class);
        UserImIdentityController controller = new UserImIdentityController(service);
        AutoWonderContext.get().setUserId(200L);
        when(service.list(200L)).thenReturn(List.of());

        Result<List<UserImIdentityVO>> result = controller.list();

        assertTrue(result.isSuccess());
        assertNotNull(result.getData());
        verify(service).list(200L);
    }

    @Test
    void testEndpointUsesCurrentUserAndRequiresReadWriteAccess() throws Exception {
        UserImIdentityService service = mock(UserImIdentityService.class);
        UserImIdentityController controller = new UserImIdentityController(service);
        AutoWonderContext.get().setUserId(200L);
        AutoWonderContext.get().setCurrentOrgId(1000L);

        Result<Void> result = controller.testDingTalk();

        assertTrue(result.isSuccess());
        assertNull(result.getData());
        verify(service).sendTest(200L, "DINGTALK");
        Method method = UserImIdentityController.class.getMethod("testDingTalk");
        assertArrayEquals(new String[]{"/dingtalk/test"},
                method.getAnnotation(PostMapping.class).value());
        RequireOrgAccess methodAccess = method.getAnnotation(RequireOrgAccess.class);
        assertNotNull(methodAccess,
                "write operations must still require org membership so non-members cannot "
                        + "configure IM identities");
        assertEquals(OrgAccessLevel.READ_WRITE, methodAccess.value());
        assertEquals("测试个人钉钉身份", methodAccess.action());
    }

    @Test
    void updateDingTalkStillRequiresReadWriteAccess() throws Exception {
        Method method = UserImIdentityController.class.getMethod(
                "updateDingTalk", com.aliyun.autowonder.im.dto.UpdateUserImIdentityRequest.class);
        assertArrayEquals(new String[]{"/dingtalk"},
                method.getAnnotation(PutMapping.class).value());
        RequireOrgAccess methodAccess = method.getAnnotation(RequireOrgAccess.class);
        assertNotNull(methodAccess);
        assertEquals(OrgAccessLevel.READ_WRITE, methodAccess.value());
        assertEquals("配置个人钉钉身份", methodAccess.action());
    }
}
