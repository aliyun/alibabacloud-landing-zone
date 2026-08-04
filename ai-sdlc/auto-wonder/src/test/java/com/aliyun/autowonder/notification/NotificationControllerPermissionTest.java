package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.access.RequireOrgAccess;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMapping;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

class NotificationControllerPermissionTest {

    @Test
    void personalNotificationEndpointsStayOutsideOrganizationAccessLadder() {
        for (Method method : mappedMethods()) {
            assertFalse(AnnotatedElementUtils.hasAnnotation(method, RequireOrgAccess.class),
                    method.getName() + " should not require organization access");
        }
    }

    private Method[] mappedMethods() {
        return Arrays.stream(NotificationController.class.getDeclaredMethods())
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .toArray(Method[]::new);
    }
}
