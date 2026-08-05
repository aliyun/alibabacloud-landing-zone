package com.aliyun.autowonder.im;

import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.OrgAccessAspect;
import com.aliyun.autowonder.access.RequireOrgAccess;
import com.aliyun.autowonder.im.dto.UpdateUserImIdentityRequest;
import com.aliyun.autowonder.im.dto.UserImIdentityVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.List;

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
    void updateDingTalkUsesCurrentUserWithoutOrganizationDependency() {
        UserImIdentityService service = mock(UserImIdentityService.class);
        UserImIdentityController controller = proxiedController(service);
        AutoWonderContext.get().setUserId(200L);
        AutoWonderContext.get().setCurrentOrgId(null);
        UpdateUserImIdentityRequest request = new UpdateUserImIdentityRequest();
        request.setExternalUserId("220791");
        UserImIdentityVO saved = new UserImIdentityVO();
        saved.setProvider("DINGTALK");
        saved.setExternalUserId("220791");
        when(service.update(200L, "DINGTALK", "220791")).thenReturn(saved);

        Result<UserImIdentityVO> result = controller.updateDingTalk(request);

        assertTrue(result.isSuccess());
        assertEquals("220791", result.getData().getExternalUserId());
        verify(service).update(200L, "DINGTALK", "220791");
    }

    @Test
    void testEndpointUsesCurrentUserWithoutOrganizationDependency() {
        UserImIdentityService service = mock(UserImIdentityService.class);
        UserImIdentityController controller = proxiedController(service);
        AutoWonderContext.get().setUserId(200L);
        AutoWonderContext.get().setCurrentOrgId(null);

        Result<Void> result = controller.testDingTalk();

        assertTrue(result.isSuccess());
        assertNull(result.getData());
        verify(service).sendTest(200L, "DINGTALK");
    }

    private UserImIdentityController proxiedController(UserImIdentityService service) {
        AspectJProxyFactory factory =
                new AspectJProxyFactory(new UserImIdentityController(service));
        factory.addAspect(new OrgAccessAspect());
        return factory.getProxy();
    }
}
