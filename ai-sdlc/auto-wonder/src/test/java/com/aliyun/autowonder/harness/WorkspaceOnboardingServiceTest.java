package com.aliyun.autowonder.harness;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceOnboardingServiceTest {

    private final InMemoryWorkspaceOnboardingDao dao = new InMemoryWorkspaceOnboardingDao();
    private final HarnessTemplateCatalog catalog = new HarnessTemplateCatalog();
    private final WorkspaceOnboardingService service = new WorkspaceOnboardingService(dao, catalog);

    @Test
    void createsThirteenStableStepsForSevenVariableTemplate() {
        WorkspaceOnboardingService.PrepareResult result = service.prepare(1L, 10L,
                Map.of("requestKey", "first", "draft", Map.of("templateKey", "software-delivery")));
        WorkspaceOnboardingService.OnboardingView view = result.onboarding();
        assertTrue(result.created());
        assertEquals(13, view.totalSteps());
        assertFalse(view.estimated());
        assertEquals(1, view.completedSteps());
        assertEquals(12, view.remainingSteps());
        assertEquals(1, view.currentStepIndex());
        assertEquals(List.of(WorkspaceOnboardingService.STEP_SELECT_TEMPLATE,
                        WorkspaceOnboardingService.STEP_CONFIRM_INITIALIZATION),
                view.steps().subList(0, 2).stream().map(WorkspaceOnboardingService.StepView::stepKey).toList());
        assertEquals(WorkspaceOnboardingService.STATUS_COMPLETED, view.steps().get(0).status());
    }

    @Test
    void repeatedRequestKeyReturnsSameDraft() {
        WorkspaceOnboardingService.PrepareResult first = service.prepare(1L, 10L,
                Map.of("requestKey", "same", "draft", Map.of("templateKey", "software-delivery")));
        WorkspaceOnboardingService.PrepareResult second = service.prepare(1L, 10L,
                Map.of("requestKey", "same", "draft", Map.of("templateKey", "software-delivery")));
        assertFalse(second.created());
        assertEquals(first.onboarding().id(), second.onboarding().id());
        assertEquals(first.onboarding().version(), second.onboarding().version());
    }

    @Test
    void updateRequiresMatchingVersion() {
        WorkspaceOnboardingService.OnboardingView created = service.prepare(1L, 10L,
                Map.of("draft", Map.of("templateKey", "software-delivery"))).onboarding();
        BizException conflict = assertThrows(BizException.class, () -> service.prepare(1L, 10L,
                Map.of("onboardingId", created.id(), "expectedVersion", created.version() + 7,
                        "draft", Map.of("clientKind", "QODER_CLI"))));
        assertEquals(ErrorCode.HARNESS_ONBOARDING_VERSION_CONFLICT.getCode(), conflict.getCode());
        BizException missingVersion = assertThrows(BizException.class, () -> service.prepare(1L, 10L,
                Map.of("onboardingId", created.id(), "draft", Map.of("clientKind", "QODER_CLI"))));
        assertEquals(ErrorCode.MCP_TOOL_ARGUMENT_INVALID.getCode(), missingVersion.getCode());
    }

    @Test
    void variableStepCompletesOnlyWithValueOrExplicitNotApplicable() {
        WorkspaceOnboardingService.OnboardingView created = service.prepare(1L, 10L,
                Map.of("draft", Map.of("templateKey", "software-delivery"))).onboarding();
        String token = catalog.list().get(0).variableTokens().get(0);
        String stepKey = WorkspaceOnboardingService.variableStepKey(token);

        Map<String, Object> blank = new LinkedHashMap<>();
        blank.put(token, Map.of("value", "   "));
        WorkspaceOnboardingService.OnboardingView pending = service.prepare(1L, 10L,
                Map.of("onboardingId", created.id(), "expectedVersion", created.version(),
                        "draft", Map.of("variables", blank))).onboarding();
        assertEquals(WorkspaceOnboardingService.STATUS_PENDING, step(pending, stepKey).status());

        WorkspaceOnboardingService.OnboardingView unresolved = service.prepare(1L, 10L,
                Map.of("onboardingId", created.id(), "expectedVersion", pending.version(),
                        "draft", Map.of("variables",
                                Map.of(token, Map.of("value", "{{still-placeholder}}"))))).onboarding();
        assertEquals(WorkspaceOnboardingService.STATUS_BLOCKED, step(unresolved, stepKey).status());

        WorkspaceOnboardingService.OnboardingView noReason = service.prepare(1L, 10L,
                Map.of("onboardingId", created.id(), "expectedVersion", unresolved.version(),
                        "draft", Map.of("variables", Map.of(token, Map.of("notApplicable", true))))).onboarding();
        assertEquals(WorkspaceOnboardingService.STATUS_BLOCKED, step(noReason, stepKey).status());

        WorkspaceOnboardingService.OnboardingView applicable = service.prepare(1L, 10L,
                Map.of("onboardingId", created.id(), "expectedVersion", noReason.version(),
                        "draft", Map.of("variables",
                                Map.of(token, Map.of("notApplicable", true, "reason", "本项目无覆盖率要求")))))
                .onboarding();
        WorkspaceOnboardingService.StepView step = step(applicable, stepKey);
        assertEquals(WorkspaceOnboardingService.STATUS_NOT_APPLICABLE, step.status());
        assertNotNull(step.reason());
    }

    @Test
    void rejectsDraftFieldsThatCouldForgeCompletion() {
        BizException invalid = assertThrows(BizException.class, () -> service.prepare(1L, 10L,
                Map.of("draft", Map.of("stage", "READY"))));
        assertEquals(ErrorCode.MCP_TOOL_ARGUMENT_INVALID.getCode(), invalid.getCode());
        BizException completed = assertThrows(BizException.class, () -> service.prepare(1L, 10L,
                Map.of("draft", Map.of("confirmCheckId", "1", "completedSteps", 13))));
        assertEquals(ErrorCode.MCP_TOOL_ARGUMENT_INVALID.getCode(), completed.getCode());
    }

    @Test
    void getListsResumableDraftsAndRejectsOtherOwners() {
        WorkspaceOnboardingService.OnboardingView created = service.prepare(1L, 10L,
                Map.of("draft", Map.of("templateKey", "software-delivery"))).onboarding();
        Map<String, Object> listed = service.get(1L, 10L, Map.of());
        assertNotNull(listed.get("onboarding"));
        assertEquals(1, ((List<?>) listed.get("onboardings")).size());

        BizException foreign = assertThrows(BizException.class,
                () -> service.require(1L, 99L, created.id()));
        assertEquals(ErrorCode.HARNESS_ONBOARDING_NOT_FOUND.getCode(), foreign.getCode());
        BizException noOnboarding = assertThrows(BizException.class,
                () -> service.get(2L, 10L, Map.of()));
        assertEquals(ErrorCode.HARNESS_ONBOARDING_NOT_FOUND.getCode(), noOnboarding.getCode());
    }

    @Test
    void pauseFlagRoundTripsThroughDraft() {
        WorkspaceOnboardingService.OnboardingView created = service.prepare(1L, 10L,
                Map.of("draft", Map.of("templateKey", "software-delivery"))).onboarding();
        WorkspaceOnboardingService.OnboardingView paused = service.prepare(1L, 10L,
                Map.of("onboardingId", created.id(), "expectedVersion", created.version(),
                        "draft", Map.of("paused", true))).onboarding();
        assertTrue(paused.paused());
        assertEquals(WorkspaceOnboardingService.STAGE_DRAFT, paused.stage());
    }

    @Test
    void rejectsMissingDraftAndNonBooleanPause() {
        assertEquals(ErrorCode.MCP_TOOL_ARGUMENT_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.prepare(1L, 10L, Map.of())).getCode());
        assertEquals(ErrorCode.MCP_TOOL_ARGUMENT_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.prepare(1L, 10L,
                        Map.of("draft", Map.of("paused", "yes")))).getCode());
    }

    @Test
    void getByOnboardingIdReturnsViewAndForeignOwnerIsRejected() {
        WorkspaceOnboardingService.OnboardingView created = service.prepare(1L, 10L,
                Map.of("draft", Map.of("templateKey", "software-delivery"))).onboarding();
        Map<String, Object> found = service.get(1L, 10L, Map.of("onboardingId", created.id()));
        assertNotNull(found.get("onboarding"));
        assertEquals(ErrorCode.HARNESS_ONBOARDING_NOT_FOUND.getCode(),
                assertThrows(BizException.class, () -> service.get(1L, 99L,
                        Map.of("onboardingId", created.id()))).getCode());
    }

    @Test
    void rejectsUnknownStageAndClearsPauseOnReady() {
        WorkspaceOnboardingDO doc = service.require(1L, 10L, service.prepare(1L, 10L,
                Map.of("draft", Map.of("templateKey", "software-delivery"))).onboarding().id());
        assertEquals(ErrorCode.HARNESS_CONFIGURATION_INVALID.getCode(),
                assertThrows(BizException.class, () -> service.advanceStage(doc, 10L, "UNKNOWN")).getCode());
        service.pause(doc, 10L, true);
        service.markReady(doc, 10L);
        WorkspaceOnboardingDO stored = service.require(1L, 10L, doc.getId());
        assertEquals(WorkspaceOnboardingService.STAGE_READY, stored.getStage());
        assertFalse(Boolean.TRUE.equals(stored.getPaused()));
    }

    @Test
    void advanceToSameStageIsNoOpAndEmptyViewHasNoSteps() {
        WorkspaceOnboardingDO doc = service.require(1L, 10L, service.prepare(1L, 10L,
                Map.of("draft", Map.of("templateKey", "software-delivery"))).onboarding().id());
        int version = doc.getVersion();
        service.advanceStage(doc, 10L, doc.getStage());
        assertEquals(version, service.require(1L, 10L, doc.getId()).getVersion());

        WorkspaceOnboardingDO blank = new WorkspaceOnboardingDO();
        blank.setId(999L);
        blank.setTenantId(1L);
        blank.setWorkspaceId(1L);
        blank.setOwnerUserId(10L);
        blank.setStage(WorkspaceOnboardingService.STAGE_DRAFT);
        blank.setVersion(0);
        blank.setConfigVersion(0);
        blank.setDraftJson("{}");
        assertEquals(0, service.view(blank).totalSteps());
    }

    @Test
    void staleUpdateRaisesVersionConflict() throws Exception {
        WorkspaceOnboardingDO doc = service.require(1L, 10L, service.prepare(1L, 10L,
                Map.of("draft", Map.of("templateKey", "software-delivery"))).onboarding().id());
        WorkspaceOnboardingDO stale = service.require(1L, 10L, doc.getId());
        service.pause(doc, 10L, true);
        java.lang.reflect.Method update = WorkspaceOnboardingService.class.getDeclaredMethod("update",
                WorkspaceOnboardingDO.class, long.class, String.class,
                com.aliyun.autowonder.json.JSONObject.class, Integer.class, String.class);
        update.setAccessible(true);
        java.lang.reflect.InvocationTargetException failure = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> update.invoke(service, stale, 10L, stale.getStage(), null, null, null));
        assertEquals(ErrorCode.HARNESS_ONBOARDING_VERSION_CONFLICT.getCode(),
                ((BizException) failure.getCause()).getCode());
    }

    @Test
    void acceptsTextVariablesOnCreateAndUpdateAndPreservesOtherValues() {
        List<String> tokens = catalog.list().get(0).variableTokens();
        var created = service.prepare(1L, 10L, Map.of("draft", Map.of(
                "templateKey", "software-delivery", "variables", Map.of(
                        tokens.get(0), "npm test\n执行目录：frontend",
                        tokens.get(1), Map.of("notApplicable", true, "reason", "不涉及数据库")))))
                .onboarding();
        var stored = service.draft(service.require(1L, 10L, created.id())).getJSONObject("variables");
        assertEquals("npm test\n执行目录：frontend", stored.getJSONObject(tokens.get(0)).getString("value"));
        assertTrue(stored.getJSONObject(tokens.get(1)).getBooleanValue("notApplicable"));
        var updated = service.prepare(1L, 10L, Map.of("onboardingId", created.id(),
                "expectedVersion", created.version(), "draft", Map.of("variables",
                        Map.of(tokens.get(0), "npm run test")))).onboarding();
        assertEquals(WorkspaceOnboardingService.STATUS_COMPLETED,
                step(updated, WorkspaceOnboardingService.variableStepKey(tokens.get(0))).status());
        assertEquals(WorkspaceOnboardingService.STATUS_NOT_APPLICABLE,
                step(updated, WorkspaceOnboardingService.variableStepKey(tokens.get(1))).status());
    }

    @Test
    void invalidVariableShapesAreRejectedBeforeDraftUpdate() {
        String token = catalog.list().get(0).variableTokens().get(0);
        var created = service.prepare(1L, 10L, Map.of("draft", Map.of("templateKey", "software-delivery")))
                .onboarding();
        var invalid = new java.util.ArrayList<Object>(List.of(123, true, List.of("text"),
                Map.of("value", 123), Map.of("notApplicable", "true"), Map.of("reason", List.of("text"))));
        invalid.add(null);
        for (Object value : invalid) {
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put(token, value);
            BizException failure = assertThrows(BizException.class, () -> service.prepare(1L, 10L,
                    Map.of("onboardingId", created.id(), "expectedVersion", created.version(),
                            "draft", Map.of("variables", variables))));
            assertEquals(ErrorCode.MCP_TOOL_ARGUMENT_INVALID.getCode(), failure.getCode());
            var stored = service.require(1L, 10L, created.id());
            assertEquals(created.version(), stored.getVersion());
            assertFalse(service.draft(stored).containsKey("variables"));
        }
    }

    private WorkspaceOnboardingService.StepView step(WorkspaceOnboardingService.OnboardingView view,
            String stepKey) {
        return view.steps().stream().filter(item -> item.stepKey().equals(stepKey)).findFirst().orElseThrow();
    }
}
