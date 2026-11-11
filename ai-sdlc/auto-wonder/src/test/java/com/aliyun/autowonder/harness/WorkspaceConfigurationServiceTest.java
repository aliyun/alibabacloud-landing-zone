package com.aliyun.autowonder.harness;

import com.aliyun.autowonder.agent.AgentService;
import com.aliyun.autowonder.agent.dto.AgentVO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.repo.RepoService;
import com.aliyun.autowonder.repo.dto.RepoVO;
import com.aliyun.autowonder.sdlc.SdlcService;
import com.aliyun.autowonder.sdlc.dto.SdlcVO;
import com.aliyun.autowonder.sdlc.dto.StepVO;
import com.aliyun.autowonder.sdlc.dto.UpdateStepRequest;
import com.aliyun.autowonder.skill.SkillService;
import com.aliyun.autowonder.skill.dto.SkillVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceConfigurationServiceTest {

    private final InMemoryWorkspaceOnboardingDao dao = new InMemoryWorkspaceOnboardingDao();
    private final HarnessTemplateCatalog catalog = new HarnessTemplateCatalog();
    private final WorkspaceOnboardingService onboardingService = new WorkspaceOnboardingService(dao, catalog);
    private final HarnessPreviewService previewService =
            new HarnessPreviewService(onboardingService, dao, catalog, mock(
                    com.aliyun.autowonder.executor.ExecutorLaunchOptionsService.class));
    private final RepoService repoService = mock(RepoService.class);
    private final AgentService agentService = mock(AgentService.class);
    private final SdlcService sdlcService = mock(SdlcService.class);
    private final SkillService skillService = mock(SkillService.class);

    private final WorkspaceConfigurationService service = new WorkspaceConfigurationService(dao, onboardingService,
            previewService, catalog, repoService, agentService, sdlcService, skillService);

    private WorkspaceOnboardingDO onboarding;
    private String token;

    @BeforeEach
    void setUp() {
        WorkspaceOnboardingService.OnboardingView created = onboardingService.prepare(11L, 110L,
                Map.of("draft", Map.of("templateKey", "software-delivery"))).onboarding();
        onboarding = onboardingService.require(11L, 110L, created.id());
        onboardingService.advanceStage(onboarding, 110L, WorkspaceOnboardingService.STAGE_TEMPLATE_INITIALIZED);
        token = catalog.list().get(0).variableTokens().get(0);
        Map<String, Object> objects = new LinkedHashMap<>();
        objects.put("agents", Map.of("AW_AUTO_FS_DEV", 300L, "AW_AUTO_CR", 301L, "AW_AUTO_QA", 302L,
                "AW_AUTO_REQ_CLARIFIER", 303L, "AW_AUTO_PM", 304L));
        objects.put("sdcls", Map.of("AW_AUTO_FS_DEV", 400L));
        objects.put("skills", Map.of());
        objects.put("squad", 500L);
        objects.put("executors", Map.of());
        onboardingService.recordDraftSection(onboarding, 110L, "objects", objects);
        onboarding = onboardingService.require(11L, 110L, onboarding.getId());

        when(repoService.list(anyLong(), anyInt(), anyInt())).thenReturn(List.of());
        java.util.concurrent.atomic.AtomicLong repoIds = new java.util.concurrent.atomic.AtomicLong(700);
        when(repoService.create(any(), anyLong(), anyLong())).thenAnswer(invocation -> {
            RepoVO repo = new RepoVO();
            repo.setId(repoIds.getAndIncrement());
            return repo;
        });
        when(repoService.createRelation(any(), anyLong(), anyLong())).thenAnswer(invocation -> {
            com.aliyun.autowonder.repo.dto.RepoRelationVO relation =
                    new com.aliyun.autowonder.repo.dto.RepoRelationVO();
            relation.setId(900L);
            return relation;
        });
        when(skillService.list(anyLong(), anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(agentService.get(anyLong(), anyLong())).thenAnswer(invocation -> {
            AgentVO agent = new AgentVO();
            agent.setStatus("ONLINE");
            agent.setHasDraft(false);
            return agent;
        });
        when(sdlcService.create(any(), anyLong(), anyLong())).thenAnswer(invocation -> {
            SdlcVO copy = new SdlcVO();
            copy.setId(800L);
            return copy;
        });
    }

    @Test
    void initializationConflictsRenderBothLanguagesWithoutChangingRawFields() {
        String rawName = "用户's <raw>{0}";
        Map<String, Object> repo = Map.of("name", "app", "url", "git@example/app", "defaultBranch", "main");
        Map<String, String> permission = Map.of("roleCode", "AW_AUTO_QA", "repo", "app", "permLevel", "WRITE");
        Map<String, String> binding = Map.of("roleCode", "AW_AUTO_QA", "skillName", rawName);
        Map<String, Object> changes = Map.of("repositories", List.of(repo, repo),
                "repoPermissions", List.of(permission, permission),
                "relations", List.of(Map.of("from", "app", "to", "app")),
                "skillBindings", List.of(binding, binding));
        Map<String, String> english = Map.of(
                "REPOSITORY_DUPLICATE", "Repository names must be unique.",
                "PERMISSION_DUPLICATE", "Repository permissions for a role must not be duplicated.",
                "PERMISSION_REQUIRED", "Initialization is missing required repository permissions for a role.",
                "RELATION_INVALID", "Repository dependencies must not reference themselves or be duplicated.",
                "SKILL_BINDING_DUPLICATE", "Skill bindings for a role must not be duplicated.",
                "SKILL_BINDING_MISSING", "The specified Skill does not exist in the current workspace.");
        Map<String, String> chinese = Map.of(
                "REPOSITORY_DUPLICATE", "仓库名不能重复。",
                "PERMISSION_DUPLICATE", "角色的仓库权限配置不能重复。",
                "PERMISSION_REQUIRED", "初始化缺少必需的角色仓库权限。",
                "RELATION_INVALID", "仓库依赖不能自引用或重复。",
                "SKILL_BINDING_DUPLICATE", "角色的 Skill 绑定不能重复。",
                "SKILL_BINDING_MISSING", "指定的 Skill 不在当前空间。");
        try {
            for (java.util.Locale locale : List.of(java.util.Locale.US, java.util.Locale.CHINA)) {
                org.springframework.context.i18n.LocaleContextHolder.setLocale(locale);
                Map<String, String> expected = locale.equals(java.util.Locale.US) ? english : chinese;
                List<?> conflicts = (List<?>) service.normalizeInitializationChanges(11L, 110L, onboarding, changes)
                        .get("conflicts");
                java.util.Set<String> codes = new java.util.LinkedHashSet<>();
                for (Object value : conflicts) {
                    Map<?, ?> conflict = (Map<?, ?>) value;
                    String code = String.valueOf(conflict.get("code"));
                    codes.add(code);
                    assertEquals(expected.get(code), conflict.get("message"));
                    if ("SKILL_BINDING_MISSING".equals(code)) {
                        assertEquals(rawName, conflict.get("field"));
                    }
                }
                assertEquals(expected.keySet(), codes);
                List<?> empty = (List<?>) service.normalizeInitializationChanges(11L, 110L, onboarding,
                        Map.of("repositories", List.of())).get("conflicts");
                assertEquals(locale.equals(java.util.Locale.US) ? "Initialization requires at least one repository."
                        : "初始化必须配置至少一个仓库。", ((Map<?, ?>) empty.get(0)).get("message"));
            }
        } finally {
            org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();
        }
    }

    @Test
    void initializationPreflightAllowsScriptDeploymentBeforeAgentsExist() {
        onboardingService.recordDraftSection(onboarding, 110L, "objects", Map.of());
        onboarding = onboardingService.require(11L, 110L, onboarding.getId());
        Map<String, Object> result = service.normalizeInitializationChanges(11L, 110L, onboarding,
                initializationChanges(List.of()));
        assertTrue(((List<?>) result.get("conflicts")).isEmpty());
    }

    @Test
    void initializationPreflightRejectsMissingExplicitSkillAndMissingPermissions() {
        Map<String, Object> changes = new LinkedHashMap<>(initializationChanges(
                List.of(Map.of("roleCode", "AW_AUTO_QA", "skillName", "missing-deploy"))));
        changes.put("repoPermissions", List.of());
        Map<String, Object> result = service.normalizeInitializationChanges(11L, 110L, onboarding, changes);
        String conflicts = result.get("conflicts").toString();
        assertTrue(conflicts.contains("SKILL_BINDING_MISSING"));
        assertTrue(conflicts.contains("PERMISSION_REQUIRED"));
    }

    @Test
    void initializationPreflightRejectsEmptyRepositoriesAndUnsupportedChanges() {
        Map<String, Object> result = service.normalizeInitializationChanges(11L, 110L, onboarding,
                Map.of("repositories", List.of()));
        assertTrue(result.get("conflicts").toString().contains("REPOSITORY_REQUIRED"));
        assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                () -> service.normalizeInitializationChanges(11L, 110L, onboarding,
                        Map.of("sdlcInstructions", List.of())));
    }

    private Map<String, Object> initializationChanges(List<Map<String, String>> bindings) {
        List<Map<String, String>> permissions = catalog.list().get(0).getAgents().stream()
                .filter(agent -> catalog.repoPermissionFor(agent.roleCode()) != null)
                .map(agent -> Map.of("roleCode", agent.roleCode(), "repo", "app",
                        "permLevel", catalog.repoPermissionFor(agent.roleCode()))).toList();
        return Map.of("repositories", List.of(Map.of("name", "app", "url", "git@example/app",
                        "defaultBranch", "main")), "repoPermissions", permissions, "skillBindings", bindings);
    }

    @Test
    void unknownVariableIsReportedAsConflict() {
        Map<String, Object> preview = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(), "expectedVersion", onboarding.getVersion(),
                        "changes", Map.of("variables", List.of(Map.of("token", "{{unknown}}", "value", "x")))));
        assertNull(preview.get("snapshotId"));
        assertEquals("VARIABLE_UNKNOWN", firstConflict(preview).get("code"));
    }

    @Test
    void permissionAboveTemplateMatrixIsRejected() {
        Map<String, Object> preview = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(), "expectedVersion", onboarding.getVersion(),
                        "changes", Map.of(
                                "repositories", List.of(Map.of("name", "server", "url", "https://git/x.git",
                                        "defaultBranch", "master")),
                                "repoPermissions", List.of(Map.of("roleCode", "AW_AUTO_CR", "repo", "server",
                                        "permLevel", "WRITE")))));
        assertEquals("PERMISSION_LEVEL_MISMATCH", firstConflict(preview).get("code"));
    }

    @Test
    void rejectsUnknownChangeSection() {
        com.aliyun.autowonder.common.error.BizException invalid = org.junit.jupiter.api.Assertions.assertThrows(
                com.aliyun.autowonder.common.error.BizException.class,
                () -> service.preview(11L, 110L,
                        Map.of("onboardingId", onboarding.getId(), "expectedVersion", onboarding.getVersion(),
                                "changes", Map.of("overwriteEverything", true))));
        assertEquals(com.aliyun.autowonder.common.error.ErrorCode.HARNESS_CONFIGURATION_INVALID.getCode(),
                invalid.getCode());
    }

    @Test
    void appliesVariablesRepositoriesAndPermissions() {
        SdlcVO sdlc = new SdlcVO();
        sdlc.setId(400L);
        StepVO step = new StepVO();
        step.setId(900L);
        step.setSdlcId(400L);
        step.setCode("dev_test_prepare");
        step.setInstructionMd("执行 " + token + " 完成测试");
        sdlc.setSteps(List.of(step));
        when(sdlcService.get(400L)).thenReturn(sdlc);

        Map<String, Object> preview = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(), "expectedVersion", onboarding.getVersion(),
                        "changes", Map.of(
                                "variables", List.of(Map.of("token", token, "value", "mvn -q test")),
                                "repositories", List.of(Map.of("name", "server", "url", "https://git/x.git",
                                        "defaultBranch", "master", "description", "服务端")),
                                "repoPermissions", List.of(Map.of("roleCode", "AW_AUTO_FS_DEV",
                                        "repo", "server", "permLevel", "WRITE")))));
        assertEquals(List.of(), preview.get("conflicts"));
        assertNotNull(preview.get("snapshotId"));

        Map<String, Object> run = service.apply(11L, 110L, Map.of(
                "snapshotId", preview.get("snapshotId"), "snapshotHash", preview.get("snapshotHash")));
        assertEquals("SUCCEEDED", run.get("status"));

        ArgumentCaptor<UpdateStepRequest> captor = ArgumentCaptor.forClass(UpdateStepRequest.class);
        verify(sdlcService).updateStep(eq(400L), eq(900L), captor.capture(), eq(11L), eq(110L));
        assertEquals("执行 mvn -q test 完成测试", captor.getValue().getInstructionMd());

        verify(repoService).create(any(), eq(11L), eq(110L));
        verify(agentService).addRepoPerm(eq(300L), any(), eq(11L), eq(110L));
        verify(agentService).submit(eq(300L), eq(11L), eq(110L));
        verify(agentService).approve(eq(300L), eq(11L), eq(110L), isNull());

        WorkspaceOnboardingDO stored = onboardingService.require(11L, 110L, onboarding.getId());
        JSONObjectView draft = new JSONObjectView(stored.getDraftJson());
        assertTrue(draft.flag("configApplied", "repositories"));
        assertTrue(draft.flag("configApplied", "repoPermissions"));
        assertTrue(draft.containsVariable(token));
    }

    @Test
    void copiesReusedSdlcBeforeApplyingVariables() {
        Map<String, Object> objects = new LinkedHashMap<>();
        objects.put("agents", Map.of("AW_AUTO_FS_DEV", 300L));
        objects.put("sdcls", Map.of("AW_AUTO_FS_DEV", 400L));
        objects.put("skills", Map.of());
        objects.put("squad", 500L);
        objects.put("executors", Map.of());
        objects.put("reusedSdcls", List.of("AW_AUTO_FS_DEV"));
        onboardingService.recordDraftSection(onboardingService.require(11L, 110L, onboarding.getId()),
                110L, "objects", objects);

        SdlcVO reused = new SdlcVO();
        reused.setId(400L);
        reused.setName("共享全栈流程");
        StepVO step = new StepVO();
        step.setId(900L);
        step.setSdlcId(400L);
        step.setCode("dev_test_prepare");
        step.setInstructionMd("执行 " + token + " 完成测试");
        reused.setSteps(List.of(step));
        when(sdlcService.get(400L)).thenReturn(reused);
        when(sdlcService.get(800L)).thenReturn(reused);

        Map<String, Object> preview = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(),
                        "expectedVersion", onboardingService.require(11L, 110L, onboarding.getId()).getVersion(),
                        "changes", Map.of("variables", List.of(Map.of("token", token, "value", "mvn -q test")))));
        assertEquals(List.of(), preview.get("conflicts"));
        Map<String, Object> run = service.apply(11L, 110L, Map.of(
                "snapshotId", preview.get("snapshotId"), "snapshotHash", preview.get("snapshotHash")));
        assertEquals("SUCCEEDED", run.get("status"));

        verify(sdlcService).create(any(), eq(11L), eq(110L));
        verify(agentService).editConfig(eq(300L), any(), eq(11L), eq(110L));
        verify(sdlcService).updateStep(eq(800L), eq(900L), any(UpdateStepRequest.class), eq(11L), eq(110L));
        verify(sdlcService, org.mockito.Mockito.never())
                .updateStep(eq(400L), anyLong(), any(UpdateStepRequest.class), anyLong(), anyLong());
    }

    @Test
    void revisesAppliedVariableUsingPreviousValue() {
        onboardingService.recordDraftSection(onboardingService.require(11L, 110L, onboarding.getId()),
                110L, "variables", Map.of(token, Map.of("value", "mvn -q test")));
        onboardingService.recordDraftSection(onboardingService.require(11L, 110L, onboarding.getId()),
                110L, "appliedVariables", Map.of(token, Map.of("value", "mvn -q test", "steps", List.of("900"))));
        onboarding = onboardingService.require(11L, 110L, onboarding.getId());
        SdlcVO sdlc = new SdlcVO();
        sdlc.setId(400L);
        StepVO step = new StepVO();
        step.setId(900L);
        step.setSdlcId(400L);
        step.setCode("dev_test_prepare");
        step.setInstructionMd("执行 mvn -q test 完成测试");
        sdlc.setSteps(List.of(step));
        when(sdlcService.get(400L)).thenReturn(sdlc);

        Map<String, Object> preview = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(), "expectedVersion", onboarding.getVersion(),
                        "changes", Map.of("variables", List.of(Map.of("token", token, "value", "gradle test")))));
        assertEquals(List.of(), preview.get("conflicts"));
        Map<String, Object> run = service.apply(11L, 110L, Map.of(
                "snapshotId", preview.get("snapshotId"), "snapshotHash", preview.get("snapshotHash")));
        assertEquals("SUCCEEDED", run.get("status"));

        ArgumentCaptor<UpdateStepRequest> captor = ArgumentCaptor.forClass(UpdateStepRequest.class);
        verify(sdlcService).updateStep(eq(400L), eq(900L), captor.capture(), eq(11L), eq(110L));
        assertEquals("执行 gradle test 完成测试", captor.getValue().getInstructionMd());
        assertEquals(WorkspaceOnboardingService.STAGE_RUNTIME_PENDING,
                onboardingService.require(11L, 110L, onboarding.getId()).getStage());
    }

    @Test
    void ambiguousPreviousValueIsRejectedInsteadOfGlobalReplace() {
        onboardingService.recordDraftSection(onboardingService.require(11L, 110L, onboarding.getId()),
                110L, "variables", Map.of(token, Map.of("value", "test")));
        onboardingService.recordDraftSection(onboardingService.require(11L, 110L, onboarding.getId()),
                110L, "appliedVariables", Map.of(token, Map.of("value", "test", "steps", List.of("900"))));
        onboarding = onboardingService.require(11L, 110L, onboarding.getId());
        SdlcVO sdlc = new SdlcVO();
        sdlc.setId(400L);
        StepVO step = new StepVO();
        step.setId(900L);
        step.setSdlcId(400L);
        step.setCode("dev_test_prepare");
        step.setInstructionMd("执行 test 后再跑 test 完成测试");
        sdlc.setSteps(List.of(step));
        when(sdlcService.get(400L)).thenReturn(sdlc);

        Map<String, Object> preview = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(), "expectedVersion", onboarding.getVersion(),
                        "changes", Map.of("variables", List.of(Map.of("token", token, "value", "gradle test")))));
        Map<String, Object> run = service.apply(11L, 110L, Map.of(
                "snapshotId", preview.get("snapshotId"), "snapshotHash", preview.get("snapshotHash")));
        assertEquals("FAILED", run.get("status"));
        assertTrue(String.valueOf(run.get("failure")).contains("不唯一"));
        verify(sdlcService, org.mockito.Mockito.never())
                .updateStep(eq(400L), eq(900L), any(UpdateStepRequest.class), eq(11L), eq(110L));
    }

    @Test
    void copiesReusedSdlcOnlyOnceAcrossBatches() {
        Map<String, Object> objects = new LinkedHashMap<>();
        objects.put("agents", Map.of("AW_AUTO_FS_DEV", 300L));
        objects.put("sdcls", Map.of("AW_AUTO_FS_DEV", 400L));
        objects.put("skills", Map.of());
        objects.put("squad", 500L);
        objects.put("executors", Map.of());
        objects.put("reusedSdcls", List.of("AW_AUTO_FS_DEV"));
        onboardingService.recordDraftSection(onboardingService.require(11L, 110L, onboarding.getId()),
                110L, "objects", objects);

        SdlcVO shared = new SdlcVO();
        shared.setId(400L);
        shared.setName("共享全栈流程");
        StepVO sharedStep = new StepVO();
        sharedStep.setId(900L);
        sharedStep.setSdlcId(400L);
        sharedStep.setCode("dev_test_prepare");
        sharedStep.setInstructionMd("执行 " + token + " 完成测试");
        shared.setSteps(List.of(sharedStep));
        when(sdlcService.get(400L)).thenReturn(shared);

        java.util.concurrent.atomic.AtomicReference<SdlcVO> copyView = new java.util.concurrent.atomic.AtomicReference<>();
        SdlcVO copy = new SdlcVO();
        copy.setId(800L);
        copy.setName("共享全栈流程（实例 " + onboarding.getId() + "）");
        StepVO copyStep = new StepVO();
        copyStep.setId(900L);
        copyStep.setSdlcId(800L);
        copyStep.setCode("dev_test_prepare");
        copyStep.setInstructionMd("执行 " + token + " 完成测试");
        copy.setSteps(List.of(copyStep));
        copyView.set(copy);
        when(sdlcService.get(800L)).thenAnswer(invocation -> copyView.get());

        Map<String, Object> first = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(),
                        "expectedVersion", onboardingService.require(11L, 110L, onboarding.getId()).getVersion(),
                        "changes", Map.of("variables", List.of(Map.of("token", token, "value", "第一批")))));
        Map<String, Object> firstRun = service.apply(11L, 110L, Map.of(
                "snapshotId", first.get("snapshotId"), "snapshotHash", first.get("snapshotHash")));
        assertEquals("SUCCEEDED", firstRun.get("status"));
        copyStep.setInstructionMd("执行 第一批 完成测试");

        Map<String, Object> second = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(),
                        "expectedVersion", onboardingService.require(11L, 110L, onboarding.getId()).getVersion(),
                        "changes", Map.of("variables", List.of(Map.of("token", token, "value", "第二批")))));
        Map<String, Object> secondRun = service.apply(11L, 110L, Map.of(
                "snapshotId", second.get("snapshotId"), "snapshotHash", second.get("snapshotHash")));
        assertEquals("SUCCEEDED", secondRun.get("status"));

        verify(sdlcService, org.mockito.Mockito.times(1)).create(any(), eq(11L), eq(110L));
        ArgumentCaptor<UpdateStepRequest> captor = ArgumentCaptor.forClass(UpdateStepRequest.class);
        verify(sdlcService, org.mockito.Mockito.times(2))
                .updateStep(eq(800L), eq(900L), captor.capture(), eq(11L), eq(110L));
        assertEquals("执行 第二批 完成测试", captor.getAllValues().get(1).getInstructionMd());
        com.aliyun.autowonder.json.JSONObject stored = com.aliyun.autowonder.json.JSON
                .parseObject(onboardingService.require(11L, 110L, onboarding.getId()).getDraftJson())
                .getJSONObject("objects");
        assertTrue(stored.get("reusedSdcls") instanceof List<?> reused && reused.isEmpty());
        assertTrue(stored.get("ownedSdcls") instanceof List<?> owned && owned.contains("AW_AUTO_FS_DEV"));
    }

    @Test
    void applyIsIdempotentForSameSnapshotAndSucceededResumeReturns() {
        Map<String, Object> preview = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(), "expectedVersion", expectedVersion(),
                        "changes", Map.of("repositories", List.of())));
        Map<String, Object> first = service.apply(11L, 110L,
                Map.of("snapshotId", preview.get("snapshotId"), "snapshotHash", preview.get("snapshotHash")));
        Map<String, Object> second = service.apply(11L, 110L,
                Map.of("snapshotId", preview.get("snapshotId"), "snapshotHash", preview.get("snapshotHash")));
        assertEquals("SUCCEEDED", first.get("status"));
        assertEquals(first.get("runId"), second.get("runId"));

        Map<String, Object> resumed = service.resume(11L, 110L, Map.of("runId", first.get("runId")));
        assertEquals("SUCCEEDED", resumed.get("status"));
    }

    @Test
    void configurationRunLookupsReportNotFound() {
        assertEquals(ErrorCode.NOT_FOUND.getCode(),
                assertThrows(BizException.class, () -> service.get(11L, 110L, Map.of("runId", 987654L))).getCode());
        assertEquals(ErrorCode.NOT_FOUND.getCode(),
                assertThrows(BizException.class, () -> service.resume(11L, 110L, Map.of("runId", 987654L))).getCode());
    }

    @Test
    void resumeRetriesOnlyUnfinishedObjects() {
        java.util.Set<String> failedOnce = new java.util.HashSet<>();
        java.util.Map<String, Integer> created = new java.util.LinkedHashMap<>();
        when(repoService.create(any(), eq(11L), eq(110L))).thenAnswer(invocation -> {
            com.aliyun.autowonder.repo.dto.CreateRepoRequest request = invocation.getArgument(0);
            String name = request.getName();
            created.merge(name, 1, Integer::sum);
            if ("front".equals(name) && failedOnce.add(name)) {
                throw new BizException(ErrorCode.HARNESS_CONFIGURATION_INVALID);
            }
            RepoVO repo = new RepoVO();
            repo.setId("server".equals(name) ? 700L : 701L);
            repo.setName(name);
            return repo;
        });
        Map<String, Object> preview = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(), "expectedVersion", expectedVersion(),
                        "changes", Map.of("repositories", List.of(
                                Map.of("name", "server", "url", "https://git/server.git", "defaultBranch", "master"),
                                Map.of("name", "front", "url", "https://git/front.git", "defaultBranch", "master")))));
        Map<String, Object> failed = service.apply(11L, 110L,
                Map.of("snapshotId", preview.get("snapshotId"), "snapshotHash", preview.get("snapshotHash")));
        assertEquals("FAILED", failed.get("status"));

        Map<String, Object> resumed = service.resume(11L, 110L, Map.of("runId", failed.get("runId")));
        assertEquals("SUCCEEDED", resumed.get("status"));
        assertEquals(1, created.get("server"));
        assertEquals(2, created.get("front"));
    }

    @Test
    void reportsEveryConfigurationConflictCode() {
        assertEquals("VARIABLE_UNKNOWN", conflictCode(Map.of("variables",
                List.of(Map.of("token", "{{unknown}}", "value", "x")))));
        assertEquals("VARIABLE_UNRESOLVED", conflictCode(Map.of("variables",
                List.of(Map.of("token", token, "value", "{{still}}")))));
        assertEquals("VARIABLE_REASON_REQUIRED", conflictCode(Map.of("variables",
                List.of(Map.of("token", token, "notApplicable", true)))));
        assertEquals("VARIABLE_VALUE_REQUIRED", conflictCode(Map.of("variables",
                List.of(Map.of("token", token, "value", " ")))));
        assertEquals("REPOSITORY_NAME_INVALID", conflictCode(Map.of("repositories",
                List.of(Map.of("name", "a/b", "url", "https://git/a.git", "defaultBranch", "master")))));
        assertEquals("REPOSITORY_INCOMPLETE", conflictCode(Map.of("repositories",
                List.of(Map.of("name", "server")))));
        assertEquals("RELATION_ENDPOINT_UNKNOWN", conflictCode(Map.of("relations",
                List.of(Map.of("from", "front", "to", "server")))));
        assertEquals("PERMISSION_ROLE_UNKNOWN", conflictCode(Map.of(
                "repositories", List.of(Map.of("name", "server", "url", "https://git/s.git",
                        "defaultBranch", "master")),
                "repoPermissions", List.of(Map.of("roleCode", "UNKNOWN", "repo", "server",
                        "permLevel", "READ")))));
        assertEquals("PERMISSION_REPO_UNKNOWN", conflictCode(Map.of(
                "repositories", List.of(Map.of("name", "server", "url", "https://git/s.git",
                        "defaultBranch", "master")),
                "repoPermissions", List.of(Map.of("roleCode", "AW_AUTO_CR", "repo", "other",
                        "permLevel", "READ")))));
        assertEquals("PERMISSION_LEVEL_MISMATCH", conflictCode(Map.of(
                "repositories", List.of(Map.of("name", "server", "url", "https://git/s.git",
                        "defaultBranch", "master")),
                "repoPermissions", List.of(Map.of("roleCode", "AW_AUTO_CR", "repo", "server",
                        "permLevel", "WRITE")))));
        assertEquals("SKILL_BINDING_ROLE_UNKNOWN", conflictCode(Map.of("skillBindings",
                List.of(Map.of("roleCode", "UNKNOWN", "skillName", "deploy")))));
        assertEquals("SKILL_BINDING_NAME_REQUIRED", conflictCode(Map.of("skillBindings",
                List.of(Map.of("roleCode", "AW_AUTO_FS_DEV", "skillName", " ")))));
        assertEquals("SDLC_STEP_UNKNOWN", conflictCode(Map.of("sdlcInstructions",
                List.of(Map.of("roleCode", "AW_AUTO_FS_DEV", "stepCode", "missing",
                        "instructionMd", "x")))));
        assertEquals("SDLC_INSTRUCTION_REQUIRED", conflictCode(Map.of("sdlcInstructions",
                List.of(Map.of("roleCode", "AW_AUTO_FS_DEV", "stepCode", "dev_test_prepare",
                        "instructionMd", " ")))));
    }

    @Test
    void reportsMissingAgentWhenGrantingPermissions() {
        Map<String, Object> objectsWithoutAgents = new LinkedHashMap<>();
        objectsWithoutAgents.put("agents", Map.of());
        objectsWithoutAgents.put("sdcls", Map.of("AW_AUTO_FS_DEV", 400L));
        objectsWithoutAgents.put("skills", Map.of());
        objectsWithoutAgents.put("squad", 500L);
        objectsWithoutAgents.put("executors", Map.of());
        onboardingService.recordDraftSection(onboardingService.require(11L, 110L, onboarding.getId()),
                110L, "objects", objectsWithoutAgents);
        assertEquals("PERMISSION_AGENT_MISSING", conflictCode(Map.of(
                "repositories", List.of(Map.of("name", "server", "url", "https://git/s.git",
                        "defaultBranch", "master")),
                "repoPermissions", List.of(Map.of("roleCode", "AW_AUTO_FS_DEV", "repo", "server",
                        "permLevel", "WRITE")))));
    }

    @Test
    void appliesRelationsPermissionsBindingsAndInstructions() {
        SdlcVO sdlc = new SdlcVO();
        sdlc.setId(400L);
        StepVO step = new StepVO();
        step.setId(900L);
        step.setSdlcId(400L);
        step.setCode("dev_test_prepare");
        step.setInstructionMd("原指令");
        sdlc.setSteps(List.of(step));
        when(sdlcService.get(400L)).thenReturn(sdlc);
        SkillVO skill = new SkillVO();
        skill.setId(950L);
        skill.setName("deploy-skill");
        when(skillService.list(anyLong(), anyString(), anyInt(), anyInt())).thenReturn(List.of(skill));

        Map<String, Object> preview = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(), "expectedVersion", expectedVersion(),
                        "changes", Map.of(
                                "repositories", List.of(
                                        Map.of("name", "server", "url", "https://git/s.git",
                                                "defaultBranch", "master"),
                                        Map.of("name", "front", "url", "https://git/f.git",
                                                "defaultBranch", "main")),
                                "relations", List.of(Map.of("from", "front", "to", "server")),
                                "repoPermissions", List.of(Map.of("roleCode", "AW_AUTO_FS_DEV",
                                        "repo", "server", "permLevel", "WRITE")),
                                "skillBindings", List.of(Map.of("roleCode", "AW_AUTO_FS_DEV",
                                        "skillName", "deploy-skill")),
                                "sdlcInstructions", List.of(Map.of("roleCode", "AW_AUTO_FS_DEV",
                                        "stepCode", "dev_test_prepare", "instructionMd", "自定义指令")))));
        assertEquals(List.of(), preview.get("conflicts"));
        Map<String, Object> run = service.apply(11L, 110L,
                Map.of("snapshotId", preview.get("snapshotId"), "snapshotHash", preview.get("snapshotHash")));
        assertEquals("SUCCEEDED", run.get("status"));

        ArgumentCaptor<com.aliyun.autowonder.repo.dto.CreateRelationRequest> relation =
                ArgumentCaptor.forClass(com.aliyun.autowonder.repo.dto.CreateRelationRequest.class);
        verify(repoService).createRelation(relation.capture(), eq(11L), eq(110L));
        assertEquals(701L, relation.getValue().getFromRepoId());
        assertEquals(700L, relation.getValue().getToRepoId());
        assertEquals("DEPENDS_ON", relation.getValue().getRelationType());
        verify(agentService).addRepoPerm(eq(300L), any(), eq(11L), eq(110L));
        verify(agentService).addSkill(eq(300L), any(), eq(11L), eq(110L));
        ArgumentCaptor<UpdateStepRequest> instruction = ArgumentCaptor.forClass(UpdateStepRequest.class);
        verify(sdlcService).updateStep(eq(400L), eq(900L), instruction.capture(), eq(11L), eq(110L));
        assertEquals("自定义指令", instruction.getValue().getInstructionMd());
    }

    @Test
    void reusesRepositoryByNameAndRejectsDifferentUrl() {
        RepoVO existing = new RepoVO();
        existing.setId(700L);
        existing.setName("server");
        existing.setUrl("https://git/s.git");
        when(repoService.list(anyLong(), anyInt(), anyInt())).thenReturn(List.of(existing));

        Map<String, Object> reuse = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(), "expectedVersion", expectedVersion(),
                        "changes", Map.of("repositories", List.of(Map.of("name", "server",
                                "url", "https://git/s.git", "defaultBranch", "master")))));
        assertEquals(List.of(), reuse.get("conflicts"));
        service.apply(11L, 110L,
                Map.of("snapshotId", reuse.get("snapshotId"), "snapshotHash", reuse.get("snapshotHash")));
        verify(repoService, org.mockito.Mockito.never()).create(any(), anyLong(), anyLong());

        Map<String, Object> conflict = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(),
                        "expectedVersion", onboardingService.require(11L, 110L, onboarding.getId()).getVersion(),
                        "changes", Map.of("repositories", List.of(Map.of("name", "server",
                                "url", "https://git/other.git", "defaultBranch", "master")))));
        Map<String, Object> failed = service.apply(11L, 110L,
                Map.of("snapshotId", conflict.get("snapshotId"), "snapshotHash", conflict.get("snapshotHash")));
        assertEquals("FAILED", failed.get("status"));
        assertTrue(String.valueOf(failed.get("failure")).contains("同名仓库地址不一致"));
    }

    @Test
    void duplicateRelationIsTreatedAsAlreadyApplied() {
        when(repoService.createRelation(any(), anyLong(), anyLong()))
                .thenThrow(new BizException(ErrorCode.CONFLICT));
        Map<String, Object> preview = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(), "expectedVersion", expectedVersion(),
                        "changes", Map.of(
                                "repositories", List.of(
                                        Map.of("name", "server", "url", "https://git/s.git",
                                                "defaultBranch", "master"),
                                        Map.of("name", "front", "url", "https://git/f.git",
                                                "defaultBranch", "main")),
                                "relations", List.of(Map.of("from", "front", "to", "server")))));
        Map<String, Object> run = service.apply(11L, 110L,
                Map.of("snapshotId", preview.get("snapshotId"), "snapshotHash", preview.get("snapshotHash")));
        assertEquals("SUCCEEDED", run.get("status"));
    }

    @Test
    void unknownSkillBindingFailsApply() {
        Map<String, Object> preview = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(), "expectedVersion", expectedVersion(),
                        "changes", Map.of("skillBindings", List.of(Map.of("roleCode", "AW_AUTO_FS_DEV",
                                "skillName", "missing-skill")))));
        Map<String, Object> run = service.apply(11L, 110L,
                Map.of("snapshotId", preview.get("snapshotId"), "snapshotHash", preview.get("snapshotHash")));
        assertEquals("FAILED", run.get("status"));
        assertTrue(String.valueOf(run.get("failure")).contains("40009"));
    }

    @Test
    void legacyInstanceCopySuffixIsRecognisedAsOwned() {
        Map<String, Object> objects = new LinkedHashMap<>();
        objects.put("agents", Map.of("AW_AUTO_FS_DEV", 300L));
        objects.put("sdcls", Map.of("AW_AUTO_FS_DEV", 800L));
        objects.put("skills", Map.of());
        objects.put("squad", 500L);
        objects.put("executors", Map.of());
        objects.put("reusedSdcls", List.of("AW_AUTO_FS_DEV"));
        onboardingService.recordDraftSection(onboardingService.require(11L, 110L, onboarding.getId()),
                110L, "objects", objects);
        SdlcVO copy = new SdlcVO();
        copy.setId(800L);
        copy.setName("共享流程（实例 " + onboarding.getId() + "）");
        StepVO step = new StepVO();
        step.setId(900L);
        step.setSdlcId(800L);
        step.setCode("dev_test_prepare");
        step.setInstructionMd("执行 " + token + " 完成测试");
        copy.setSteps(List.of(step));
        when(sdlcService.get(800L)).thenReturn(copy);

        Map<String, Object> preview = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(),
                        "expectedVersion", onboardingService.require(11L, 110L, onboarding.getId()).getVersion(),
                        "changes", Map.of("variables", List.of(Map.of("token", token, "value", "已应用")))));
        Map<String, Object> run = service.apply(11L, 110L,
                Map.of("snapshotId", preview.get("snapshotId"), "snapshotHash", preview.get("snapshotHash")));
        assertEquals("SUCCEEDED", run.get("status"));
        verify(sdlcService, org.mockito.Mockito.never()).create(any(), anyLong(), anyLong());
        verify(sdlcService).updateStep(eq(800L), eq(900L), any(UpdateStepRequest.class), eq(11L), eq(110L));
    }

    @Test
    void appliedValueMissingIsRejected() {
        onboardingService.recordDraftSection(onboardingService.require(11L, 110L, onboarding.getId()),
                110L, "variables", Map.of(token, Map.of("value", "旧值不存在")));
        onboardingService.recordDraftSection(onboardingService.require(11L, 110L, onboarding.getId()),
                110L, "appliedVariables", Map.of(token, Map.of("value", "旧值不存在",
                        "steps", List.of("900"))));
        SdlcVO sdlc = new SdlcVO();
        sdlc.setId(400L);
        StepVO step = new StepVO();
        step.setId(900L);
        step.setSdlcId(400L);
        step.setCode("dev_test_prepare");
        step.setInstructionMd("执行 与变量无关的指令");
        sdlc.setSteps(List.of(step));
        when(sdlcService.get(400L)).thenReturn(sdlc);

        Map<String, Object> preview = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(),
                        "expectedVersion", onboardingService.require(11L, 110L, onboarding.getId()).getVersion(),
                        "changes", Map.of("variables", List.of(Map.of("token", token, "value", "新值")))));
        Map<String, Object> run = service.apply(11L, 110L,
                Map.of("snapshotId", preview.get("snapshotId"), "snapshotHash", preview.get("snapshotHash")));
        assertEquals("FAILED", run.get("status"));
        assertTrue(String.valueOf(run.get("failure")).contains("无法定位"));
    }

    @Test
    void snapshotHashMismatchIsRejected() {
        Map<String, Object> preview = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(), "expectedVersion", expectedVersion(),
                        "changes", Map.of("repositories", List.of())));
        assertEquals(ErrorCode.HARNESS_SNAPSHOT_EXPIRED.getCode(), assertThrows(BizException.class,
                () -> service.apply(11L, 110L, Map.of("snapshotId", preview.get("snapshotId"),
                        "snapshotHash", "bad"))).getCode());
    }

    @Test
    void unknownStepCodeFailsApply() {
        SdlcVO sdlc = new SdlcVO();
        sdlc.setId(400L);
        sdlc.setSteps(List.of());
        when(sdlcService.get(400L)).thenReturn(sdlc);
        Map<String, Object> preview = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(), "expectedVersion", expectedVersion(),
                        "changes", Map.of("sdlcInstructions", List.of(Map.of("roleCode", "AW_AUTO_FS_DEV",
                                "stepCode", "dev_test_prepare", "instructionMd", "x")))));
        Map<String, Object> run = service.apply(11L, 110L,
                Map.of("snapshotId", preview.get("snapshotId"), "snapshotHash", preview.get("snapshotHash")));
        assertEquals("FAILED", run.get("status"));
        assertTrue(String.valueOf(run.get("failure")).contains("SDLC 步骤"));
    }

    @Test
    void appliesRepositoriesWhenOnboardingHasNoObjects() {
        WorkspaceOnboardingService.OnboardingView created = onboardingService.prepare(11L, 111L,
                Map.of("draft", Map.of("templateKey", "software-delivery"))).onboarding();
        WorkspaceOnboardingDO fresh = onboardingService.require(11L, 111L, created.id());
        onboardingService.advanceStage(fresh, 111L, WorkspaceOnboardingService.STAGE_TEMPLATE_INITIALIZED);

        Map<String, Object> preview = service.preview(11L, 111L,
                Map.of("onboardingId", fresh.getId(), "expectedVersion", fresh.getVersion(),
                        "changes", Map.of("repositories", List.of(Map.of("name", "server",
                                "url", "https://git/s.git", "defaultBranch", "master")))));
        Map<String, Object> run = service.apply(11L, 111L,
                Map.of("snapshotId", preview.get("snapshotId"), "snapshotHash", preview.get("snapshotHash")));
        assertEquals("SUCCEEDED", run.get("status"));
        verify(repoService).create(any(), eq(11L), eq(111L));
    }

    @Test
    void toleratesMalformedOwnershipAndAppliedState() {
        Map<String, Object> objects = new LinkedHashMap<>();
        objects.put("agents", Map.of("AW_AUTO_FS_DEV", 300L));
        objects.put("sdcls", Map.of("AW_AUTO_FS_DEV", 400L));
        objects.put("skills", Map.of());
        objects.put("squad", 500L);
        objects.put("executors", Map.of());
        objects.put("reusedSdcls", "AW_AUTO_FS_DEV");
        onboardingService.recordDraftSection(onboardingService.require(11L, 110L, onboarding.getId()),
                110L, "objects", objects);
        onboardingService.recordDraftSection(onboardingService.require(11L, 110L, onboarding.getId()),
                110L, "variables", Map.of(token, Map.of("value", "旧")));
        onboardingService.recordDraftSection(onboardingService.require(11L, 110L, onboarding.getId()),
                110L, "appliedVariables", Map.of(token, Map.of("value", "旧", "steps", "not-a-list")));
        SdlcVO sdlc = new SdlcVO();
        sdlc.setId(400L);
        StepVO step = new StepVO();
        step.setId(900L);
        step.setSdlcId(400L);
        step.setCode("dev_test_prepare");
        step.setInstructionMd("旧 与 旧");
        sdlc.setSteps(List.of(step));
        when(sdlcService.get(400L)).thenReturn(sdlc);

        Map<String, Object> preview = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(),
                        "expectedVersion", onboardingService.require(11L, 110L, onboarding.getId()).getVersion(),
                        "changes", Map.of("variables", List.of(Map.of("token", token, "value", "新")))));
        Map<String, Object> run = service.apply(11L, 110L,
                Map.of("snapshotId", preview.get("snapshotId"), "snapshotHash", preview.get("snapshotHash")));
        assertEquals("SUCCEEDED", run.get("status"));

        assertEquals(ErrorCode.HARNESS_ONBOARDING_NOT_FOUND.getCode(), assertThrows(BizException.class,
                () -> service.get(11L, 999L, Map.of("runId", run.get("runId")))).getCode());
    }

    @Test
    void reusedSdlcThatDisappearedFailsApply() {
        Map<String, Object> objects = new LinkedHashMap<>();
        objects.put("agents", Map.of("AW_AUTO_FS_DEV", 300L));
        objects.put("sdcls", Map.of("AW_AUTO_FS_DEV", 400L));
        objects.put("skills", Map.of());
        objects.put("squad", 500L);
        objects.put("executors", Map.of());
        objects.put("reusedSdcls", List.of("AW_AUTO_FS_DEV"));
        onboardingService.recordDraftSection(onboardingService.require(11L, 110L, onboarding.getId()),
                110L, "objects", objects);
        when(sdlcService.get(400L)).thenReturn(null);

        Map<String, Object> preview = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(),
                        "expectedVersion", onboardingService.require(11L, 110L, onboarding.getId()).getVersion(),
                        "changes", Map.of("variables", List.of(Map.of("token", token, "value", "新")))));
        Map<String, Object> run = service.apply(11L, 110L,
                Map.of("snapshotId", preview.get("snapshotId"), "snapshotHash", preview.get("snapshotHash")));
        assertEquals("FAILED", run.get("status"));
        assertTrue(String.valueOf(run.get("failure")).contains("流程不存在"));
    }

    @Test
    void relationFailureOtherThanDuplicatePropagates() {
        when(repoService.createRelation(any(), anyLong(), anyLong()))
                .thenThrow(new BizException(ErrorCode.SYSTEM_ERROR));
        Map<String, Object> preview = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(), "expectedVersion", expectedVersion(),
                        "changes", Map.of(
                                "repositories", List.of(
                                        Map.of("name", "server", "url", "https://git/s.git",
                                                "defaultBranch", "master"),
                                        Map.of("name", "front", "url", "https://git/f.git",
                                                "defaultBranch", "main")),
                                "relations", List.of(Map.of("from", "front", "to", "server")))));
        Map<String, Object> run = service.apply(11L, 110L,
                Map.of("snapshotId", preview.get("snapshotId"), "snapshotHash", preview.get("snapshotHash")));
        assertEquals("FAILED", run.get("status"));
    }

    @Test
    void malformedVariableAndSdlcEntriesAreIgnored() {
        Map<String, Object> objects = new LinkedHashMap<>();
        objects.put("agents", Map.of("AW_AUTO_FS_DEV", 300L));
        objects.put("sdcls", Map.of("AW_AUTO_FS_DEV", "not-an-id"));
        objects.put("skills", Map.of());
        objects.put("squad", 500L);
        objects.put("executors", Map.of());
        onboardingService.recordDraftSection(onboardingService.require(11L, 110L, onboarding.getId()),
                110L, "objects", objects);
        onboardingService.recordDraftSection(onboardingService.require(11L, 110L, onboarding.getId()),
                110L, "variables", Map.of(token, "raw-value"));

        Map<String, Object> preview = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(),
                        "expectedVersion", onboardingService.require(11L, 110L, onboarding.getId()).getVersion(),
                        "changes", Map.of("variables", List.of(Map.of("token", token, "value", "新")))));
        Map<String, Object> run = service.apply(11L, 110L,
                Map.of("snapshotId", preview.get("snapshotId"), "snapshotHash", preview.get("snapshotHash")));
        assertEquals("SUCCEEDED", run.get("status"));
    }

    private int expectedVersion() {
        return onboardingService.require(11L, 110L, onboarding.getId()).getVersion();
    }

    private String conflictCode(Map<String, Object> changes) {
        Map<String, Object> preview = service.preview(11L, 110L,
                Map.of("onboardingId", onboarding.getId(), "expectedVersion", expectedVersion(),
                        "changes", changes));
        assertNull(preview.get("snapshotId"));
        return String.valueOf(firstConflict(preview).get("code"));
    }

    private Map<String, Object> firstConflict(Map<String, Object> preview) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> conflicts = (List<Map<String, Object>>) preview.get("conflicts");
        return conflicts.get(0);
    }

    /** 小工具：只读草稿 JSON 中的标志位，避免测试直接依赖完整结构。 */
    private static final class JSONObjectView {
        private final com.aliyun.autowonder.json.JSONObject draft;

        private JSONObjectView(String json) {
            this.draft = com.aliyun.autowonder.json.JSON.parseObject(json);
        }

        private boolean flag(String section, String key) {
            com.aliyun.autowonder.json.JSONObject stored = draft.getJSONObject(section);
            return stored != null && Boolean.TRUE.equals(stored.getBoolean(key));
        }

        private boolean containsVariable(String token) {
            com.aliyun.autowonder.json.JSONObject variables = draft.getJSONObject("variables");
            return variables != null && variables.getJSONObject(token) != null;
        }
    }
}
