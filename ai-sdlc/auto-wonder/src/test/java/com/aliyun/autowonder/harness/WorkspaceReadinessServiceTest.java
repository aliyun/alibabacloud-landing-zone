package com.aliyun.autowonder.harness;

import com.aliyun.autowonder.agent.AgentRepoPermDao;
import com.aliyun.autowonder.agent.AgentRepoPermDO;
import com.aliyun.autowonder.agent.AgentService;
import com.aliyun.autowonder.agent.dto.AgentVO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.executor.ExecutorService;
import com.aliyun.autowonder.executor.dto.ExecutorVO;
import com.aliyun.autowonder.skill.SkillService;
import com.aliyun.autowonder.skill.dto.SkillVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspaceReadinessServiceTest {

    private static final Map<String, Long> AGENT_IDS = Map.of(
            "AW_AUTO_FS_DEV", 300L, "AW_AUTO_CR", 301L, "AW_AUTO_QA", 302L,
            "AW_AUTO_REQ_CLARIFIER", 303L, "AW_AUTO_PM", 304L);
    private static final Map<String, String> AGENT_NAMES = Map.of(
            "AW_AUTO_FS_DEV", "全栈开发", "AW_AUTO_CR", "代码评审工程师", "AW_AUTO_QA", "测试与部署工程师",
            "AW_AUTO_REQ_CLARIFIER", "需求澄清", "AW_AUTO_PM", "项目经理");

    private final InMemoryWorkspaceOnboardingDao dao = new InMemoryWorkspaceOnboardingDao();
    private final HarnessTemplateCatalog catalog = new HarnessTemplateCatalog();
    private final WorkspaceOnboardingService onboardingService = new WorkspaceOnboardingService(dao, catalog);
    private final ExecutorService executorService = mock(ExecutorService.class);
    private final AgentService agentService = mock(AgentService.class);
    private final AgentRepoPermDao repoPermDao = mock(AgentRepoPermDao.class);
    private final SkillService skillService = mock(SkillService.class);

    private final WorkspaceReadinessService service = new WorkspaceReadinessService(dao, onboardingService,
            catalog, executorService, agentService, repoPermDao, skillService);

    private WorkspaceOnboardingDO onboarding;

    @BeforeEach
    void setUp() {
        WorkspaceOnboardingService.OnboardingView created = onboardingService.prepare(13L, 130L,
                Map.of("draft", Map.of("templateKey", "software-delivery"))).onboarding();
        onboarding = onboardingService.require(13L, 130L, created.id());
        onboardingService.advanceStage(onboarding, 130L, WorkspaceOnboardingService.STAGE_TEMPLATE_INITIALIZED);
        Map<String, Object> objects = new LinkedHashMap<>();
        objects.put("agents", AGENT_IDS);
        objects.put("sdcls", Map.of("AW_AUTO_FS_DEV", 400L, "AW_AUTO_CR", 401L, "AW_AUTO_QA", 402L));
        objects.put("skills", Map.of());
        objects.put("squad", 500L);
        objects.put("executors", Map.of());
        onboardingService.recordDraftSection(onboarding, 130L, "objects", objects);
        onboardingService.recordDraftSection(onboarding, 130L, "repositories", Map.of("server", 700L));

        Map<String, Object> variables = new LinkedHashMap<>();
        for (HarnessVariable variable : catalog.list().get(0).getVariables()) {
            variables.put(variable.token(), Map.of("notApplicable", true, "reason", "本示例不适用"));
        }
        onboardingService.recordDraftSection(onboarding, 130L, "variables", variables);
        onboardingService.recordDraftSection(onboarding, 130L, "evidence",
                Map.of("gitAccess", true, "runtimeEnvironment", true));

        when(skillService.list(anyLong(), anyString(), anyInt(), anyInt())).thenReturn(
                catalog.list().get(0).getSkills().stream().map(skill -> {
                    SkillVO vo = new SkillVO();
                    vo.setName(skill.name());
                    return vo;
                }).toList());
        when(agentService.get(anyLong(), anyLong())).thenAnswer(invocation -> {
            AgentVO agent = new AgentVO();
            agent.setId(((Number) invocation.getArgument(0)).longValue());
            agent.setOnlineVersionId(((Number) invocation.getArgument(0)).longValue());
            agent.setStatus("ONLINE");
            return agent;
        });
        when(repoPermDao.listByVersion(anyLong())).thenAnswer(invocation -> {
            long agentId = ((Number) invocation.getArgument(0)).longValue();
            AgentRepoPermDO perm = new AgentRepoPermDO();
            perm.setRepoId(700L);
            perm.setPermLevel(agentId == 300L || agentId == 302L ? "WRITE" : "READ");
            return List.of(perm);
        });
        when(executorService.listByAgent(anyLong(), anyLong())).thenAnswer(invocation -> {
            long agentId = ((Number) invocation.getArgument(0)).longValue();
            String name = AGENT_IDS.entrySet().stream()
                    .filter(entry -> entry.getValue() == agentId)
                    .map(entry -> AGENT_NAMES.get(entry.getKey()) + "默认执行器")
                    .findFirst().orElse("unknown默认执行器");
            ExecutorVO executor = new ExecutorVO();
            executor.setAgentId(agentId);
            executor.setName(name);
            executor.setStatus("ONLINE");
            return List.of(executor);
        });
    }

    @Test
    void passesWhenEverythingIsReal() {
        Map<String, Object> check = service.check(13L, 130L,
                Map.of("onboardingId", onboarding.getId()));
        assertEquals(WorkspaceReadinessService.PASS, check.get("status"));
        assertEquals(6, ((List<?>) check.get("checks")).size());
        assertEquals(List.of(), check.get("gaps"));
        // 只读检查不推进阶段、不标记步骤。
        assertEquals(WorkspaceOnboardingService.STAGE_TEMPLATE_INITIALIZED,
                onboardingService.require(13L, 130L, onboarding.getId()).getStage());

        Map<String, Object> confirmed = service.confirm(13L, 130L, onboarding.getId(),
                String.valueOf(check.get("checkId")));
        assertEquals(WorkspaceOnboardingService.STAGE_READY, confirmed.get("stage"));
        WorkspaceOnboardingDO stored = onboardingService.require(13L, 130L, onboarding.getId());
        assertEquals(WorkspaceOnboardingService.STAGE_READY, stored.getStage());
    }

    @Test
    void offlineExecutorBlocksReadinessAndConfirmation() {
        when(executorService.listByAgent(anyLong(), anyLong())).thenAnswer(invocation -> {
            ExecutorVO executor = new ExecutorVO();
            executor.setName("全栈开发默认执行器");
            executor.setStatus("OFFLINE");
            return List.of(executor);
        });
        Map<String, Object> check = service.check(13L, 130L, Map.of("onboardingId", onboarding.getId()));
        assertEquals(WorkspaceReadinessService.BLOCKED, check.get("status"));
        assertTrue(String.valueOf(check.get("gaps")).contains("默认执行器"));
        BizException blocked = assertThrows(BizException.class, () -> service.confirm(13L, 130L,
                onboarding.getId(), String.valueOf(check.get("checkId"))));
        assertEquals(ErrorCode.HARNESS_READINESS_BLOCKED.getCode(), blocked.getCode());
        assertEquals(WorkspaceOnboardingService.STAGE_TEMPLATE_INITIALIZED,
                onboardingService.require(13L, 130L, onboarding.getId()).getStage());
    }

    @Test
    void missingEvidenceStaysUnknownAndNeverPasses() {
        onboardingService.recordDraftSection(onboardingService.require(13L, 130L, onboarding.getId()),
                130L, "evidence", Map.of("gitAccess", true));
        Map<String, Object> check = service.check(13L, 130L, Map.of("onboardingId", onboarding.getId()));
        assertEquals(WorkspaceReadinessService.UNKNOWN, check.get("status"));
    }

    @Test
    void unresolvedVariableBlocksReadiness() {
        onboardingService.recordDraftSection(onboardingService.require(13L, 130L, onboarding.getId()),
                130L, "variables", Map.of());
        Map<String, Object> check = service.check(13L, 130L, Map.of("onboardingId", onboarding.getId()));
        assertEquals(WorkspaceReadinessService.BLOCKED, check.get("status"));
    }

    @Test
    void blocksWhenTemplateIsNotInitialized() {
        WorkspaceOnboardingDO draft = onboardingService.require(13L, 130L, onboardingService.prepare(13L, 130L,
                Map.of("draft", Map.of("templateKey", "software-delivery"))).onboarding().id());
        Map<String, Object> check = service.check(13L, 130L, Map.of("onboardingId", draft.getId()));
        assertEquals(WorkspaceReadinessService.BLOCKED, check.get("status"));
        assertTrue(String.valueOf(check.get("gaps")).contains("尚未填写"));
        assertEquals(WorkspaceOnboardingService.STAGE_DRAFT,
                onboardingService.require(13L, 130L, draft.getId()).getStage());
    }

    @Test
    void blocksWhenSkillIsMissing() {
        when(skillService.list(anyLong(), anyString(), anyInt(), anyInt())).thenReturn(List.of());
        Map<String, Object> check = service.check(13L, 130L, Map.of("onboardingId", onboarding.getId()));
        assertEquals(WorkspaceReadinessService.BLOCKED, check.get("status"));
        assertTrue(String.valueOf(check.get("gaps")).contains("Skill"));
    }

    @Test
    void blocksWhenNoRepositoryIsRegistered() {
        onboardingService.recordDraftSection(onboardingService.require(13L, 130L, onboarding.getId()),
                130L, "repositories", Map.of());
        Map<String, Object> check = service.check(13L, 130L, Map.of("onboardingId", onboarding.getId()));
        assertEquals(WorkspaceReadinessService.BLOCKED, check.get("status"));
        assertTrue(String.valueOf(check.get("gaps")).contains("尚未登记项目仓库"));
    }

    @Test
    void blocksWhenAgentsAreMissingOrUnpublished() {
        onboardingService.recordDraftSection(onboardingService.require(13L, 130L, onboarding.getId()),
                130L, "objects", Map.of("agents", Map.of(), "sdcls", Map.of(), "skills", Map.of(),
                        "squad", 500L, "executors", Map.of()));
        Map<String, Object> missing = service.check(13L, 130L, Map.of("onboardingId", onboarding.getId()));
        assertEquals(WorkspaceReadinessService.BLOCKED, missing.get("status"));
        assertTrue(String.valueOf(missing.get("gaps")).contains("尚未创建"));

        when(agentService.get(anyLong(), anyLong())).thenAnswer(invocation -> {
            AgentVO agent = new AgentVO();
            agent.setId(((Number) invocation.getArgument(0)).longValue());
            agent.setOnlineVersionId(null);
            agent.setStatus("DRAFT");
            return agent;
        });
        onboardingService.recordDraftSection(onboardingService.require(13L, 130L, onboarding.getId()),
                130L, "objects", Map.of("agents", AGENT_IDS, "sdcls", Map.of(), "skills", Map.of(),
                        "squad", 500L, "executors", Map.of()));
        Map<String, Object> unpublished = service.check(13L, 130L, Map.of("onboardingId", onboarding.getId()));
        assertEquals(WorkspaceReadinessService.BLOCKED, unpublished.get("status"));
        assertTrue(String.valueOf(unpublished.get("gaps")).contains("尚未发布在线版本"));
    }

    @Test
    void blocksWhenRepositoryPermissionIsMissingOrMismatched() {
        when(repoPermDao.listByVersion(anyLong())).thenReturn(List.of());
        Map<String, Object> missing = service.check(13L, 130L, Map.of("onboardingId", onboarding.getId()));
        assertEquals(WorkspaceReadinessService.BLOCKED, missing.get("status"));
        assertTrue(String.valueOf(missing.get("gaps")).contains("缺少"));

        when(repoPermDao.listByVersion(anyLong())).thenAnswer(invocation -> {
            AgentRepoPermDO perm = new AgentRepoPermDO();
            perm.setRepoId(700L);
            perm.setPermLevel("READ");
            return List.of(perm);
        });
        Map<String, Object> mismatched = service.check(13L, 130L, Map.of("onboardingId", onboarding.getId()));
        assertEquals(WorkspaceReadinessService.BLOCKED, mismatched.get("status"));
        assertTrue(String.valueOf(mismatched.get("gaps")).contains("WRITE"));
    }

    @Test
    void blocksWhenVariableHasPlaceholderOrMissingReason() {
        String token = catalog.list().get(0).getVariables().get(0).token();
        Map<String, Object> variables = new LinkedHashMap<>();
        for (HarnessVariable variable : catalog.list().get(0).getVariables()) {
            variables.put(variable.token(), Map.of("notApplicable", true, "reason", "不适用"));
        }
        variables.put(token, Map.of("value", "{{still}}"));
        onboardingService.recordDraftSection(onboardingService.require(13L, 130L, onboarding.getId()),
                130L, "variables", variables);
        Map<String, Object> unresolved = service.check(13L, 130L, Map.of("onboardingId", onboarding.getId()));
        assertEquals(WorkspaceReadinessService.BLOCKED, unresolved.get("status"));
        assertTrue(String.valueOf(unresolved.get("gaps")).contains("占位符"));

        variables.put(token, Map.of("notApplicable", true));
        onboardingService.recordDraftSection(onboardingService.require(13L, 130L, onboarding.getId()),
                130L, "variables", variables);
        Map<String, Object> noReason = service.check(13L, 130L, Map.of("onboardingId", onboarding.getId()));
        assertEquals(WorkspaceReadinessService.BLOCKED, noReason.get("status"));
        assertTrue(String.valueOf(noReason.get("gaps")).contains("不适用缺少理由"));
    }

    @Test
    void acceptsStructuredEvidenceForm() {
        onboardingService.recordDraftSection(onboardingService.require(13L, 130L, onboarding.getId()),
                130L, "evidence", Map.of("gitAccess", Map.of("valid", true),
                        "runtimeEnvironment", Map.of("valid", true)));
        Map<String, Object> check = service.check(13L, 130L, Map.of("onboardingId", onboarding.getId()));
        assertEquals(WorkspaceReadinessService.PASS, check.get("status"));
    }

    @Test
    void checkIdIsStableAndConfirmWritesAudit() {
        InMemoryWorkspaceOnboardingDao spyDao = org.mockito.Mockito.spy(dao);
        WorkspaceReadinessService spyService = new WorkspaceReadinessService(spyDao, onboardingService,
                catalog, executorService, agentService, repoPermDao, skillService);
        Map<String, Object> first = spyService.check(13L, 130L, Map.of("onboardingId", onboarding.getId()));
        Map<String, Object> second = spyService.check(13L, 130L, Map.of("onboardingId", onboarding.getId()));
        assertEquals(first.get("checkId"), second.get("checkId"));
        org.mockito.Mockito.verify(spyDao, org.mockito.Mockito.never())
                .insertCheck(any(WorkspaceReadinessCheckDO.class));

        spyService.confirm(13L, 130L, onboarding.getId(), String.valueOf(first.get("checkId")));
        org.mockito.Mockito.verify(spyDao).insertCheck(any(WorkspaceReadinessCheckDO.class));
        assertEquals(WorkspaceOnboardingService.STAGE_READY,
                onboardingService.require(13L, 130L, onboarding.getId()).getStage());
    }

    @Test
    void treatsNonNumericRepositoryIdsAndLooseEvidenceAsUnknown() {
        onboardingService.recordDraftSection(onboardingService.require(13L, 130L, onboarding.getId()),
                130L, "repositories", Map.of("server", "unknown"));
        onboardingService.recordDraftSection(onboardingService.require(13L, 130L, onboarding.getId()),
                130L, "evidence", Map.of("gitAccess", "yes", "runtimeEnvironment", true));
        Map<String, Object> check = service.check(13L, 130L, Map.of("onboardingId", onboarding.getId()));
        assertEquals(WorkspaceReadinessService.UNKNOWN, check.get("status"));
    }

    @Test
    void blocksWhenOnboardingHasNoObjectsOrVariables() {
        WorkspaceOnboardingDO fresh = onboardingService.require(13L, 130L, onboardingService.prepare(13L, 130L,
                Map.of("draft", Map.of("templateKey", "software-delivery"))).onboarding().id());
        onboardingService.advanceStage(fresh, 130L, WorkspaceOnboardingService.STAGE_TEMPLATE_INITIALIZED);
        Map<String, Object> check = service.check(13L, 130L, Map.of("onboardingId", fresh.getId()));
        assertEquals(WorkspaceReadinessService.BLOCKED, check.get("status"));
        assertTrue(String.valueOf(check.get("gaps")).contains("尚未填写"));
    }

    @Test
    void evidenceMapWithoutValidFlagStaysUnknown() {
        onboardingService.recordDraftSection(onboardingService.require(13L, 130L, onboarding.getId()),
                130L, "evidence", Map.of("gitAccess", Map.of("source", "manual"),
                        "runtimeEnvironment", true));
        Map<String, Object> check = service.check(13L, 130L, Map.of("onboardingId", onboarding.getId()));
        assertEquals(WorkspaceReadinessService.UNKNOWN, check.get("status"));
    }

    @Test
    void staleOrForeignCheckIdCannotConfirm() {
        service.check(13L, 130L, Map.of("onboardingId", onboarding.getId()));
        BizException stale = assertThrows(BizException.class, () -> service.confirm(13L, 130L,
                onboarding.getId(), "not-a-current-check"));
        assertEquals(ErrorCode.HARNESS_ONBOARDING_VERSION_CONFLICT.getCode(), stale.getCode());
        assertEquals(WorkspaceOnboardingService.STAGE_TEMPLATE_INITIALIZED,
                onboardingService.require(13L, 130L, onboarding.getId()).getStage());
    }
}
