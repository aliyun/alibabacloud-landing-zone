package com.aliyun.autowonder.access;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.springframework.aop.Advisor;
import org.springframework.aop.aspectj.AspectJProxyUtils;
import org.springframework.aop.aspectj.annotation.ReflectiveAspectJAdvisorFactory;
import org.springframework.aop.aspectj.annotation.SingletonMetadataAwareAspectInstanceFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrgAccessAspectTest {

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void class_annotation_is_default_and_allows_one_invocation() {
        ClassDefaultService target = new ClassDefaultService();
        ClassDefaultService proxy = proxy(target);
        setContext(7L, 100L, OrgAccessLevel.READ_WRITE);

        assertEquals("read", proxy.read());
        assertEquals(1, target.readCalls);
    }

    @Test
    void concrete_method_annotation_wins_over_class_annotation() {
        ClassDefaultService target = new ClassDefaultService();
        ClassDefaultService proxy = proxy(target);
        setContext(7L, 100L, OrgAccessLevel.READ_WRITE);

        OrgAccessDeniedException ex = assertThrows(
                OrgAccessDeniedException.class, proxy::administer);

        assertEquals(OrgAccessLevel.READ_WRITE, ex.getCurrent());
        assertEquals(OrgAccessLevel.ADMIN, ex.getRequired());
        assertEquals("管理组织", ex.getAction());
        assertEquals(0, target.adminCalls);
    }

    @Test
    void method_annotation_intercepts_without_class_annotation() {
        MethodOnlyService target = new MethodOnlyService();
        MethodOnlyService proxy = proxy(target);
        setContext(7L, 100L, OrgAccessLevel.READ_ONLY);

        OrgAccessDeniedException ex = assertThrows(OrgAccessDeniedException.class, proxy::write);

        assertEquals(OrgAccessLevel.READ_WRITE, ex.getRequired());
        assertEquals(0, target.calls);
    }

    @Test
    void missing_user_is_unauthorized_without_null_pointer() {
        ClassDefaultService proxy = proxy(new ClassDefaultService());

        BizException ex = assertThrows(BizException.class, proxy::read);

        assertEquals("10401", ex.getCode());
    }

    @Test
    void missing_organization_is_not_member_without_null_pointer() {
        ClassDefaultService proxy = proxy(new ClassDefaultService());
        setContext(7L, null, null);

        BizException ex = assertThrows(BizException.class, proxy::read);

        assertEquals("11001", ex.getCode());
    }

    @Test
    void missing_current_level_is_not_member_without_null_pointer() {
        ClassDefaultService proxy = proxy(new ClassDefaultService());
        setContext(7L, 100L, null);

        BizException ex = assertThrows(BizException.class, proxy::read);

        assertEquals("11001", ex.getCode());
    }

    @Test
    void jdk_proxy_enforces_interface_type_annotation() {
        JdkAccessControllerImpl target = new JdkAccessControllerImpl();
        JdkAccessController proxy = jdkProxy(target);

        BizException ex = assertThrows(BizException.class, proxy::read);

        assertEquals("10401", ex.getCode());
        assertEquals(0, target.readCalls);
    }

    @Test
    void jdk_proxy_interface_method_annotation_wins_over_interface_type() {
        JdkAccessControllerImpl target = new JdkAccessControllerImpl();
        JdkAccessController proxy = jdkProxy(target);
        setContext(7L, 100L, OrgAccessLevel.READ_WRITE);

        OrgAccessDeniedException ex = assertThrows(
                OrgAccessDeniedException.class, proxy::administer);

        assertEquals(OrgAccessLevel.READ_WRITE, ex.getCurrent());
        assertEquals(OrgAccessLevel.ADMIN, ex.getRequired());
        assertEquals("管理接口组织", ex.getAction());
        assertEquals(0, target.adminCalls);
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new OrgAccessAspect());
        return (T) factory.getProxy();
    }

    private JdkAccessController jdkProxy(JdkAccessController target) {
        OrgAccessAspect aspect = new OrgAccessAspect();
        ReflectiveAspectJAdvisorFactory advisorFactory = new ReflectiveAspectJAdvisorFactory();
        List<Advisor> advisors = new ArrayList<>(advisorFactory.getAdvisors(
                new SingletonMetadataAwareAspectInstanceFactory(aspect, "orgAccessAspect")));
        AspectJProxyUtils.makeAdvisorChainAspectJCapableIfNecessary(advisors);

        ProxyFactory factory = new ProxyFactory();
        factory.setTarget(target);
        factory.setInterfaces(JdkAccessController.class);
        factory.setProxyTargetClass(false);
        advisors.forEach(factory::addAdvisor);
        JdkAccessController proxy = (JdkAccessController) factory.getProxy();
        assertTrue(AopUtils.isJdkDynamicProxy(proxy));
        return proxy;
    }

    private void setContext(Long userId, Long orgId, OrgAccessLevel level) {
        AutoWonderContext.get().setUserId(userId);
        AutoWonderContext.get().setCurrentOrgId(orgId);
        AutoWonderContext.get().setOrgAccessLevel(level);
    }

    @RequireOrgAccess(OrgAccessLevel.READ_ONLY)
    static class ClassDefaultService {
        private int readCalls;
        private int adminCalls;

        public String read() {
            readCalls++;
            return "read";
        }

        @RequireOrgAccess(value = OrgAccessLevel.ADMIN, action = "管理组织")
        public String administer() {
            adminCalls++;
            return "admin";
        }
    }

    static class MethodOnlyService {
        private int calls;

        @RequireOrgAccess(OrgAccessLevel.READ_WRITE)
        public String write() {
            calls++;
            return "write";
        }
    }

    @RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "读取接口组织")
    interface JdkAccessController {
        String read();

        @RequireOrgAccess(value = OrgAccessLevel.ADMIN, action = "管理接口组织")
        String administer();
    }

    static class JdkAccessControllerImpl implements JdkAccessController {
        private int readCalls;
        private int adminCalls;

        @Override
        public String read() {
            readCalls++;
            return "read";
        }

        @Override
        public String administer() {
            adminCalls++;
            return "admin";
        }
    }
}
