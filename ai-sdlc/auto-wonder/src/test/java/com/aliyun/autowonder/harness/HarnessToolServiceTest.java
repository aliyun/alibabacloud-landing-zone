package com.aliyun.autowonder.harness;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HarnessToolServiceTest {

    private final WorkspaceOnboardingService onboardingService = mock(WorkspaceOnboardingService.class);
    private final HarnessPreviewService previewService = mock(HarnessPreviewService.class);
    private final HarnessInitializationService initializationService = mock(HarnessInitializationService.class);
    private final WorkspaceConfigurationService configurationService = mock(WorkspaceConfigurationService.class);
    private final WorkspaceReadinessService readinessService = mock(WorkspaceReadinessService.class);
    private final HarnessToolService service = new HarnessToolService(new HarnessTemplateCatalog(),
            onboardingService, previewService, initializationService, configurationService, readinessService);

    @Test
    void exposesTemplateCatalogAndBundledSkillFiles() {
        List<Map<String, Object>> templates = service.listTemplates();
        assertEquals(1, templates.size());
        assertEquals("software-delivery", templates.get(0).get("templateId"));
        assertEquals(7, templates.get(0).get("variableCount"));

        Map<String, Object> template = service.getTemplate(Map.of("templateId", "software-delivery"));
        assertEquals(5, template.get("agentCount"));
        assertEquals(8, template.get("skillCount"));
        assertEquals(3L, template.get("sdlcCount"));
        assertTrue(service.onboardingSkillFiles().containsKey("SKILL.md"));
    }

    @Test
    void prepareDelegatesAndConfirmsReadinessWhenCheckIdGiven() {
        WorkspaceOnboardingDO onboarding = onboarding(21L);
        WorkspaceOnboardingService.OnboardingView view = view(onboarding);
        when(onboardingService.prepare(eq(1L), eq(10L), any()))
                .thenReturn(new WorkspaceOnboardingService.PrepareResult(view, true));
        when(readinessService.confirm(eq(1L), eq(10L), eq(21L), eq("check-hash")))
                .thenReturn(Map.of("stage", "READY"));
        when(onboardingService.require(1L, 10L, 21L)).thenReturn(onboarding);
        when(onboardingService.view(any())).thenReturn(view);

        Map<String, Object> result = service.prepare(1L, 10L,
                Map.of("draft", Map.of("confirmCheckId", "check-hash")));
        assertEquals(Boolean.TRUE, result.get("created"));
        assertEquals("READY", ((Map<?, ?>) result.get("readiness")).get("stage"));
        verify(readinessService).confirm(1L, 10L, 21L, "check-hash");

        assertThrows(BizException.class, () -> service.prepare(1L, 10L,
                Map.of("draft", Map.of("confirmCheckId", " "))));
    }

    @Test
    void delegatesEveryHarnessEntryPoint() {
        Map<String, Object> args = Map.of("onboardingId", 21L);
        Map<String, Object> marker = Map.of("ok", true);
        when(onboardingService.get(1L, 10L, args)).thenReturn(marker);
        when(previewService.preview(1L, 10L, args)).thenReturn(marker);
        when(initializationService.initialize(1L, 10L, args)).thenReturn(marker);
        when(initializationService.get(1L, 10L, args)).thenReturn(marker);
        when(initializationService.resume(1L, 10L, args)).thenReturn(marker);
        when(configurationService.preview(1L, 10L, args)).thenReturn(marker);
        when(configurationService.apply(1L, 10L, args)).thenReturn(marker);
        when(configurationService.get(1L, 10L, args)).thenReturn(marker);
        when(configurationService.resume(1L, 10L, args)).thenReturn(marker);
        when(readinessService.check(1L, 10L, args)).thenReturn(marker);

        assertEquals(marker, service.getOnboarding(1L, 10L, args));
        assertEquals(marker, service.previewInitialization(1L, 10L, args));
        assertEquals(marker, service.initialize(1L, 10L, args));
        assertEquals(marker, service.getInitialization(1L, 10L, args));
        assertEquals(marker, service.resumeInitialization(1L, 10L, args));
        assertEquals(marker, service.previewConfiguration(1L, 10L, args));
        assertEquals(marker, service.applyConfiguration(1L, 10L, args));
        assertEquals(marker, service.getConfiguration(1L, 10L, args));
        assertEquals(marker, service.resumeConfiguration(1L, 10L, args));
        assertEquals(marker, service.checkReadiness(1L, 10L, args));
    }

    @Test
    void prepareWithoutCheckIdDoesNotConfirmReadiness() {
        WorkspaceOnboardingDO onboarding = onboarding(21L);
        WorkspaceOnboardingService.OnboardingView view = view(onboarding);
        when(onboardingService.prepare(eq(1L), eq(10L), any()))
                .thenReturn(new WorkspaceOnboardingService.PrepareResult(view, true));
        Map<String, Object> result = service.prepare(1L, 10L, Map.of("draft", Map.of("clientKind", "QODER_CLI")));
        assertEquals(Boolean.TRUE, result.get("created"));
        assertFalse(result.containsKey("readiness"));
    }

    @Test
    void unknownTemplateSurfacesNotFound() {
        BizException missing = assertThrows(BizException.class,
                () -> service.getTemplate(Map.of("templateId", "missing")));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), missing.getCode());
        assertFalse(service.onboardingSkillFiles().isEmpty());
        assertNotNull(service.listTemplates().get(0).get("sha256"));
    }

    private WorkspaceOnboardingService.OnboardingView view(WorkspaceOnboardingDO onboarding) {
        return new WorkspaceOnboardingService.OnboardingView(21L, 1L, 10L, "software-delivery", "1.0.0",
                "sha", WorkspaceOnboardingService.STAGE_DRAFT, false, false, 0, 0, 13, 1, 12, 1,
                Map.of(), List.of(), List.of());
    }

    private WorkspaceOnboardingDO onboarding(long id) {
        WorkspaceOnboardingDO onboarding = new WorkspaceOnboardingDO();
        onboarding.setId(id);
        onboarding.setTenantId(1L);
        onboarding.setWorkspaceId(1L);
        onboarding.setOwnerUserId(10L);
        onboarding.setStage(WorkspaceOnboardingService.STAGE_DRAFT);
        onboarding.setVersion(0);
        onboarding.setConfigVersion(0);
        onboarding.setDraftJson("{}");
        return onboarding;
    }
}
