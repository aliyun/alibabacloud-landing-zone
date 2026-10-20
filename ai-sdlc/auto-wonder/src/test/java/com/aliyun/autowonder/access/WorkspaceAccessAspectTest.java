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

class WorkspaceAccessAspectTest {

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
        org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
    }

    @Test
    void readOnlyDomainBlocksWritesAndRendersBothLanguages() {
        var service = org.mockito.Mockito.mock(com.aliyun.autowonder.sdlc.SdlcService.class);
        var controller = proxy(new com.aliyun.autowonder.sdlc.SdlcController(service));
        setContext(7L, 100L, WorkspaceAccessLevel.READ_WRITE);
        setContextMemberReadOnlyDomains("[\"SDLC\"]");

        var error = assertThrows(WorkspaceDomainReadOnlyException.class,
                () -> controller.create(new com.aliyun.autowonder.sdlc.dto.CreateSdlcRequest()));

        assertEquals(WorkspaceDomain.SDLC, error.getDomain());
        assertEquals("创建SDLC流程", error.getAction());
        assertEquals("该功能为只读，无法创建SDLC流程", error.getMessage());
        assertEquals(error.getMessage(), error.getLocalizedMessage());
        org.mockito.Mockito.verifyNoInteractions(service);

        var handler = new com.aliyun.autowonder.common.web.GlobalExceptionHandler();
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.CHINA);
        assertEquals("当前角色对该功能只有只读权限，无法创建SDLC流程。",
                handler.handleWorkspaceDomainReadOnly(error).getBody().getMessage());
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
        var response = handler.handleWorkspaceDomainReadOnly(error);
        assertEquals(403, response.getStatusCodeValue());
        assertEquals("12008", response.getBody().getCode());
        assertEquals("Your role has read-only access to this feature and cannot create an SDLC workflow.",
                response.getBody().getMessage());
    }

    @Test
    void forbiddenDomainLeavesOtherDomainsAndNullMemberUnaffected() {
        var service = org.mockito.Mockito.mock(com.aliyun.autowonder.sdlc.SdlcService.class);
        var controller = proxy(new com.aliyun.autowonder.sdlc.SdlcController(service));
        setContext(7L, 100L, WorkspaceAccessLevel.ADMIN);
        setContextMemberReadOnlyDomains("[\"WORKITEM\"]");

        controller.create(new com.aliyun.autowonder.sdlc.dto.CreateSdlcRequest());
        org.mockito.Mockito.verify(service).create(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(100L),
                org.mockito.ArgumentMatchers.eq(7L));

        // 无成员行的请求（如平台管理员跨空间访问）不受功能域限制。
        AutoWonderContext.get().setWorkspaceMember(null);
        var second = org.mockito.Mockito.mock(com.aliyun.autowonder.sdlc.SdlcService.class);
        var secondController = proxy(new com.aliyun.autowonder.sdlc.SdlcController(second));
        setContext(7L, 100L, WorkspaceAccessLevel.ADMIN);
        secondController.create(new com.aliyun.autowonder.sdlc.dto.CreateSdlcRequest());
        org.mockito.Mockito.verify(second).create(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(100L),
                org.mockito.ArgumentMatchers.eq(7L));
    }

    @Test
    void executorOperationalWritesAreGovernedByReadOnlyDomainWhileCredentialsStayAdmin() {
        var executorService = org.mockito.Mockito.mock(
                com.aliyun.autowonder.executor.ExecutorService.class);
        var controller = proxy(new com.aliyun.autowonder.executor.ExecutorController(
                executorService, null, null, null));
        var request = new com.aliyun.autowonder.executor.dto.CreateExecutorRequest();
        setContext(7L, 100L, WorkspaceAccessLevel.READ_WRITE);
        setContextMemberReadOnlyDomains("[\"EXECUTOR\"]");

        var error = assertThrows(WorkspaceDomainReadOnlyException.class,
                () -> controller.create(10L, request));
        assertEquals(WorkspaceDomain.EXECUTOR, error.getDomain());
        assertEquals("创建执行器", error.getAction());
        org.mockito.Mockito.verifyNoInteractions(executorService);

        // 同一读写成员在未勾选 EXECUTOR 只读时可按角色权限创建执行器。
        setContextMemberReadOnlyDomains("[]");
        var issued = new com.aliyun.autowonder.executor.dto.IssuedExecutorVO();
        issued.setId(7L);
        issued.setToken("exec_secret_token");
        org.mockito.Mockito.when(executorService.create(
                org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.same(request),
                org.mockito.ArgumentMatchers.eq(100L), org.mockito.ArgumentMatchers.eq(7L)))
                .thenReturn(issued);
        var created = controller.create(10L, request).getData();
        // 读写成员创建成功但不返回明文令牌。
        assertEquals(7L, created.getId());
        org.junit.jupiter.api.Assertions.assertNull(created.getToken());
        org.mockito.Mockito.verify(executorService).create(
                org.mockito.ArgumentMatchers.eq(10L), org.mockito.ArgumentMatchers.same(request),
                org.mockito.ArgumentMatchers.eq(100L), org.mockito.ArgumentMatchers.eq(7L));

        // 令牌与启动命令携带凭证语义，仍收在管理员边界。
        assertThrows(WorkspaceAccessDeniedException.class, () -> controller.getToken(1L));
        assertThrows(WorkspaceAccessDeniedException.class,
                () -> controller.buildLaunchCommand(1L,
                        new com.aliyun.autowonder.executor.dto.ExecutorLaunchCommandRequest()));
    }

    private void setContextMemberReadOnlyDomains(String readOnlyDomains) {
        var member = new com.aliyun.autowonder.workspace.WorkspaceMemberDO();
        member.setReadOnlyDomains(readOnlyDomains);
        AutoWonderContext.get().setWorkspaceMember(member);
    }

    @Test
    void allWorkspaceReadsRemainAvailableAndAdministratorIgnoresReadOnlyDomains() {
        var workspaceService = org.mockito.Mockito.mock(com.aliyun.autowonder.workspace.WorkspaceService.class);
        var controller = proxy(new com.aliyun.autowonder.workspace.WorkspaceController(workspaceService, null));
        setContext(7L, 100L, WorkspaceAccessLevel.READ_WRITE);
        setContextMemberReadOnlyDomains("[\"WORKSPACE\"]");
        controller.currentMembership();
        controller.listMembers();
        org.mockito.Mockito.verify(workspaceService).currentMembership(100L, 7L);
        org.mockito.Mockito.verify(workspaceService).listMembers(100L);
        assertThrows(WorkspaceAccessDeniedException.class, () -> controller.removeMember(8L));

        setContext(7L, 100L, WorkspaceAccessLevel.ADMIN);
        setContextMemberReadOnlyDomains("[\"WORKSPACE\"]");
        controller.removeMember(8L);
        org.mockito.Mockito.verify(workspaceService).removeMember(100L, 8L, 7L);
    }

    @Test
    void roleDefinitionsAreReadableButOnlyAdministratorCanChangeThem() {
        var roles = org.mockito.Mockito.mock(com.aliyun.autowonder.workspace.WorkspaceRoleService.class);
        var controller = proxy(new com.aliyun.autowonder.workspace.WorkspaceRoleController(roles));
        setContext(7L, 100L, WorkspaceAccessLevel.READ_ONLY);
        setContextMemberReadOnlyDomains("[\"WORKSPACE\"]");
        controller.list();
        org.mockito.Mockito.verify(roles).list(100L);
        assertThrows(WorkspaceAccessDeniedException.class, () -> controller.create(null));
        assertThrows(WorkspaceAccessDeniedException.class, () -> controller.update(12L, null));
        assertThrows(WorkspaceAccessDeniedException.class, () -> controller.delete(12L, 0));
        setContext(7L, 100L, WorkspaceAccessLevel.ADMIN);
        controller.create(null);
        org.mockito.Mockito.verify(roles).create(100L, null, 7L);
    }

    @Test
    void class_annotation_is_default_and_allows_one_invocation() {
        ClassDefaultService target = new ClassDefaultService();
        ClassDefaultService proxy = proxy(target);
        setContext(7L, 100L, WorkspaceAccessLevel.READ_WRITE);

        assertEquals("read", proxy.read());
        assertEquals(1, target.readCalls);
    }

    @Test
    void concrete_method_annotation_wins_over_class_annotation() {
        ClassDefaultService target = new ClassDefaultService();
        ClassDefaultService proxy = proxy(target);
        setContext(7L, 100L, WorkspaceAccessLevel.READ_WRITE);

        WorkspaceAccessDeniedException ex = assertThrows(
                WorkspaceAccessDeniedException.class, proxy::administer);

        assertEquals(WorkspaceAccessLevel.READ_WRITE, ex.getCurrent());
        assertEquals(WorkspaceAccessLevel.ADMIN, ex.getRequired());
        assertEquals("管理工作空间", ex.getAction());
        assertEquals("", ex.getActionKey());
        assertEquals(0, target.adminCalls);
    }

    @Test
    void method_annotation_intercepts_without_class_annotation() {
        MethodOnlyService target = new MethodOnlyService();
        MethodOnlyService proxy = proxy(target);
        setContext(7L, 100L, WorkspaceAccessLevel.READ_ONLY);

        WorkspaceAccessDeniedException ex = assertThrows(WorkspaceAccessDeniedException.class, proxy::write);

        assertEquals(WorkspaceAccessLevel.READ_WRITE, ex.getRequired());
        assertEquals(0, target.calls);
    }

    @Test
    void missing_user_is_unauthorized_without_null_pointer() {
        ClassDefaultService proxy = proxy(new ClassDefaultService());

        BizException ex = assertThrows(BizException.class, proxy::read);

        assertEquals("10401", ex.getCode());
    }

    @Test
    void missing_workspace_is_not_member_without_null_pointer() {
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
        setContext(7L, 100L, WorkspaceAccessLevel.READ_WRITE);

        WorkspaceAccessDeniedException ex = assertThrows(
                WorkspaceAccessDeniedException.class, proxy::administer);

        assertEquals(WorkspaceAccessLevel.READ_WRITE, ex.getCurrent());
        assertEquals(WorkspaceAccessLevel.ADMIN, ex.getRequired());
        assertEquals("管理接口工作空间", ex.getAction());
        assertEquals("", ex.getActionKey());
        assertEquals(0, target.adminCalls);
    }


    @Test
    void actualMethodActionIsEnglishAt403BoundaryWithoutChangingRawPayloadOrInvocation() {
        var service = org.mockito.Mockito.mock(com.aliyun.autowonder.repo.RepoService.class);
        var controller = proxy(new com.aliyun.autowonder.repo.RepoController(service));
        setContext(7L, 100L, WorkspaceAccessLevel.READ_ONLY);
        var error = assertThrows(WorkspaceAccessDeniedException.class, () -> controller.delete(9007199254740993L));
        assertEquals("删除代码仓库", error.getAction());
        assertEquals("工作空间访问级别不足，无法删除代码仓库", error.getMessage());
        assertEquals(error.getMessage(), error.getLocalizedMessage());
        org.mockito.Mockito.verifyNoInteractions(service);
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.CHINA);
        assertEquals("当前角色权限不足，无法删除代码仓库", new com.aliyun.autowonder.common.web.GlobalExceptionHandler().handleWorkspaceAccessDenied(error).getBody().getMessage());
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
        var response = new com.aliyun.autowonder.common.web.GlobalExceptionHandler().handleWorkspaceAccessDenied(error);
        assertEquals(403, response.getStatusCodeValue());
        assertEquals("12008", response.getBody().getCode());
        assertEquals("删除代码仓库", response.getBody().getData().getAction());
        assertEquals("Your role does not have permission to delete a code repository", response.getBody().getMessage());
    }

    @Test
    void actualClassActionIsEnglishAndKeepsClassPolicy() {
        var controller = proxy(new com.aliyun.autowonder.integration.AoneIntegrationController(null, null));
        setContext(7L, 100L, WorkspaceAccessLevel.READ_WRITE);
        var error = assertThrows(WorkspaceAccessDeniedException.class, () -> controller.dispatchNow(20));
        assertEquals(WorkspaceAccessLevel.ADMIN, error.getRequired());
        assertEquals("管理Aone集成", error.getAction());
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
        assertEquals("Your role does not have permission to manage Aone integration",
                new com.aliyun.autowonder.common.web.GlobalExceptionHandler().handleWorkspaceAccessDenied(error).getBody().getMessage());
    }

    @Test
    void actualBackupDenialUsesWorkspaceTerminologyWithoutChangingRawContract() {
        var service = org.mockito.Mockito.mock(com.aliyun.autowonder.backup.ProjectBackupService.class);
        var controller = proxy(new com.aliyun.autowonder.backup.ProjectBackupController(service));
        setContext(7L, 100L, WorkspaceAccessLevel.READ_WRITE);
        var error = assertThrows(WorkspaceAccessDeniedException.class, () -> controller.list(1, 20));
        org.mockito.Mockito.verifyNoInteractions(service);
        assertEquals("access.action.manage_project_configuration_backups", error.getActionKey());
        assertWorkspaceActionBoundary(error, WorkspaceAccessLevel.READ_WRITE, WorkspaceAccessLevel.ADMIN,
                "管理项目配置备份", "manage Workspace configuration backups");
    }

    @Test
    void actualMemberDeliveryActionIdentityUsesWorkspaceTerminologyAtDeniedBoundary() {
        var annotation = com.aliyun.autowonder.insights.MemberDeliveryController.class
                .getAnnotation(RequireWorkspaceAccess.class);
        assertEquals(WorkspaceAccessLevel.READ_ONLY, annotation.value());
        assertEquals("access.action.view_project_member_delivery_statistics", annotation.actionKey());
        // READ_ONLY is the minimum level; this exercises its actual identity at the response boundary,
        // rather than claiming a valid member can fail the real controller's level guard.
        var error = new WorkspaceAccessDeniedException(WorkspaceAccessLevel.READ_ONLY,
                annotation.value(), annotation.action(), annotation.actionKey());
        assertWorkspaceActionBoundary(error, WorkspaceAccessLevel.READ_ONLY, WorkspaceAccessLevel.READ_ONLY,
                "查看项目成员交付统计", "view Workspace member delivery statistics");
    }

    private void assertWorkspaceActionBoundary(WorkspaceAccessDeniedException error,
            WorkspaceAccessLevel current, WorkspaceAccessLevel required, String rawAction, String englishAction) {
        String diagnostic = "工作空间访问级别不足，无法" + rawAction;
        assertEquals(rawAction, error.getAction());
        assertEquals(current, error.getCurrent());
        assertEquals(required, error.getRequired());
        assertEquals(diagnostic, error.getMessage());
        assertEquals(diagnostic, error.getLocalizedMessage());
        org.junit.jupiter.api.Assertions.assertNull(error.getCause());
        var handler = new com.aliyun.autowonder.common.web.GlobalExceptionHandler();
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.CHINA);
        assertEquals("当前角色权限不足，无法" + rawAction, handler.handleWorkspaceAccessDenied(error).getBody().getMessage());
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
        var response = handler.handleWorkspaceAccessDenied(error);
        assertEquals(403, response.getStatusCodeValue());
        assertEquals("12008", response.getBody().getCode());
        assertEquals(rawAction, response.getBody().getData().getAction());
        assertEquals(current, response.getBody().getData().getCurrent());
        assertEquals(required, response.getBody().getData().getRequired());
        assertEquals("Your role does not have permission to " + englishAction, response.getBody().getMessage());
    }

    @Test
    void unkeyedCustomAndUnknownKeysKeepRawActionsEvenWhenTheyLookOwned() {
        setContext(7L, 100L, WorkspaceAccessLevel.READ_ONLY);
        var controller = proxy(new MethodOnlyService());
        var error = assertThrows(WorkspaceAccessDeniedException.class, controller::write);
        assertEquals("", error.getActionKey());
        var handler = new com.aliyun.autowonder.common.web.GlobalExceptionHandler();
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
        for (String key : new String[]{null, "", "future.unknown"}) {
            for (String action : new String[]{"删除代码仓库", "删除代码仓库 <b>raw</b>"}) {
                var raw = new WorkspaceAccessDeniedException(WorkspaceAccessLevel.READ_ONLY, WorkspaceAccessLevel.ADMIN, action, key);
                assertEquals("Your role does not have permission to " + action, handler.handleWorkspaceAccessDenied(raw).getBody().getMessage());
                assertEquals(action, handler.handleWorkspaceAccessDenied(raw).getBody().getData().getAction());
                assertEquals("工作空间访问级别不足，无法" + action, raw.getMessage());
            }
        }
    }

    @Test
    void metadataFollowsAllFourJdkResolutionLevels() {
        setContext(7L, 100L, WorkspaceAccessLevel.READ_ONLY);
        var target = new MetadataControllerImpl();
        var proxy = jdkMetadataProxy(target);
        assertEquals("access.action.view_work_items", assertThrows(WorkspaceAccessDeniedException.class, proxy::type).getActionKey());
        assertEquals("access.action.reorder_sdlc_steps", assertThrows(WorkspaceAccessDeniedException.class, proxy::interfaceMethod).getActionKey());
        var concrete = assertThrows(WorkspaceAccessDeniedException.class, proxy::concreteMethod);
        assertEquals("access.action.delete_a_work_item", concrete.getActionKey());
        assertEquals("删除工作项", concrete.getAction());
        assertEquals(0, target.calls);
        var typeOnly = jdkMetadataProxy(new MetadataController() {
            public String type() { throw new AssertionError("must not invoke"); }
            public String interfaceMethod() { throw new AssertionError("must not invoke"); }
            public String concreteMethod() { throw new AssertionError("must not invoke"); }
        });
        assertEquals("access.action.manage_system_settings", assertThrows(WorkspaceAccessDeniedException.class, typeOnly::type).getActionKey());
        var interfaceOnly = jdkProxy(new JdkAccessControllerImpl());
        setContext(7L, 100L, WorkspaceAccessLevel.READ_WRITE);
        assertEquals("", assertThrows(WorkspaceAccessDeniedException.class, interfaceOnly::administer).getActionKey());
    }

    private MetadataController jdkMetadataProxy(MetadataController target) {
        var aspect = new WorkspaceAccessAspect();
        var advisors = new ArrayList<>(new ReflectiveAspectJAdvisorFactory().getAdvisors(
                new SingletonMetadataAwareAspectInstanceFactory(aspect, "metadataAspect")));
        AspectJProxyUtils.makeAdvisorChainAspectJCapableIfNecessary(advisors);
        ProxyFactory factory = new ProxyFactory(); factory.setTarget(target); factory.setInterfaces(MetadataController.class);
        factory.setProxyTargetClass(false); advisors.forEach(factory::addAdvisor);
        var proxy = (MetadataController) factory.getProxy(); assertTrue(AopUtils.isJdkDynamicProxy(proxy)); return proxy;
    }

    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "管理系统设置", actionKey = "access.action.manage_system_settings")
    interface MetadataController {
        String type();
        @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "重排SDLC步骤", actionKey = "access.action.reorder_sdlc_steps")
        String interfaceMethod();
        @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "重排SDLC步骤", actionKey = "access.action.reorder_sdlc_steps")
        String concreteMethod();
    }

    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "查看工作项", actionKey = "access.action.view_work_items")
    static class MetadataControllerImpl implements MetadataController {
        int calls;
        public String type() { calls++; return "type"; }
        public String interfaceMethod() { calls++; return "interface"; }
        @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "删除工作项", actionKey = "access.action.delete_a_work_item")
        public String concreteMethod() { calls++; return "concrete"; }
    }

    @SuppressWarnings("unchecked")
    private <T> T proxy(T target) {
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAspect(new WorkspaceAccessAspect());
        return (T) factory.getProxy();
    }

    private JdkAccessController jdkProxy(JdkAccessController target) {
        WorkspaceAccessAspect aspect = new WorkspaceAccessAspect();
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

    private void setContext(Long userId, Long workspaceId, WorkspaceAccessLevel level) {
        AutoWonderContext.get().setUserId(userId);
        AutoWonderContext.get().setCurrentWorkspaceId(workspaceId);
        AutoWonderContext.get().setWorkspaceAccessLevel(level);
    }

    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, actionKey = "access.action.workspace_resources")
    static class ClassDefaultService {
        private int readCalls;
        private int adminCalls;

        public String read() {
            readCalls++;
            return "read";
        }

        @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "管理工作空间")
        public String administer() {
            adminCalls++;
            return "admin";
        }
    }

    static class MethodOnlyService {
        private int calls;

        @RequireWorkspaceAccess(WorkspaceAccessLevel.READ_WRITE)
        public String write() {
            calls++;
            return "write";
        }
    }

    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "读取接口工作空间")
    interface JdkAccessController {
        String read();

        @RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "管理接口工作空间")
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
