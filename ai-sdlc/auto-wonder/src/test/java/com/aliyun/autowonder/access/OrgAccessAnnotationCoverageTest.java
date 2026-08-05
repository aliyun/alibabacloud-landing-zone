package com.aliyun.autowonder.access;

import com.aliyun.autowonder.aiusage.AiUsageController;
import com.aliyun.autowonder.artifact.ArtifactController;
import com.aliyun.autowonder.branding.PlatformBrandingController;
import com.aliyun.autowonder.executor.ExecutorController;
import com.aliyun.autowonder.integration.AoneIntegrationController;
import com.aliyun.autowonder.integration.dingtalk.DingTalkBindingController;
import com.aliyun.autowonder.im.PlatformImChannelConfigController;
import com.aliyun.autowonder.mcp.McpTokenController;
import com.aliyun.autowonder.setting.SystemSettingController;
import com.aliyun.autowonder.workitem.WorkitemController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrgAccessAnnotationCoverageTest {

    private static final String BASE_PACKAGE = "com.aliyun.autowonder";
    private static final String DEFAULT_ACTION = defaultAction();

    private static final Set<String> EXEMPT_CONTROLLERS = Set.of(
            "com.aliyun.autowonder.aiusage.DaemonTaskUsageController",
            "com.aliyun.autowonder.artifact.DaemonArtifactController",
            "com.aliyun.autowonder.controller.HealthCheckController",
            "com.aliyun.autowonder.controller.HelloWorldController",
            "com.aliyun.autowonder.dispatch.DaemonCheckpointController",
            "com.aliyun.autowonder.dispatch.DaemonRecoveryClaimController",
            "com.aliyun.autowonder.integration.dingtalk.HttpCallbackTransport",
            "com.aliyun.autowonder.mcp.McpController",
            "com.aliyun.autowonder.notification.NotificationController",
            "com.aliyun.autowonder.taskpackage.DaemonTaskPackageController",
            "com.aliyun.autowonder.user.AuthController",
            "com.aliyun.autowonder.workitem.DaemonCommentController"
    );

    private static final Set<String> EXEMPT_METHODS = Set.of(
            "com.aliyun.autowonder.branding.PlatformBrandingController#logo()",
            "com.aliyun.autowonder.branding.PlatformBrandingController#adminConfig()",
            "com.aliyun.autowonder.branding.PlatformBrandingController#publicConfig()",
            "com.aliyun.autowonder.branding.PlatformBrandingController#update("
                    + "com.aliyun.autowonder.branding.dto.UpdatePlatformBrandingRequest)",
            "com.aliyun.autowonder.branding.PlatformBrandingController#uploadLogo("
                    + "org.springframework.web.multipart.MultipartFile)",
            "com.aliyun.autowonder.im.PlatformImChannelConfigController#list()",
            "com.aliyun.autowonder.im.PlatformImChannelConfigController#updateDingTalk("
                    + "com.aliyun.autowonder.im.dto.UpdateDingTalkChannelRequest)",
            "com.aliyun.autowonder.integration.IntegrationCapabilityController#capabilities()",
            "com.aliyun.autowonder.mcp.McpTokenController#issue("
                    + "com.aliyun.autowonder.mcp.dto.CreateMcpTokenRequest)",
            "com.aliyun.autowonder.mcp.McpTokenController#list()",
            "com.aliyun.autowonder.mcp.McpTokenController#revoke(java.lang.Long)",
            "com.aliyun.autowonder.mcp.McpTokenController#tools()",
            "com.aliyun.autowonder.mcp.McpTokenController#platformSkills()",
            "com.aliyun.autowonder.org.OrgController#create("
                    + "com.aliyun.autowonder.org.dto.CreateOrgRequest)",
            "com.aliyun.autowonder.org.OrgController#mine()",
            "com.aliyun.autowonder.org.OrgController#switchOrg(java.lang.Long)",
            "com.aliyun.autowonder.im.UserImIdentityController#list()",
            "com.aliyun.autowonder.im.UserImIdentityController#updateDingTalk("
                    + "com.aliyun.autowonder.im.dto.UpdateUserImIdentityRequest)",
            "com.aliyun.autowonder.im.UserImIdentityController#testDingTalk()"
    );

    @Test
    void everyOrganizationEndpointResolvesAnExplicitAccessLevelAndAction() {
        List<String> unclassified = new ArrayList<>();
        List<String> invalidActions = new ArrayList<>();

        for (Class<?> controller : restControllers()) {
            if (isControllerExemptFromOrganizationAccess(controller)) {
                continue;
            }
            RequireOrgAccess classAccess =
                    AnnotatedElementUtils.findMergedAnnotation(controller, RequireOrgAccess.class);
            for (Method method : requestMappedMethods(controller)) {
                if (EXEMPT_METHODS.contains(methodName(method))) {
                    continue;
                }
                RequireOrgAccess methodAccess =
                        AnnotatedElementUtils.findMergedAnnotation(method, RequireOrgAccess.class);
                RequireOrgAccess resolvedAccess = methodAccess != null ? methodAccess : classAccess;
                if (resolvedAccess == null) {
                    unclassified.add(methodName(method));
                } else if (resolvedAccess.action().isBlank()
                        || DEFAULT_ACTION.equals(resolvedAccess.action())) {
                    invalidActions.add(methodName(method));
                }
            }
        }

        assertTrue(unclassified.isEmpty(),
                () -> "Organization endpoints without @RequireOrgAccess:\n"
                        + String.join("\n", unclassified));
        assertTrue(invalidActions.isEmpty(),
                () -> "Organization endpoints without an explicit access action:\n"
                        + String.join("\n", invalidActions));
    }

    @Test
    void specialAccessLevelMatrixIsExplicitlyClassified() {
        assertAccess(ExecutorController.class, "create", OrgAccessLevel.ADMIN);
        assertAccess(ExecutorController.class, "list", OrgAccessLevel.READ_ONLY);
        assertAccess(ExecutorController.class, "listAll", OrgAccessLevel.READ_ONLY);
        assertAccess(ExecutorController.class, "getToken", OrgAccessLevel.ADMIN);
        assertAccess(ExecutorController.class, "delete", OrgAccessLevel.ADMIN);

        assertAccess(SystemSettingController.class, "listByGroup", OrgAccessLevel.ADMIN);
        assertAccess(SystemSettingController.class, "updateGroup", OrgAccessLevel.ADMIN);

        assertAccess(AoneIntegrationController.class, "testConnection", OrgAccessLevel.ADMIN);
        assertAccess(AoneIntegrationController.class, "createBinding", OrgAccessLevel.ADMIN);
        assertAccess(AoneIntegrationController.class, "listBindings", OrgAccessLevel.ADMIN);
        assertAccess(AoneIntegrationController.class, "searchProjects", OrgAccessLevel.ADMIN);
        assertAccess(AoneIntegrationController.class, "listMembers", OrgAccessLevel.ADMIN);
        assertAccess(AoneIntegrationController.class, "syncNow", OrgAccessLevel.ADMIN);
        assertAccess(AoneIntegrationController.class, "dispatchNow", OrgAccessLevel.ADMIN);

        assertAccess(DingTalkBindingController.class, "list", OrgAccessLevel.ADMIN);
        assertAccess(DingTalkBindingController.class, "get", OrgAccessLevel.ADMIN);
        assertAccess(DingTalkBindingController.class, "create", OrgAccessLevel.ADMIN);
        assertAccess(DingTalkBindingController.class, "update", OrgAccessLevel.ADMIN);
        assertAccess(DingTalkBindingController.class, "delete", OrgAccessLevel.ADMIN);

        assertAccess(AiUsageController.class, "listUsage", OrgAccessLevel.READ_ONLY);
        assertAccess(AiUsageController.class, "getQuota", OrgAccessLevel.ADMIN);
        assertAccess(AiUsageController.class, "updateQuota", OrgAccessLevel.ADMIN);

        assertAccess(ArtifactController.class, "listByWorkitem", OrgAccessLevel.READ_ONLY);
        assertAccess(ArtifactController.class, "listRequirementDocuments", OrgAccessLevel.READ_ONLY);
        assertAccess(ArtifactController.class, "download", OrgAccessLevel.READ_ONLY);
        assertAccess(ArtifactController.class, "preview", OrgAccessLevel.READ_ONLY);
        assertAccess(ArtifactController.class, "uploadRequirementDocuments", OrgAccessLevel.READ_WRITE);
        assertAccess(ArtifactController.class, "deleteRequirementDocument", OrgAccessLevel.READ_WRITE);

        assertAccess(WorkitemController.class, "create", OrgAccessLevel.READ_WRITE);
        assertAccess(WorkitemController.class, "transition", OrgAccessLevel.READ_WRITE);
        assertAccess(WorkitemController.class, "assign", OrgAccessLevel.READ_WRITE);
        assertAccess(WorkitemController.class, "updateContent", OrgAccessLevel.READ_WRITE);
        assertAccess(WorkitemController.class, "delete", OrgAccessLevel.READ_WRITE);
        assertAccess(WorkitemController.class, "addComment", OrgAccessLevel.READ_WRITE);

    }

    @Test
    void allowlistedChannelsStayOutsideOrganizationAccessLadder() {
        List<String> annotatedExemptions = new ArrayList<>();

        for (Class<?> controller : restControllers()) {
            RequireOrgAccess classAccess =
                    AnnotatedElementUtils.findMergedAnnotation(controller, RequireOrgAccess.class);
            if (EXEMPT_CONTROLLERS.contains(controller.getName()) && classAccess != null) {
                annotatedExemptions.add(controller.getName());
            }
            for (Method method : requestMappedMethods(controller)) {
                boolean exempt = EXEMPT_CONTROLLERS.contains(controller.getName())
                        || EXEMPT_METHODS.contains(methodName(method));
                RequireOrgAccess methodAccess =
                        AnnotatedElementUtils.findMergedAnnotation(method, RequireOrgAccess.class);
                if (exempt && (classAccess != null || methodAccess != null)) {
                    annotatedExemptions.add(methodName(method));
                }
            }
        }

        assertTrue(annotatedExemptions.isEmpty(),
                () -> "Allowlisted endpoints unexpectedly using @RequireOrgAccess:\n"
                        + String.join("\n", annotatedExemptions));
    }

    @Test
    void globalPlatformManagementEndpointsStayOutsideOrganizationAccessLadder() {
        assertExempt(PlatformBrandingController.class, "adminConfig");
        assertExempt(PlatformBrandingController.class, "update");
        assertExempt(PlatformBrandingController.class, "uploadLogo");
        assertExempt(PlatformImChannelConfigController.class, "list");
        assertExempt(PlatformImChannelConfigController.class, "updateDingTalk");
    }

    private boolean isControllerExemptFromOrganizationAccess(Class<?> controller) {
        return EXEMPT_CONTROLLERS.contains(controller.getName());
    }

    private static String defaultAction() {
        try {
            return (String) RequireOrgAccess.class
                    .getDeclaredMethod("action")
                    .getDefaultValue();
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Could not inspect @RequireOrgAccess action default", e);
        }
    }

    private void assertAccess(
            Class<?> controller, String methodName, OrgAccessLevel expectedLevel) {
        Method method = mappedMethod(controller, methodName);
        RequireOrgAccess methodAccess =
                AnnotatedElementUtils.findMergedAnnotation(method, RequireOrgAccess.class);
        RequireOrgAccess access = methodAccess != null
                ? methodAccess
                : AnnotatedElementUtils.findMergedAnnotation(controller, RequireOrgAccess.class);
        assertNotNull(access, () -> methodName(method) + " should require organization access");
        assertEquals(expectedLevel, access.value(), methodName(method));
    }

    private void assertExempt(Class<?> controller, String methodName) {
        Method method = mappedMethod(controller, methodName);
        assertTrue(EXEMPT_METHODS.contains(methodName(method)),
                () -> methodName(method) + " should be explicitly allowlisted");
        assertTrue(AnnotatedElementUtils.findMergedAnnotation(
                        method, RequireOrgAccess.class) == null,
                () -> methodName(method) + " should stay outside organization access");
    }

    private Method mappedMethod(Class<?> controller, String name) {
        List<Method> matches = requestMappedMethods(controller).stream()
                .filter(method -> method.getName().equals(name))
                .toList();
        assertEquals(1, matches.size(),
                () -> controller.getName() + "#" + name + " should identify one mapped method");
        return matches.get(0);
    }

    private List<Class<?>> restControllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        return scanner.findCandidateComponents(BASE_PACKAGE).stream()
                .map(BeanDefinition::getBeanClassName)
                .map(this::loadClass)
                .sorted(Comparator.comparing(Class::getName))
                .toList();
    }

    private Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new AssertionError("Could not load REST controller " + className, e);
        }
    }

    private List<Method> requestMappedMethods(Class<?> controller) {
        return declaredMethods(controller).stream()
                .filter(method -> AnnotatedElementUtils.hasAnnotation(method, RequestMapping.class))
                .toList();
    }

    private List<Method> declaredMethods(Class<?> controller) {
        return Arrays.stream(controller.getDeclaredMethods())
                .sorted(Comparator.comparing(this::methodName))
                .toList();
    }

    private String methodName(Method method) {
        String parameterTypes = Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .collect(Collectors.joining(", "));
        return method.getDeclaringClass().getName()
                + "#" + method.getName() + "(" + parameterTypes + ")";
    }
}
