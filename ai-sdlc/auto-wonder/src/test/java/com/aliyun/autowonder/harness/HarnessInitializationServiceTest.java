package com.aliyun.autowonder.harness;

import com.aliyun.autowonder.agent.AgentService;
import com.aliyun.autowonder.agent.dto.AgentVO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.executor.ExecutorLaunchConfigService;
import com.aliyun.autowonder.executor.ExecutorLaunchOptionsService;
import com.aliyun.autowonder.executor.ExecutorService;
import com.aliyun.autowonder.executor.dto.ExecutorLaunchConfigVO;
import com.aliyun.autowonder.executor.dto.ExecutorVO;
import com.aliyun.autowonder.executor.dto.IssuedExecutorVO;
import com.aliyun.autowonder.executor.dto.ProviderModelCatalogItemVO;
import com.aliyun.autowonder.executor.dto.ProviderModelCatalogVO;
import com.aliyun.autowonder.sdlc.SdlcService;
import com.aliyun.autowonder.sdlc.dto.SdlcVO;
import com.aliyun.autowonder.sdlc.dto.StepVO;
import com.aliyun.autowonder.skill.SkillPackageService;
import com.aliyun.autowonder.skill.SkillService;
import com.aliyun.autowonder.skill.dto.SkillVO;
import com.aliyun.autowonder.squad.SquadDao;
import com.aliyun.autowonder.squad.SquadDO;
import com.aliyun.autowonder.squad.SquadService;
import com.aliyun.autowonder.squad.dto.SquadVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HarnessInitializationServiceTest {

    private static final java.util.Map<Long, String> AGENT_NAMES = java.util.Map.of(
            301L, "全栈开发", 302L, "代码评审工程师", 303L, "测试与部署工程师",
            304L, "需求澄清", 305L, "项目经理");

    private final InMemoryWorkspaceOnboardingDao dao = new InMemoryWorkspaceOnboardingDao();
    private final HarnessTemplateCatalog catalog = new HarnessTemplateCatalog();
    private final WorkspaceOnboardingService onboardingService = new WorkspaceOnboardingService(dao, catalog);
    private final ExecutorLaunchOptionsService launchOptions = mock(ExecutorLaunchOptionsService.class);
    private final HarnessPreviewService previewService =
            new HarnessPreviewService(onboardingService, dao, catalog, launchOptions);
    private final SkillPackageService skillPackageService = mock(SkillPackageService.class);
    private final SkillService skillService = mock(SkillService.class);
    private final SdlcService sdlcService = mock(SdlcService.class);
    private final AgentService agentService = mock(AgentService.class);
    private final SquadService squadService = mock(SquadService.class);
    private final SquadDao squadDao = mock(SquadDao.class);
    private final ExecutorService executorService = mock(ExecutorService.class);
    private final ExecutorLaunchConfigService launchConfigService = mock(ExecutorLaunchConfigService.class);
    private final org.springframework.transaction.PlatformTransactionManager transactionManager =
            mock(org.springframework.transaction.PlatformTransactionManager.class);

    private final HarnessInitializationService service = new HarnessInitializationService(dao, onboardingService,
            previewService, catalog, skillPackageService, skillService, sdlcService, agentService, squadService,
            squadDao, executorService, launchConfigService, transactionManager);

    private WorkspaceOnboardingService.OnboardingView onboarding;
    private long snapshotId;
    private String snapshotHash;

    @BeforeEach
    void setUp() {
        when(launchOptions.requireCreatableClientKind("QODER_CLI")).thenReturn("QODER_CLI");
        when(launchOptions.liveCatalog(9L, "qoder")).thenReturn(catalog());
        when(skillService.list(anyLong(), anyString(), anyInt(), anyInt())).thenReturn(List.of());
        when(sdlcService.list(anyLong(), isNull(), isNull(), isNull(), anyInt(), anyInt())).thenReturn(List.of());
        when(agentService.list(anyLong(), isNull(), isNull(), isNull(), anyInt(), anyInt())).thenReturn(List.of());
        when(executorService.listByAgent(anyLong(), anyLong())).thenReturn(List.of());
        when(squadDao.findByTenantAndName(anyLong(), anyString())).thenReturn(null);
        when(transactionManager.getTransaction(any())).thenReturn(null);

        AtomicLong skillIds = new AtomicLong(100);
        when(skillPackageService.packDirectory(any())).thenReturn(new byte[]{1, 2, 3});
        when(skillPackageService.uploadMcpPackage(anyString(), any(), anyString(), anyString(), anyString(),
                isNull(), any(), anyLong()))
                .thenReturn(new SkillPackageService.UploadedPackage("oss://pkg", "pkg.zip", 3L, "md5",
                        "sha", "SKILL", "skill", "desc"));
        when(skillPackageService.createFromUploadedPackage(anyString(), anyString(), anyString(), anyString(),
                isNull(), isNull(), anyString(), anyLong(), anyLong())).thenAnswer(invocation -> {
                    SkillVO skill = new SkillVO();
                    skill.setId(skillIds.incrementAndGet());
                    skill.setName(invocation.getArgument(2));
                    return skill;
                });
        AtomicLong sdlcIds = new AtomicLong(200);
        when(sdlcService.create(any(), anyLong(), anyLong())).thenAnswer(invocation -> {
            SdlcVO sdlc = new SdlcVO();
            sdlc.setId(sdlcIds.incrementAndGet());
            return sdlc;
        });
        AtomicLong agentIds = new AtomicLong(300);
        when(agentService.create(any(), anyLong(), anyLong())).thenAnswer(invocation -> {
            AgentVO agent = new AgentVO();
            agent.setId(agentIds.incrementAndGet());
            return agent;
        });
        SquadVO squad = new SquadVO();
        squad.setId(500L);
        when(squadService.create(any(), anyLong(), anyLong())).thenReturn(squad);
        AtomicLong executorIds = new AtomicLong(600);
        when(executorService.create(anyLong(), any(), anyLong(), anyLong())).thenAnswer(invocation -> {
            IssuedExecutorVO issued = new IssuedExecutorVO();
            issued.setId(executorIds.incrementAndGet());
            return issued;
        });

        onboarding = onboardingService.prepare(9L, 90L,
                Map.of("draft", Map.of("templateKey", "software-delivery"))).onboarding();
        Map<String, String> values = new java.util.LinkedHashMap<>();
        catalog.require("software-delivery", null, null).variableTokens().forEach(token -> values.put(token, "configured"));
        Map<String, Object> preview = previewService.preview(9L, 90L,
                Map.of("onboardingId", onboarding.id(), "expectedVersion", onboarding.version(),
                        "clientKind", "QODER_CLI", "variableValues", values));
        snapshotId = Long.parseLong(String.valueOf(preview.get("snapshotId")));
        // 既有场景使用历史快照，验证旧批次恢复/重试兼容性。
        HarnessSnapshotDO legacySnapshot = dao.findSnapshot(snapshotId, 9L);
        var legacyContent = previewService.content(legacySnapshot);
        legacyContent.remove("variableValues");
        legacySnapshot.setContentJson(com.aliyun.autowonder.json.JSON.toJSONString(legacyContent));
        snapshotHash = HarnessPreviewService.sha256(legacySnapshot.getContentJson());
        legacySnapshot.setContentSha256(snapshotHash);
        dao.insertSnapshot(legacySnapshot);
        snapshotId = legacySnapshot.getId();
    }

    @Test
    void createsFinalInstructionsFromFrozenValuesAndRecordsRevisionLocations() {
        HarnessTemplate template = catalog.require("software-delivery", null, null);
        Map<String, String> values = new java.util.LinkedHashMap<>();
        template.variableTokens().forEach(token -> values.put(token, "final configuration " + template.variableTokens().indexOf(token)));
        Map<String, Object> preview = previewService.preview(9L, 90L, Map.of("onboardingId", onboarding.id(),
                "clientKind", "QODER_CLI", "variableValues", values));
        AtomicLong stepIds = new AtomicLong(1000);
        when(sdlcService.addStep(anyLong(), any(), anyLong(), anyLong())).thenAnswer(invocation -> {
            StepVO step = new StepVO();
            step.setId(stepIds.incrementAndGet());
            return step;
        });
        // 修改调用方 Map 不得改变已冻结内容。
        String firstToken = template.variableTokens().get(0);
        String frozen = values.get(firstToken);
        values.put(firstToken, "changed after preview");
        Map<String, Object> run = service.initialize(9L, 90L,
                Map.of("snapshotId", preview.get("snapshotId"), "snapshotHash", preview.get("snapshotHash")));
        assertEquals("SUCCEEDED", run.get("status"));
        org.mockito.ArgumentCaptor<com.aliyun.autowonder.sdlc.dto.CreateStepRequest> requests =
                org.mockito.ArgumentCaptor.forClass(com.aliyun.autowonder.sdlc.dto.CreateStepRequest.class);
        verify(sdlcService, org.mockito.Mockito.atLeastOnce()).addStep(anyLong(), requests.capture(), anyLong(), anyLong());
        assertTrue(requests.getAllValues().stream().allMatch(request -> request.getInstructionMd() == null
                || !request.getInstructionMd().contains("{{")));
        assertTrue(requests.getAllValues().stream().anyMatch(request -> request.getInstructionMd() != null
                && request.getInstructionMd().contains(frozen)));
        assertTrue(template.getAgents().stream().filter(agent -> agent.sdlc() != null)
                .flatMap(agent -> agent.sdlc().steps().stream()).anyMatch(step -> step.instruction().contains(firstToken)));
        var draft = onboardingService.draft(onboardingService.require(9L, 90L, onboarding.id()));
        assertEquals(frozen, draft.getJSONObject("variables").getJSONObject(firstToken).getString("value"));
        var location = draft.getJSONObject("appliedVariables").getJSONObject(firstToken);
        assertEquals(frozen, location.getString("value"));
        assertTrue(!location.getJSONArray("steps").isEmpty());
        assertEquals(true, draft.getJSONObject("configApplied").getBoolean("variables"));
    }

    @Test
    void initializesAllObjectsInTemplateOrder() {
        Map<String, Object> run = service.initialize(9L, 90L,
                Map.of("snapshotId", String.valueOf(snapshotId), "snapshotHash", snapshotHash));

        assertEquals("SUCCEEDED", run.get("status"));
        verify(skillPackageService, times(8)).createFromUploadedPackage(anyString(), anyString(), anyString(),
                anyString(), isNull(), isNull(), anyString(), anyLong(), anyLong());
        verify(sdlcService, times(3)).create(any(), anyLong(), anyLong());
        verify(agentService, times(5)).create(any(), anyLong(), anyLong());
        verify(agentService, times(5)).submit(anyLong(), anyLong(), anyLong());
        verify(agentService, times(5)).approve(anyLong(), anyLong(), anyLong(), isNull());
        verify(squadService, times(1)).create(any(), anyLong(), anyLong());
        verify(squadService, times(5)).addMembers(anyLong(), any(), anyLong());
        verify(executorService, times(5)).create(anyLong(), any(), anyLong(), anyLong());

        Map<?, ?> result = (Map<?, ?>) run.get("result");
        assertEquals(8, ((Map<?, ?>) result.get("skills")).size());
        assertEquals(3, ((Map<?, ?>) result.get("sdcls")).size());
        assertEquals(5, ((Map<?, ?>) result.get("agents")).size());
        assertEquals(5, ((Map<?, ?>) result.get("executors")).size());

        WorkspaceOnboardingDO stored = onboardingService.require(9L, 90L, onboarding.id());
        assertEquals(WorkspaceOnboardingService.STAGE_TEMPLATE_INITIALIZED, stored.getStage());
    }

    @Test
    void repeatedConfirmationReturnsSameRunWithoutDuplicates() {
        Map<String, Object> first = service.initialize(9L, 90L,
                Map.of("snapshotId", String.valueOf(snapshotId), "snapshotHash", snapshotHash));
        Map<String, Object> second = service.initialize(9L, 90L,
                Map.of("snapshotId", String.valueOf(snapshotId), "snapshotHash", snapshotHash));
        assertEquals(first.get("runId"), second.get("runId"));
        verify(executorService, times(5)).create(anyLong(), any(), anyLong(), anyLong());
        verify(skillPackageService, times(8)).createFromUploadedPackage(anyString(), anyString(), anyString(),
                anyString(), isNull(), isNull(), anyString(), anyLong(), anyLong());
    }

    @Test
    void failureKeepsCompletedObjectsAndResumeOnlyRetriesMissingOnes() {
        when(sdlcService.create(any(), anyLong(), anyLong()))
                .thenThrow(new BizException(ErrorCode.SYSTEM_ERROR));
        Map<String, Object> failed = service.initialize(9L, 90L,
                Map.of("snapshotId", String.valueOf(snapshotId), "snapshotHash", snapshotHash));
        assertEquals("FAILED", failed.get("status"));
        verify(skillPackageService, times(8)).createFromUploadedPackage(anyString(), anyString(), anyString(),
                anyString(), isNull(), isNull(), anyString(), anyLong(), anyLong());
        verify(executorService, never()).create(anyLong(), any(), anyLong(), anyLong());

        AtomicLong sdlcIds = new AtomicLong(200);
        org.mockito.Mockito.doAnswer(invocation -> {
            SdlcVO sdlc = new SdlcVO();
            sdlc.setId(sdlcIds.incrementAndGet());
            return sdlc;
        }).when(sdlcService).create(any(), anyLong(), anyLong());
        long runId = Long.parseLong(String.valueOf(failed.get("runId")));
        Map<String, Object> resumed = service.resume(9L, 90L, Map.of("runId", String.valueOf(runId)));
        assertEquals("SUCCEEDED", resumed.get("status"));
        // Skills are reused from the failed run; only the missing objects are created.
        verify(skillPackageService, times(8)).createFromUploadedPackage(anyString(), anyString(), anyString(),
                anyString(), isNull(), isNull(), anyString(), anyLong(), anyLong());
        verify(executorService, times(5)).create(anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    void reusesSameNameSdlcOnlyWhenSignatureMatches() {
        HarnessAgent cr = catalog.list().get(0).agent("AW_AUTO_CR");
        when(sdlcService.list(anyLong(), isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(sdlc(401L, cr, false)));

        Map<String, Object> run = service.initialize(9L, 90L,
                Map.of("snapshotId", String.valueOf(snapshotId), "snapshotHash", snapshotHash));

        assertEquals("SUCCEEDED", run.get("status"));
        // CR 的流程签名与模板一致被复用，仅开发与测试两条流程新建。
        verify(sdlcService, times(2)).create(any(), anyLong(), anyLong());
        Map<?, ?> result = (Map<?, ?>) run.get("result");
        assertTrue(((List<?>) result.get("reusedSdcls")).contains("AW_AUTO_CR"));
    }

    @Test
    void conflictingSameNameSdlcFailsWithoutRewritingSharedFlow() {
        HarnessAgent cr = catalog.list().get(0).agent("AW_AUTO_CR");
        when(sdlcService.list(anyLong(), isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(sdlc(401L, cr, true)));

        Map<String, Object> run = service.initialize(9L, 90L,
                Map.of("snapshotId", String.valueOf(snapshotId), "snapshotHash", snapshotHash));

        assertEquals("FAILED", run.get("status"));
        assertTrue(String.valueOf(run.get("failure")).contains("40012"));
        verify(sdlcService, times(1)).create(any(), anyLong(), anyLong());
        verify(sdlcService, never()).updateStep(anyLong(), anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    void reusesExistingSquadByNameInsteadOfCreatingDuplicate() {
        SquadDO existing = new SquadDO();
        existing.setId(500L);
        when(squadDao.findByTenantAndName(anyLong(), anyString())).thenReturn(existing);

        Map<String, Object> run = service.initialize(9L, 90L,
                Map.of("snapshotId", String.valueOf(snapshotId), "snapshotHash", snapshotHash));

        assertEquals("SUCCEEDED", run.get("status"));
        verify(squadService, never()).create(any(), anyLong(), anyLong());
        verify(squadService, times(5)).addMembers(anyLong(), any(), anyLong());
    }

    @Test
    void refusesToReuseSquadOwnedByAnotherMember() {
        SquadDO existing = new SquadDO();
        existing.setId(500L);
        existing.setOwnerId(999L);
        when(squadDao.findByTenantAndName(anyLong(), anyString())).thenReturn(existing);

        Map<String, Object> run = service.initialize(9L, 90L,
                Map.of("snapshotId", String.valueOf(snapshotId), "snapshotHash", snapshotHash));

        assertEquals("FAILED", run.get("status"));
        assertTrue(String.valueOf(run.get("failure")).contains("归属"));
        verify(squadService, never()).create(any(), anyLong(), anyLong());
    }

    @Test
    void conflictsWhenExistingSdlcHasDifferentStepCount() {
        HarnessAgent cr = catalog.list().get(0).agent("AW_AUTO_CR");
        SdlcVO existing = new SdlcVO();
        existing.setId(401L);
        existing.setName(cr.sdlc().name());
        StepVO only = new StepVO();
        only.setId(9001L);
        only.setCode("only");
        only.setStepOrder(1);
        only.setKind("analysis");
        only.setName("唯一");
        only.setInstructionMd("i");
        existing.setSteps(List.of(only));
        when(sdlcService.list(anyLong(), isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(existing));

        Map<String, Object> run = service.initialize(9L, 90L,
                Map.of("snapshotId", String.valueOf(snapshotId), "snapshotHash", snapshotHash));

        assertEquals("FAILED", run.get("status"));
        assertTrue(String.valueOf(run.get("failure")).contains("40012"));
    }

    private SdlcVO sdlc(long id, HarnessAgent agent, boolean differentInstruction) {
        SdlcVO sdlc = new SdlcVO();
        sdlc.setId(id);
        sdlc.setName(agent.sdlc().name());
        List<StepVO> steps = new java.util.ArrayList<>();
        for (HarnessSdlcStep expected : agent.sdlc().steps()) {
            StepVO step = new StepVO();
            step.setId(id * 10 + expected.order());
            step.setCode(expected.code());
            step.setStepOrder(expected.order());
            step.setName(expected.name());
            step.setKind(expected.kind());
            step.setInstructionMd(differentInstruction ? "另一条共享流程的指令" : expected.instruction());
            step.setHandlerType(expected.handlerType());
            step.setHandlerRoleRef(expected.handlerRoleRef());
            steps.add(step);
        }
        sdlc.setSteps(steps);
        return sdlc;
    }

    @Test
    void reusesAgentWhenRoleAndSdlcMatch() {
        HarnessAgent fsDev = catalog.list().get(0).agent("AW_AUTO_FS_DEV");
        when(sdlcService.list(anyLong(), isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(sdlc(401L, fsDev, false)));
        AgentVO existing = new AgentVO();
        existing.setId(301L);
        existing.setRoleCode("AW_AUTO_FS_DEV");
        existing.setSdlcId(401L);
        when(agentService.list(anyLong(), isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(existing));
        // 已有 Agent 复用后不会再次创建
        when(agentService.create(any(), anyLong(), anyLong())).thenAnswer(invocation -> {
            AgentVO agent = new AgentVO();
            agent.setId(310L);
            return agent;
        });

        Map<String, Object> run = service.initialize(9L, 90L,
                Map.of("snapshotId", String.valueOf(snapshotId), "snapshotHash", snapshotHash));

        assertEquals("SUCCEEDED", run.get("status"));
        verify(agentService, times(4)).create(any(), anyLong(), anyLong());
        Map<?, ?> result = (Map<?, ?>) run.get("result");
        assertTrue(((List<?>) result.get("reusedSdcls")).contains("AW_AUTO_FS_DEV"));
    }

    @Test
    void conflictsWhenExistingAgentBindsAnotherSdlc() {
        AgentVO existing = new AgentVO();
        existing.setId(301L);
        existing.setRoleCode("AW_AUTO_FS_DEV");
        existing.setSdlcId(999L);
        when(agentService.list(anyLong(), isNull(), isNull(), isNull(), anyInt(), anyInt()))
                .thenReturn(List.of(existing));

        Map<String, Object> run = service.initialize(9L, 90L,
                Map.of("snapshotId", String.valueOf(snapshotId), "snapshotHash", snapshotHash));

        assertEquals("FAILED", run.get("status"));
        assertTrue(String.valueOf(run.get("failure")).contains("SDLC 绑定不一致"));
    }

    @Test
    void reusesExecutorWhenNameAndConfigMatch() {
        when(executorService.listByAgent(anyLong(), anyLong())).thenAnswer(invocation -> {
            long agentId = ((Number) invocation.getArgument(0)).longValue();
            ExecutorVO executor = new ExecutorVO();
            executor.setId(agentId);
            executor.setAgentId(agentId);
            executor.setName(AGENT_NAMES.get(agentId) + "默认执行器");
            executor.setClientKind("QODER_CLI");
            return List.of(executor);
        });
        when(launchConfigService.readStoredConfig(anyLong(), anyLong())).thenAnswer(invocation -> {
            long agentId = ((Number) invocation.getArgument(0)).longValue();
            ExecutorLaunchConfigVO config = new ExecutorLaunchConfigVO();
            config.setModel(agentId == 302L ? "gfmodel" : "dfmodel");
            config.setContextWindow("1000000");
            config.setReasoningEffort("medium");
            config.setMemoryMode("platform");
            config.setMaxConcurrentDispatches(10);
            return config;
        });

        Map<String, Object> run = service.initialize(9L, 90L,
                Map.of("snapshotId", String.valueOf(snapshotId), "snapshotHash", snapshotHash));

        assertEquals("SUCCEEDED", run.get("status"));
        verify(executorService, never()).create(anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    void conflictsWhenExistingExecutorConfigDiffers() {
        when(executorService.listByAgent(anyLong(), anyLong())).thenAnswer(invocation -> {
            long agentId = ((Number) invocation.getArgument(0)).longValue();
            ExecutorVO executor = new ExecutorVO();
            executor.setId(600L);
            executor.setAgentId(agentId);
            executor.setName(AGENT_NAMES.get(agentId) + "默认执行器");
            executor.setClientKind("QODER_CLI");
            return List.of(executor);
        });
        when(launchConfigService.readStoredConfig(anyLong(), anyLong())).thenAnswer(invocation -> {
            ExecutorLaunchConfigVO config = new ExecutorLaunchConfigVO();
            config.setModel("different-model");
            config.setContextWindow("1000000");
            config.setReasoningEffort("medium");
            config.setMemoryMode("platform");
            config.setMaxConcurrentDispatches(10);
            return config;
        });

        Map<String, Object> run = service.initialize(9L, 90L,
                Map.of("snapshotId", String.valueOf(snapshotId), "snapshotHash", snapshotHash));

        assertEquals("FAILED", run.get("status"));
        assertTrue(String.valueOf(run.get("failure")).contains("40007"));
    }

    @Test
    void reusesSkillWhenPackageDigestMatches() throws Exception {
        byte[] archive = new byte[]{1, 2, 3};
        String md5 = java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("MD5").digest(archive));
        SkillVO existing = new SkillVO();
        existing.setId(120L);
        existing.setName(catalog.list().get(0).getSkills().get(0).name());
        existing.setPackageMd5(md5);
        when(skillService.list(anyLong(), anyString(), anyInt(), anyInt())).thenReturn(List.of(existing));

        Map<String, Object> run = service.initialize(9L, 90L,
                Map.of("snapshotId", String.valueOf(snapshotId), "snapshotHash", snapshotHash));

        assertEquals("SUCCEEDED", run.get("status"));
        verify(skillPackageService, times(7)).createFromUploadedPackage(anyString(), anyString(), anyString(),
                anyString(), isNull(), isNull(), anyString(), anyLong(), anyLong());
        assertTrue(((List<?>) ((Map<?, ?>) run.get("result")).get("reusedSkills")).contains("brainstorming"));
    }

    @Test
    void conflictsWhenSkillDigestDiffers() {
        SkillVO existing = new SkillVO();
        existing.setId(120L);
        existing.setName(catalog.list().get(0).getSkills().get(0).name());
        existing.setPackageMd5("other-digest");
        when(skillService.list(anyLong(), anyString(), anyInt(), anyInt())).thenReturn(List.of(existing));

        Map<String, Object> run = service.initialize(9L, 90L,
                Map.of("snapshotId", String.valueOf(snapshotId), "snapshotHash", snapshotHash));

        assertEquals("FAILED", run.get("status"));
        assertTrue(String.valueOf(run.get("failure")).contains("40012"));
    }

    @Test
    void reportsMissingRunAndSnapshotErrors() {
        assertEquals(ErrorCode.NOT_FOUND.getCode(), assertThrows(BizException.class,
                () -> service.initialize(9L, 90L, Map.of("snapshotId", "987654", "snapshotHash", "h"))).getCode());
        assertEquals(ErrorCode.HARNESS_SNAPSHOT_EXPIRED.getCode(), assertThrows(BizException.class,
                () -> service.initialize(9L, 90L,
                        Map.of("snapshotId", String.valueOf(snapshotId), "snapshotHash", "bad"))).getCode());
        assertEquals(ErrorCode.NOT_FOUND.getCode(), assertThrows(BizException.class,
                () -> service.get(9L, 90L, Map.of("runId", "987654"))).getCode());
    }

    @Test
    void resumeSucceededRunReturnsWithoutNewObjects() {
        Map<String, Object> run = service.initialize(9L, 90L,
                Map.of("snapshotId", String.valueOf(snapshotId), "snapshotHash", snapshotHash));
        Map<String, Object> resumed = service.resume(9L, 90L, Map.of("runId", run.get("runId")));
        assertEquals("SUCCEEDED", resumed.get("status"));
        verify(executorService, times(5)).create(anyLong(), any(), anyLong(), anyLong());
        verify(sdlcService, times(3)).create(any(), anyLong(), anyLong());
    }

    @Test
    void runStateIsReadable() {
        Map<String, Object> run = service.initialize(9L, 90L,
                Map.of("snapshotId", String.valueOf(snapshotId), "snapshotHash", snapshotHash));
        Map<String, Object> read = service.get(9L, 90L, Map.of("runId", run.get("runId")));
        assertEquals("SUCCEEDED", read.get("status"));
        assertTrue(((List<?>) read.get("steps")).size() >= 33);
    }

    private ProviderModelCatalogVO catalog() {
        ProviderModelCatalogVO vo = new ProviderModelCatalogVO();
        vo.setProvider("qoder");
        vo.setLastSuccessfulAt(new Date());
        vo.setModels(List.of(
                new ProviderModelCatalogItemVO("gmodel", "GLM-5.3"),
                new ProviderModelCatalogItemVO("gfmodel", "GLM-5.3-Flash"),
                new ProviderModelCatalogItemVO("dfmodel", "DeepSeek-V4-Flash")));
        return vo;
    }
}
