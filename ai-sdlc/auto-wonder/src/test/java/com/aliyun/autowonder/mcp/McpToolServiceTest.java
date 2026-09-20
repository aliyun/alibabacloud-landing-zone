package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.dispatch.RuntimeTraceService;
import com.aliyun.autowonder.dispatch.RuntimeTraceArtifactService;
import com.aliyun.autowonder.dispatch.dto.RuntimeTraceVO;
import com.aliyun.autowonder.dispatch.dto.RuntimeActivityTimelineVO;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.branding.PlatformBrandingDao;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.mcp.dto.WorkitemCliUploadTokenVO;
import com.aliyun.autowonder.mcp.dto.WorkitemCliDownloadTokenVO;
import com.aliyun.autowonder.storage.InMemoryObjectStorage;
import com.aliyun.autowonder.storage.OssProperties;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.agent.AgentService;
import com.aliyun.autowonder.agent.dto.AgentVO;
import com.aliyun.autowonder.agent.dto.AgentVersionSummaryVO;
import com.aliyun.autowonder.agent.dto.AgentVersionVO;
import com.aliyun.autowonder.agent.dto.UpdateConfigRequest;
import com.aliyun.autowonder.squad.dto.CreateSquadRequest;
import com.aliyun.autowonder.artifact.RequirementDocumentService;
import com.aliyun.autowonder.artifact.ArtifactOwnerRef;
import com.aliyun.autowonder.artifact.dto.ArtifactVO;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchPauseService;
import com.aliyun.autowonder.executor.ExecutorLaunchCommandService;
import com.aliyun.autowonder.executor.ExecutorLaunchConfigService;
import com.aliyun.autowonder.executor.ExecutorLaunchOptionsService;
import com.aliyun.autowonder.executor.ExecutorService;
import com.aliyun.autowonder.executor.ProviderModelCatalogService;
import com.aliyun.autowonder.executor.dto.CreateExecutorRequest;
import com.aliyun.autowonder.executor.dto.CreatedExecutorVO;
import com.aliyun.autowonder.executor.dto.ExecutorLaunchCommandVO;
import com.aliyun.autowonder.executor.dto.ExecutorLaunchConfigVO;
import com.aliyun.autowonder.executor.dto.ExecutorLaunchOptionsVO;
import com.aliyun.autowonder.executor.dto.ExecutorVO;
import com.aliyun.autowonder.executor.dto.IssuedExecutorVO;
import com.aliyun.autowonder.executor.dto.ProviderModelCatalogItemVO;
import com.aliyun.autowonder.executor.dto.ProviderModelCatalogVO;
import com.aliyun.autowonder.executor.dto.SelectOptionVO;
import com.aliyun.autowonder.executor.dto.UpdateExecutorLaunchConfigRequest;
import com.aliyun.autowonder.mcp.dto.McpToolVO;
import com.aliyun.autowonder.memory.MemoryService;
import com.aliyun.autowonder.memory.dto.CreateMemoryRequest;
import com.aliyun.autowonder.memory.dto.MemoryVO;
import com.aliyun.autowonder.memory.dto.UpdateMemoryRequest;
import com.aliyun.autowonder.workspace.WorkspaceService;
import com.aliyun.autowonder.workspace.dto.WorkspaceVO;
import com.aliyun.autowonder.repo.RepoService;
import com.aliyun.autowonder.repo.dto.CreateRelationRequest;
import com.aliyun.autowonder.repo.dto.CreateRepoRequest;
import com.aliyun.autowonder.repo.dto.UpdateRepoRequest;
import com.aliyun.autowonder.repo.dto.RepoRelationVO;
import com.aliyun.autowonder.repo.dto.RepoVO;
import com.aliyun.autowonder.sdlc.SdlcService;
import com.aliyun.autowonder.sdlc.dto.SdlcVO;
import com.aliyun.autowonder.sdlc.dto.StepVO;
import com.aliyun.autowonder.category.CategoryService;
import com.aliyun.autowonder.category.dto.BatchSkillCategoryResultVO;
import com.aliyun.autowonder.category.dto.CategoryVO;
import com.aliyun.autowonder.squad.SquadService;
import com.aliyun.autowonder.squad.dto.SquadVO;
import com.aliyun.autowonder.skill.SkillPackageService;
import com.aliyun.autowonder.skill.SkillService;
import com.aliyun.autowonder.skill.dto.SkillPackageInspectVO;
import com.aliyun.autowonder.skill.dto.SkillVO;
import com.aliyun.autowonder.statemachine.StatusTemplateService;
import com.aliyun.autowonder.workitem.WorkitemService;
import com.aliyun.autowonder.workitem.AssignmentActor;
import com.aliyun.autowonder.workitem.dto.CommentVO;
import com.aliyun.autowonder.workitem.dto.CreateWorkitemRequest;
import com.aliyun.autowonder.configuration.JacksonConfig;
import com.aliyun.autowonder.workitem.dto.WorkitemVO;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunCommentService;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskService;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDao;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDO;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunService;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskTriggerService;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunOrchestrator;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskRunDispatchControlService;
import com.aliyun.autowonder.scheduledtask.dto.CreateScheduledTaskRequest;
import com.aliyun.autowonder.scheduledtask.dto.UpdateScheduledTaskRequest;
import com.aliyun.autowonder.scheduledtask.dto.ScheduledTaskVO;
import com.aliyun.autowonder.artifact.ArtifactService;
import com.aliyun.autowonder.dispatch.ExecutionSourceType;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityGuard;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpToolServiceTest {
    private RuntimeTraceService traceService;
    private RuntimeTraceArtifactService traceArtifacts;
    private static final List<String> TRACE_TOOLS = List.of(
            "autowonder.get_dispatch_runtime_trace", "autowonder.get_dispatch_activities",
            "autowonder.get_dispatch_turn", "autowonder.get_dispatch_observation");
    private static final long WORKSPACE_ID = 100L;
    private static final long USER_ID = 7L;

    WorkspaceService workspaceService;
    WorkitemService workitemService;
    GuidanceService guidanceService;
    SkillService skillService;
    SkillPackageService skillPackageService;
    SdlcService sdlcService;
    AgentService agentService;
    StatusTemplateService statusTemplateService;
    DispatchDao dispatchDao;
    RequirementDocumentService requirementDocumentService;
    WorkitemCliUploadTokenService workitemCliUploadTokenService;
    WorkitemCliDownloadTokenService workitemCliDownloadTokenService;
    MemoryService memoryService;
    RepoService repoService;
    SquadService squadService;
    DispatchPauseService dispatchPauseService;
    CategoryService categoryService;
    ExecutorService executorService;
    ProviderModelCatalogService providerModelCatalogService;
    ExecutorLaunchConfigService executorLaunchConfigService;
    PlatformBrandingService launchBrandingService;
    McpToolService service;
    McpAccessTokenService.Principal principal;
    ScheduledTaskCapabilityGuard capabilityGuard;

    @BeforeEach
    void setUp() {
        workspaceService = mock(WorkspaceService.class);
        workitemService = mock(WorkitemService.class);
        guidanceService = mock(GuidanceService.class);
        skillService = mock(SkillService.class);
        skillPackageService = mock(SkillPackageService.class);
        sdlcService = mock(SdlcService.class);
        agentService = mock(AgentService.class);
        statusTemplateService = mock(StatusTemplateService.class);
        dispatchDao = mock(DispatchDao.class);
        requirementDocumentService = mock(RequirementDocumentService.class);
        workitemCliUploadTokenService = mock(WorkitemCliUploadTokenService.class);
        when(workitemCliUploadTokenService.commandTemplate()).thenReturn(
                "npx -y autowonder@0.2.130 workitem upload --server-url https://daily.auto-wonder.example.com"
                        + " --workitem-id <workitem-id>"
                        + " --file <filepath-1> --file <filepath-2> --file <images-1> --json");
        when(workitemCliUploadTokenService.tokenEnvHint()).thenReturn(
                "export AUTOWONDER_UPLOAD_TOKEN='<token returned by autowonder.workitem_cli_upload_token>'");
        when(workitemCliUploadTokenService.scheduledTaskCommandTemplate()).thenReturn(
                "npx -y autowonder@0.2.130 scheduled-task upload --server-url https://daily.auto-wonder.example.com"
                        + " --scheduled-task-id <scheduled-task-id>"
                        + " --file <filepath-1> --file <filepath-2> --file <images-1> --json");
        workitemCliDownloadTokenService = mock(WorkitemCliDownloadTokenService.class);
        when(workitemCliDownloadTokenService.commandTemplate()).thenReturn(
                "npx -y autowonder@0.2.130 workitem download --server-url https://daily.auto-wonder.example.com"
                        + " --workitem-id <workitem-id>"
                        + " --file <name-or-id> --output-dir <dir> --json");
        memoryService = mock(MemoryService.class);
        repoService = mock(RepoService.class);
        squadService = mock(SquadService.class);
        dispatchPauseService = mock(DispatchPauseService.class);
        executorService = mock(ExecutorService.class);
        providerModelCatalogService = mock(ProviderModelCatalogService.class);
        launchBrandingService = mock(PlatformBrandingService.class);
        when(launchBrandingService.effectivePublicBaseUrl()).thenReturn("https://auto-wonder.example.com");
        when(launchBrandingService.recommendedRuntimeVersion()).thenReturn("0.2.152");
        capabilityGuard = mock(ScheduledTaskCapabilityGuard.class);
        categoryService = mock(CategoryService.class);
        service = new McpToolService(workspaceService, workitemService, guidanceService, skillService,
                skillPackageService, sdlcService, agentService, statusTemplateService,
                new PlatformSkillCatalog(), dispatchDao, requirementDocumentService,
                workitemCliUploadTokenService, workitemCliDownloadTokenService, memoryService, repoService,
                squadService, dispatchPauseService, categoryService);
        ReflectionTestUtils.setField(service, "capabilityGuard", capabilityGuard);
        ReflectionTestUtils.setField(service, "executorService", executorService);
        ReflectionTestUtils.setField(service, "executorLaunchOptionsService",
                new ExecutorLaunchOptionsService(providerModelCatalogService));
        executorLaunchConfigService = mock(ExecutorLaunchConfigService.class);
        ReflectionTestUtils.setField(service, "executorLaunchConfigService", executorLaunchConfigService);
        // 命令生成只读数据库配置，所以生成器必须拿到执行器与启动配置两个依赖
        ReflectionTestUtils.setField(service, "executorLaunchCommandService",
                new ExecutorLaunchCommandService(launchBrandingService, executorService,
                        executorLaunchConfigService));
        traceService = mock(RuntimeTraceService.class);
        traceArtifacts = mock(RuntimeTraceArtifactService.class);
        ReflectionTestUtils.setField(service, "runtimeTraceService", traceService);
        ReflectionTestUtils.setField(service, "runtimeTraceArtifactService", traceArtifacts);
        principal = principal(WorkspaceAccessLevel.READ_WRITE);
    }

    @Test
    void traceReadsPreferArchiveAndFallBackToLiveWithCursor() {
        when(dispatchDao.findById(10490L)).thenReturn(dispatch(10490L, WORKSPACE_ID, 99L, 40014L));
        var archived = new RuntimeTraceVO();
        when(traceArtifacts.loadOutlineIfPresent(WORKSPACE_ID, 10490L)).thenReturn(archived);
        var args = Map.<String, Object>of("workspaceId", WORKSPACE_ID, "dispatchId", 10490L, "afterSeq", 8L);
        assertSame(archived, service.call(principal(WorkspaceAccessLevel.READ_ONLY), TRACE_TOOLS.get(0), args));
        verifyNoInteractions(traceService);
        when(traceArtifacts.loadOutlineIfPresent(WORKSPACE_ID, 10490L)).thenReturn(null);
        var live = new RuntimeTraceVO();
        when(traceService.get(WORKSPACE_ID, 10490L, 8L)).thenReturn(live);
        assertSame(live, service.call(principal, TRACE_TOOLS.get(0), args));
        assertEquals("LIVE", live.getSource());
    }

    @Test
    void traceDetailReadsReturnOriginalPayloadsAndMissingArtifactErrors() {
        when(dispatchDao.findById(10490L)).thenReturn(dispatch(10490L, WORKSPACE_ID, 99L, 40014L));
        var turn = new RuntimeTraceVO.Turn();
        turn.setOutput("完整回复");
        var observation = new RuntimeTraceVO.Observation();
        observation.setInput(Map.of("command", "git status"));
        observation.setOutput("clean");
        var activities = new RuntimeActivityTimelineVO();
        when(traceArtifacts.loadTurn(WORKSPACE_ID, 10490L, "turn:1")).thenReturn(turn);
        when(traceArtifacts.loadObservation(WORKSPACE_ID, 10490L, "tool:1")).thenReturn(observation);
        when(traceService.getActivities(WORKSPACE_ID, 10490L)).thenReturn(activities);
        var args = Map.<String, Object>of("workspaceId", WORKSPACE_ID, "dispatchId", 10490L,
                "traceId", "turn:1", "observationId", "tool:1");
        assertSame(turn, service.call(principal, TRACE_TOOLS.get(2), args));
        assertSame(observation, service.call(principal, TRACE_TOOLS.get(3), args));
        assertSame(activities, service.call(principal, TRACE_TOOLS.get(1), args));
        when(traceArtifacts.loadTurn(WORKSPACE_ID, 10490L, "turn:1"))
                .thenThrow(new BizException(ErrorCode.ARTIFACT_NOT_FOUND));
        assertEquals(ErrorCode.ARTIFACT_NOT_FOUND.getCode(), assertThrows(BizException.class,
                () -> service.call(principal, TRACE_TOOLS.get(2), args)).getCode());
    }

    @Test
    void allTraceToolsRejectMissingDispatchAndOtherWorkspaceBeforeReadingStorage() {
        for (String tool : TRACE_TOOLS) {
            var args = Map.<String, Object>of("workspaceId", WORKSPACE_ID, "dispatchId", 10490L);
            when(dispatchDao.findById(10490L)).thenReturn(null);
            assertEquals(ErrorCode.DISPATCH_NOT_FOUND.getCode(), assertThrows(BizException.class,
                    () -> service.call(principal, tool, args)).getCode());
            when(dispatchDao.findById(10490L)).thenReturn(dispatch(10490L, WORKSPACE_ID + 1, 99L, 40014L));
            assertEquals(ErrorCode.DISPATCH_NOT_FOUND.getCode(), assertThrows(BizException.class,
                    () -> service.call(principal, tool, args)).getCode());
        }
        verifyNoInteractions(traceService, traceArtifacts);
    }

    @Test
    void traceReadsCheckLiveMembershipAndScopedWorkspace() {
        var scoped = scopedPrincipal(WorkspaceAccessLevel.READ_ONLY);
        when(workspaceService.activeAccessLevel(WORKSPACE_ID, USER_ID))
                .thenThrow(new BizException(ErrorCode.WORKSPACE_NOT_MEMBER));
        for (String tool : TRACE_TOOLS) {
            var args = Map.<String, Object>of("workspaceId", WORKSPACE_ID, "dispatchId", 10490L);
            assertThrows(BizException.class, () -> service.call(principal, tool, args));
            assertEquals(ErrorCode.NO_PERMISSION.getCode(), assertThrows(BizException.class,
                    () -> service.call(scoped, tool, Map.of("workspaceId", WORKSPACE_ID + 1,
                            "dispatchId", 10490L))).getCode());
        }
        verifyNoInteractions(traceService, traceArtifacts);
    }

    @Test
    void dispatchCredentialCanReadHistoryOfSameSubjectButCannotCrossSubjectOrSource() {
        var caller = dispatchPrincipal(-321L);
        when(dispatchDao.findById(10490L)).thenReturn(dispatch(10490L, WORKSPACE_ID, 99L, 40015L));
        var args = Map.<String, Object>of("workspaceId", WORKSPACE_ID, "dispatchId", 10490L,
                "traceId", "turn:1", "observationId", "tool:1");
        when(traceService.get(WORKSPACE_ID, 10490L, null)).thenReturn(new RuntimeTraceVO());
        for (String tool : TRACE_TOOLS) service.call(caller, tool, args);
        clearInvocations(traceService, traceArtifacts);
        for (String tool : TRACE_TOOLS) {
            when(dispatchDao.findById(10490L)).thenReturn(dispatch(10490L, WORKSPACE_ID, 100L, 40015L));
            assertEquals(ErrorCode.NO_PERMISSION.getCode(), assertThrows(BizException.class,
                    () -> service.call(caller, tool, args)).getCode());
            var runDispatch = dispatch(10490L, WORKSPACE_ID, 99L, 40015L);
            runDispatch.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
            when(dispatchDao.findById(10490L)).thenReturn(runDispatch);
            assertEquals(ErrorCode.NO_PERMISSION.getCode(), assertThrows(BizException.class,
                    () -> service.call(caller, tool, args)).getCode());
        }
        verifyNoInteractions(traceService, traceArtifacts);
    }

    @Test
    void traceReadsHonorScheduledTaskCapabilityGate() {
        var target = dispatch(10490L, WORKSPACE_ID, 99L, 40014L);
        target.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        when(dispatchDao.findById(10490L)).thenReturn(target);
        doThrow(new BizException(ErrorCode.SCHEDULED_TASK_SCHEMA_NOT_READY))
                .when(capabilityGuard).requireAvailable("mcp");
        for (String tool : TRACE_TOOLS) {
            assertEquals(ErrorCode.SCHEDULED_TASK_SCHEMA_NOT_READY.getCode(), assertThrows(BizException.class,
                    () -> service.call(principal, tool,
                            Map.of("workspaceId", WORKSPACE_ID, "dispatchId", 10490L))).getCode());
        }
        verifyNoInteractions(traceService, traceArtifacts);
    }

    @Test
    void traceArgumentsRejectNonPositiveIdNegativeCursorAndMissingDetailId() {
        for (String tool : TRACE_TOOLS) {
            for (long id : List.of(0L, -1L)) {
                assertThrows(BizException.class, () -> service.call(principal, tool,
                        Map.of("workspaceId", WORKSPACE_ID, "dispatchId", id)));
            }
        }
        when(dispatchDao.findById(10490L)).thenReturn(dispatch(10490L, WORKSPACE_ID, 99L, 40014L));
        assertThrows(BizException.class, () -> service.call(principal, TRACE_TOOLS.get(0),
                Map.of("workspaceId", WORKSPACE_ID, "dispatchId", 10490L, "afterSeq", -1L)));
        for (String tool : TRACE_TOOLS.subList(2, 4)) {
            assertThrows(BizException.class, () -> service.call(principal, tool,
                    Map.of("workspaceId", WORKSPACE_ID, "dispatchId", 10490L)));
        }
        verifyNoInteractions(traceService, traceArtifacts);
    }

    @Test
    void readOnlyCatalogContainsEveryAndOnlyQueryTool() {
        Set<String> expectedReadOnly = Set.of(
                "autowonder.get_dispatch_runtime_trace",
                "autowonder.get_dispatch_activities",
                "autowonder.get_dispatch_turn",
                "autowonder.get_dispatch_observation",
                "autowonder.list_projects",
                "autowonder.list_workitems",
                "autowonder.get_workitem",
                "autowonder.list_workitem_comments",
                "autowonder.list_workitem_documents",
                "autowonder.workitem_cli_download_token",
                "autowonder.list_status_templates",
                "autowonder.get_status_template",
                "autowonder.list_sdlcs",
                "autowonder.get_sdlc",
                "autowonder.list_agents",
                "autowonder.get_agent",
                "autowonder.get_agent_version",
                "autowonder.get_agent_version_status",
                "autowonder.list_skills",
                "autowonder.get_skill",
                "autowonder.inspect_skill_package",
                "autowonder.list_platform_skills",
                "autowonder.list_categories",
                "autowonder.get_category",
                "autowonder.count_pending_memories",
                "autowonder.search_memories",
                "autowonder.get_memory",
                "autowonder.list_repos",
                "autowonder.get_repo",
                "autowonder.list_repo_relations",
                "autowonder.list_squads",
                "autowonder.get_squad",
                "autowonder.list_scheduled_tasks",
                "autowonder.get_scheduled_task",
                "autowonder.get_scheduled_task_run",
                "autowonder.list_scheduled_task_runs",
                "autowonder.list_executors",
                "autowonder.get_executor",
                "autowonder.list_executor_client_kinds",
                "autowonder.get_executor_launch_options",
                "autowonder.get_delivery_recovery");
        Set<String> fullCatalog = service.listTools().stream()
                .map(McpToolVO::getName)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> readOnlyCatalog =
                service.listTools(scopedPrincipal(WorkspaceAccessLevel.READ_ONLY)).stream()
                        .map(McpToolVO::getName)
                        .collect(java.util.stream.Collectors.toSet());

        assertEquals(expectedReadOnly, readOnlyCatalog);

        Set<String> readWriteCatalog =
                service.listTools(scopedPrincipal(WorkspaceAccessLevel.READ_WRITE)).stream()
                        .map(McpToolVO::getName)
                        .collect(java.util.stream.Collectors.toSet());
        Set<String> adminCatalog =
                service.listTools(scopedPrincipal(WorkspaceAccessLevel.ADMIN)).stream()
                        .map(McpToolVO::getName)
                        .collect(java.util.stream.Collectors.toSet());

        // 执行器及项目分类维护要求 ADMIN。
        Set<String> adminOnlyTools = Set.of(
                "autowonder.create_category", "autowonder.update_category", "autowonder.delete_category",
                "autowonder.create_executor",
                "autowonder.get_executor_token",
                "autowonder.delete_executor",
                "autowonder.build_executor_launch_command",
                "autowonder.get_executor_launch_config",
                "autowonder.update_executor_launch_config");
        Set<String> hiddenFromReadWrite = new java.util.HashSet<>(fullCatalog);
        hiddenFromReadWrite.removeAll(readWriteCatalog);

        assertEquals(adminOnlyTools, hiddenFromReadWrite);
        assertEquals(fullCatalog, adminCatalog);
        assertEquals(112, fullCatalog.size());
        assertEquals(103, readWriteCatalog.size());
    }

    @Test
    void agentBindingToolsDeduplicateIdsAndDelegateToAgentService() {
        assertEquals(Map.of("repoIds", List.of(11L, 12L)),
                service.call(principal, "autowonder.bind_agent_repos",
                        Map.of("workspaceId", WORKSPACE_ID, "agentId", 5L,
                                "repoIds", List.of(11L, 11L, 12L), "permLevel", "WRITE")));
        assertEquals(Map.of("skillIds", List.of(21L, 22L)),
                service.call(principal, "autowonder.bind_agent_skills",
                        Map.of("workspaceId", WORKSPACE_ID, "agentId", 5L,
                                "skillIds", List.of(21L, 21L, 22L))));
        assertEquals(Map.of("memoryIds", List.of(31L, 32L)),
                service.call(principal, "autowonder.bind_agent_memories",
                        Map.of("workspaceId", WORKSPACE_ID, "agentId", 5L,
                                "memoryIds", List.of(31L, 31L, 32L), "source", "ORG")));

        verify(agentService, times(2)).addRepoPerm(eq(5L), any(), eq(WORKSPACE_ID), eq(USER_ID));
        verify(agentService, times(2)).addSkill(eq(5L), any(), eq(WORKSPACE_ID), eq(USER_ID));
        verify(agentService, times(2)).addMemoryRef(eq(5L), any(), eq(WORKSPACE_ID), eq(USER_ID));

        assertEquals("array", ((Map<?, ?>) outputProperties(toolByName("autowonder.bind_agent_repos"))
                .get("repoIds")).get("type"));
        assertEquals("array", ((Map<?, ?>) outputProperties(toolByName("autowonder.bind_agent_skills"))
                .get("skillIds")).get("type"));
        assertEquals("array", ((Map<?, ?>) outputProperties(toolByName("autowonder.bind_agent_memories"))
                .get("memoryIds")).get("type"));
    }

    @Test
    void agentUnbindingToolsDeduplicateIdsAndDelegateToAgentService() {
        assertEquals(Map.of("repoIds", List.of(11L, 12L)),
                service.call(principal, "autowonder.unbind_agent_repos",
                        Map.of("workspaceId", WORKSPACE_ID, "agentId", 5L,
                                "repoIds", List.of(11L, 11L, 12L))));
        assertEquals(Map.of("skillIds", List.of(21L, 22L)),
                service.call(principal, "autowonder.unbind_agent_skills",
                        Map.of("workspaceId", WORKSPACE_ID, "agentId", 5L,
                                "skillIds", List.of(21L, 21L, 22L))));
        assertEquals(Map.of("memoryIds", List.of(31L, 32L)),
                service.call(principal, "autowonder.unbind_agent_memories",
                        Map.of("workspaceId", WORKSPACE_ID, "agentId", 5L,
                                "memoryIds", List.of(31L, 31L, 32L))));

        verify(agentService).removeRepoPerm(5L, 11L, WORKSPACE_ID, USER_ID);
        verify(agentService).removeRepoPerm(5L, 12L, WORKSPACE_ID, USER_ID);
        verify(agentService).removeSkill(5L, 21L, WORKSPACE_ID, USER_ID);
        verify(agentService).removeSkill(5L, 22L, WORKSPACE_ID, USER_ID);
        verify(agentService).removeMemoryRef(5L, 31L, WORKSPACE_ID, USER_ID);
        verify(agentService).removeMemoryRef(5L, 32L, WORKSPACE_ID, USER_ID);
    }

    @Test
    void squadToolsDelegateToSquadServiceAndExposePrimitiveMemberIds() {
        SquadVO squad = new SquadVO();
        when(squadService.get(42L)).thenReturn(squad);

        assertEquals(squad, service.call(principal, "autowonder.get_squad", Map.of("workspaceId", WORKSPACE_ID, "id", 42L)));
        assertEquals(Map.of("added", true), service.call(principal, "autowonder.add_agent_to_squad",
                Map.of("workspaceId", WORKSPACE_ID, "squadId", 42L, "agentId", 5L)));
        verify(squadService).addMembers(42L, List.of(5L), WORKSPACE_ID);

        Map<String, Object> properties = outputProperties(toolByName("autowonder.get_squad"));
        @SuppressWarnings("unchecked")
        Map<String, Object> memberIds = (Map<String, Object>) properties.get("memberAgentIds");
        @SuppressWarnings("unchecked")
        Map<String, Object> items = (Map<String, Object>) memberIds.get("items");
        assertEquals("integer", items.get("type"));
    }

    @Test
    void repoMapToolsReadAndMaintainTenantScopedRelations() {
        RepoVO repo = new RepoVO();
        repo.setId(10L);
        repo.setName("service");
        when(repoService.list(WORKSPACE_ID, 1, 100)).thenReturn(List.of(repo));
        RepoRelationVO relation = new RepoRelationVO();
        relation.setId(91L);
        relation.setFromRepoId(10L);
        relation.setToRepoId(11L);
        relation.setRelationType("DEPENDS_ON");
        when(repoService.listRelationsByRepoId(WORKSPACE_ID, 10L)).thenReturn(List.of(relation));
        when(repoService.createRelation(any(CreateRelationRequest.class), eq(WORKSPACE_ID), eq(USER_ID)))
                .thenReturn(relation);

        assertEquals(List.of(repo), service.call(principal, "autowonder.list_repos",
                Map.of("workspaceId", WORKSPACE_ID)));
        assertEquals(List.of(relation), service.call(principal, "autowonder.list_repo_relations",
                Map.of("workspaceId", WORKSPACE_ID, "repoId", 10L)));
        assertEquals(relation, service.call(principal, "autowonder.create_repo_relation",
                Map.of("workspaceId", WORKSPACE_ID, "fromRepoId", 10L, "toRepoId", 11L,
                        "relationType", "DEPENDS_ON")));
        service.call(principal, "autowonder.delete_repo_relation",
                Map.of("workspaceId", WORKSPACE_ID, "id", 91L));

        verify(repoService).get(10L, WORKSPACE_ID);
        verify(repoService).deleteRelation(91L, WORKSPACE_ID);
    }

    @Test
    void createRepoDelegatesToRepoService() {
        RepoVO created = new RepoVO();
        created.setId(20L);
        created.setName("new-repo");
        when(repoService.create(any(CreateRepoRequest.class), eq(WORKSPACE_ID), eq(USER_ID)))
                .thenReturn(created);

        Object result = call(principal, "autowonder.create_repo",
                Map.of("name", "new-repo", "url", "git@github.com:group/new-repo.git",
                        "defaultBranch", "main", "description", "A new repo"));

        assertSame(created, result);
        verify(repoService).create(argThat(req ->
                "new-repo".equals(req.getName())
                        && "git@github.com:group/new-repo.git".equals(req.getUrl())
                        && "main".equals(req.getDefaultBranch())
                        && "A new repo".equals(req.getDescription())),
                eq(WORKSPACE_ID), eq(USER_ID));
    }

    @Test
    void createRepoSchemaRequiresNameAndUrl() {
        Map<String, Object> schema = schemaFor("autowonder.create_repo");
        assertEquals(List.of("workspaceId", "name", "url"), schema.get("required"));
        assertTrue(properties(schema).keySet().containsAll(
                List.of("name", "url", "defaultBranch", "description")));
    }

    @Test
    void updateRepoDelegatesToRepoService() {
        RepoVO updated = new RepoVO();
        updated.setId(20L);
        updated.setName("renamed-repo");
        when(repoService.update(eq(20L), any(UpdateRepoRequest.class), eq(WORKSPACE_ID), eq(USER_ID)))
                .thenReturn(updated);

        Object result = call(principal, "autowonder.update_repo",
                Map.of("id", 20L, "name", "renamed-repo", "description", "Updated description"));

        assertSame(updated, result);
        verify(repoService).update(eq(20L), argThat(req ->
                "renamed-repo".equals(req.getName())
                        && req.isNamePresent()
                        && "Updated description".equals(req.getDescription())
                        && req.isDescriptionPresent()
                        && !req.isUrlPresent()
                        && !req.isDefaultBranchPresent()),
                eq(WORKSPACE_ID), eq(USER_ID));
    }

    @Test
    void updateRepoTreatsExplicitNullAsClearWhileOmittedFieldsStayAbsent() {
        RepoVO updated = new RepoVO();
        updated.setId(20L);
        when(repoService.update(eq(20L), any(UpdateRepoRequest.class), eq(WORKSPACE_ID), eq(USER_ID)))
                .thenReturn(updated);

        Map<String, Object> args = new HashMap<>();
        args.put("id", 20L);
        args.put("description", null);

        call(principal, "autowonder.update_repo", args);

        verify(repoService).update(eq(20L), argThat(req ->
                req.isDescriptionPresent()
                        && req.getDescription() == null
                        && !req.isNamePresent()
                        && !req.isUrlPresent()
                        && !req.isDefaultBranchPresent()),
                eq(WORKSPACE_ID), eq(USER_ID));
    }

    @Test
    void updateRepoSchemaRequiresIdAndMakesOtherFieldsOptional() {
        Map<String, Object> schema = schemaFor("autowonder.update_repo");
        assertEquals(List.of("workspaceId", "id"), schema.get("required"));
        assertTrue(properties(schema).keySet().containsAll(
                List.of("id", "name", "url", "defaultBranch", "description")));
    }

    @Test
    void deleteRepoDelegatesToRepoService() {
        Object result = call(principal, "autowonder.delete_repo",
                Map.of("id", 20L));

        assertEquals(Map.of("deleted", true), result);
        verify(repoService).delete(20L, WORKSPACE_ID, USER_ID);
    }

    @Test
    void deleteRepoSchemaRequiresOnlyId() {
        Map<String, Object> schema = schemaFor("autowonder.delete_repo");
        assertEquals(List.of("workspaceId", "id"), schema.get("required"));
        assertTrue(properties(schema).containsKey("id"));
    }

    @Test
    void repoCrudOutputSchemasReturnRepoOrDeletedFlag() {
        Map<String, Object> createOutput = properties(outputSchemaFor("autowonder.create_repo"));
        assertTrue(createOutput.keySet().containsAll(List.of("id", "name", "url")));

        Map<String, Object> updateOutput = properties(outputSchemaFor("autowonder.update_repo"));
        assertTrue(updateOutput.keySet().containsAll(List.of("id", "name", "url")));

        Map<String, Object> deleteOutput = properties(outputSchemaFor("autowonder.delete_repo"));
        assertTrue(deleteOutput.containsKey("deleted"));
    }

    @Test
    void everyFilteredMutationIsIndependentlyRejectedBeforeDispatch() {
        Set<String> readOnlyNames =
                service.listTools(scopedPrincipal(WorkspaceAccessLevel.READ_ONLY)).stream()
                        .map(McpToolVO::getName)
                        .collect(java.util.stream.Collectors.toSet());

        for (McpToolVO tool : service.listTools()) {
            if (!readOnlyNames.contains(tool.getName())) {
                BizException exception = assertThrows(BizException.class, () ->
                        call(principal(WorkspaceAccessLevel.READ_ONLY),
                                tool.getName(), Map.of()));
                assertEquals("10403", exception.getCode(), tool.getName());
            }
        }
    }

    @Test
    void listToolsIncludesCoreOperations() {
        assertTrue(service.listTools().stream()
                .anyMatch(tool -> "autowonder.create_workitem".equals(tool.getName())));
        assertTrue(service.listTools().stream()
                .anyMatch(tool -> "autowonder.assign_workitem".equals(tool.getName())));
        assertTrue(service.listTools().stream()
                .anyMatch(tool -> "autowonder.create_sdlc".equals(tool.getName())));
        assertTrue(service.listTools().stream()
                .anyMatch(tool -> "autowonder.list_agents".equals(tool.getName())));
        assertTrue(service.listTools().stream()
                .anyMatch(tool -> "autowonder.install_platform_skill".equals(tool.getName())));
        assertTrue(service.listTools().stream()
                .anyMatch(tool -> "autowonder.upload_workitem_document".equals(tool.getName())));
        assertTrue(service.listTools().stream()
                .anyMatch(tool -> "autowonder.upload_skill_package".equals(tool.getName())));
        assertTrue(service.listTools().stream()
                .anyMatch(tool -> "autowonder.create_skill_from_package".equals(tool.getName())));
        assertTrue(service.listTools().stream()
                .anyMatch(tool -> "autowonder.update_skill_package".equals(tool.getName())));
    }

    @Test
    void workitemCliUploadTokenToolIsRegisteredWithIdRequired() {
        McpToolVO tool = toolByName("autowonder.workitem_cli_upload_token");

        Map<String, Object> schema = tool.getInputSchema();
        assertEquals(List.of("workspaceId", "id"), schema.get("required"));
        assertTrue(properties(schema).containsKey("id"));
        assertTrue(tool.getDescription().contains(
                "npx -y autowonder@0.2.130 workitem upload --server-url https://daily.auto-wonder.example.com"));
        assertTrue(tool.getDescription().contains(
                "--file <filepath-1> --file <filepath-2> --file <images-1> --json"));
        assertTrue(tool.getDescription().contains(
                "Long-lived personal, dispatch, and conversation credentials can mint it"));

        Map<String, Object> output = outputProperties(tool);
        assertTrue(output.keySet().containsAll(List.of("token", "tokenType", "expiresInSeconds", "expiresAt",
                "serverUrl", "runtimeVersion", "tokenEnvName", "command", "powershellCommand",
                "supportedExtensions", "maxFiles", "maxFileSizeBytes", "maxTotalSizeBytes")));
        assertEquals("array", ((Map<?, ?>) output.get("supportedExtensions")).get("type"));
    }

    @Test
    void uploadWorkitemDocumentDescriptionIsDeprecatedAndPointsToCli() {
        McpToolVO tool = toolByName("autowonder.upload_workitem_document");

        assertTrue(tool.getDescription().startsWith(
                "DEPRECATED: Do not send file content or Base64 through MCP."));
        assertTrue(tool.getDescription().contains("autowonder.workitem_cli_upload_token"));
        assertTrue(tool.getDescription().contains(
                "npx -y autowonder@0.2.130 workitem upload --server-url https://daily.auto-wonder.example.com"
                        + " --workitem-id <workitem-id>"
                        + " --file <filepath-1> --file <filepath-2> --file <images-1> --json"));
    }

    @Test
    void uploadWorkitemDocumentAdvertisesTheExtendedFormatWhitelist() {
        McpToolVO tool = toolByName("autowonder.upload_workitem_document");

        assertTrue(tool.getDescription().contains("Word documents (.docx, .doc)"),
                "description should advertise Word support");
        assertTrue(tool.getDescription().contains("source code (.java, .py)"),
                "description should advertise source-code support");
        assertTrue(tool.getDescription().contains("ZIP archives (.zip)"),
                "description should advertise ZIP support");

        String filenameDescription = (String) property(schemaFor("autowonder.upload_workitem_document"),
                "filename").get("description");
        for (String extension : com.aliyun.autowonder.artifact.RequirementDocumentService.SUPPORTED_EXTENSIONS) {
            assertTrue(filenameDescription.contains(extension),
                    "filename schema must list " + extension + ", got: " + filenameDescription);
        }
    }

    @Test
    void workitemCliUploadTokenInvocationDelegatesForPersonalCredential() {
        WorkitemCliUploadTokenVO vo = new WorkitemCliUploadTokenVO();
        vo.setToken("awupload_xyz");
        when(workitemCliUploadTokenService.mint(
                McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, 50063L))
                .thenReturn(vo);

        Object result = call(principal, "autowonder.workitem_cli_upload_token", Map.of("id", 50063L));

        assertSame(vo, result);
        verify(workitemCliUploadTokenService).mint(
                McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, 50063L);
    }

    @Test
    void workitemCliUploadTokenInvocationDelegatesForDispatchCredential() {
        WorkitemCliUploadTokenVO vo = new WorkitemCliUploadTokenVO();
        vo.setToken("awupload_dispatch");
        when(workitemCliUploadTokenService.mint(
                McpAccessTokenService.CredentialType.DISPATCH, USER_ID, 50063L))
                .thenReturn(vo);

        Object result = call(dispatchPrincipal(5L), "autowonder.workitem_cli_upload_token", Map.of("id", 50063L));

        assertSame(vo, result);
        verify(workitemCliUploadTokenService).mint(
                McpAccessTokenService.CredentialType.DISPATCH, USER_ID, 50063L);
    }

    @Test
    void workitemCliUploadTokenInvocationDelegatesForConversationCredential() {
        WorkitemCliUploadTokenVO vo = new WorkitemCliUploadTokenVO();
        vo.setToken("awupload_conversation");
        when(workitemCliUploadTokenService.mint(
                McpAccessTokenService.CredentialType.CONVERSATION, USER_ID, 50063L))
                .thenReturn(vo);

        Object result = call(scopedPrincipal(WorkspaceAccessLevel.ADMIN),
                "autowonder.workitem_cli_upload_token", Map.of("id", 50063L));

        assertSame(vo, result);
        verify(workitemCliUploadTokenService).mint(
                McpAccessTokenService.CredentialType.CONVERSATION, USER_ID, 50063L);
    }

    @Test
    void workitemCliDownloadTokenInvocationDelegatesForPersonalCredential() {
        WorkitemCliDownloadTokenVO vo = new WorkitemCliDownloadTokenVO();
        vo.setToken("awdownload_xyz");
        when(workitemCliDownloadTokenService.mint(
                McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, 50063L))
                .thenReturn(vo);

        Object result = call(principal, "autowonder.workitem_cli_download_token", Map.of("id", 50063L));

        assertSame(vo, result);
        verify(workitemCliDownloadTokenService).mint(
                McpAccessTokenService.CredentialType.LONG_LIVED, USER_ID, 50063L);
    }

    @Test
    void workitemCliDownloadTokenInvocationDelegatesForDispatchCredential() {
        WorkitemCliDownloadTokenVO vo = new WorkitemCliDownloadTokenVO();
        vo.setToken("awdownload_dispatch");
        when(workitemCliDownloadTokenService.mint(
                McpAccessTokenService.CredentialType.DISPATCH, USER_ID, 50063L))
                .thenReturn(vo);

        Object result = call(dispatchPrincipal(5L), "autowonder.workitem_cli_download_token", Map.of("id", 50063L));

        assertSame(vo, result);
        verify(workitemCliDownloadTokenService).mint(
                McpAccessTokenService.CredentialType.DISPATCH, USER_ID, 50063L);
    }

    @Test
    void workitemCliDownloadTokenInvocationDelegatesForConversationCredential() {
        WorkitemCliDownloadTokenVO vo = new WorkitemCliDownloadTokenVO();
        vo.setToken("awdownload_conversation");
        when(workitemCliDownloadTokenService.mint(
                McpAccessTokenService.CredentialType.CONVERSATION, USER_ID, 50063L))
                .thenReturn(vo);

        Object result = call(scopedPrincipal(WorkspaceAccessLevel.ADMIN),
                "autowonder.workitem_cli_download_token", Map.of("id", 50063L));

        assertSame(vo, result);
        verify(workitemCliDownloadTokenService).mint(
                McpAccessTokenService.CredentialType.CONVERSATION, USER_ID, 50063L);
    }

    @Test
    void workitemCliDownloadTokenDescriptionPointsAtDownloadCommand() {
        McpToolVO tool = toolByName("autowonder.workitem_cli_download_token");

        assertTrue(tool.getDescription().contains("autowonder.workitem_cli_download_token")
                || tool.getDescription().contains("workitem download"));
        assertTrue(tool.getDescription().contains(
                "npx -y autowonder@0.2.130 workitem download --server-url https://daily.auto-wonder.example.com"
                        + " --workitem-id <workitem-id> --file <name-or-id> --output-dir <dir> --json"));
        assertTrue(tool.getDescription().contains("list_workitem_documents"));
    }

    @Test
    void listWorkitemDocumentsDescriptionGuidesTowardCliDownload() {
        McpToolVO tool = toolByName("autowonder.list_workitem_documents");

        assertTrue(tool.getDescription().contains("never the document body"));
        assertTrue(tool.getDescription().contains("autowonder.workitem_cli_download_token"));
        assertTrue(tool.getDescription().contains("workitem download"));
    }

    @Test
    void workitemCliDownloadTokenDeclaresIdOnlyInputAndTokenOutput() {
        McpToolVO tool = toolByName("autowonder.workitem_cli_download_token");

        String schema = com.alibaba.fastjson.JSON.toJSONString(tool.getInputSchema());
        assertTrue(schema.contains("\"id\""), schema);
        assertTrue(tool.getOutputSchema() != null);
        String output = com.alibaba.fastjson.JSON.toJSONString(tool.getOutputSchema());
        assertTrue(output.contains("awdownload_"), output);
        assertTrue(output.contains("AUTOWONDER_DOWNLOAD_TOKEN"), output);
    }

    @Test
    void privateDeploymentDescriptionsAndResultsNeverExposeDefaults() {
        WorkitemCliUploadTokenService realTokenService = realTokenService(
                "http://autowonder.internal.example.com:8080", "0.9.9-rc.1");
        WorkitemCliDownloadTokenService realDownloadTokenService = realDownloadTokenService(
                "http://autowonder.internal.example.com:8080", "0.9.9-rc.1");
        McpToolService privateService = new McpToolService(workspaceService, workitemService, guidanceService,
                skillService, skillPackageService, sdlcService, agentService, statusTemplateService,
                new PlatformSkillCatalog(), dispatchDao, requirementDocumentService,
                realTokenService, realDownloadTokenService, memoryService, repoService, squadService,
                dispatchPauseService, categoryService);

        List<McpToolVO> tools = privateService.listTools();
        String tokenDescription = tools.stream()
                .filter(tool -> "autowonder.workitem_cli_upload_token".equals(tool.getName()))
                .findFirst().orElseThrow().getDescription();
        String uploadDescription = tools.stream()
                .filter(tool -> "autowonder.upload_workitem_document".equals(tool.getName()))
                .findFirst().orElseThrow().getDescription();

        for (String description : new String[]{tokenDescription, uploadDescription}) {
            assertTrue(description.contains("autowonder@0.9.9-rc.1"), description);
            assertTrue(description.contains("http://autowonder.internal.example.com:8080"), description);
            assertFalse(description.contains("autowonder@latest"), description);
            assertFalse(description.contains("auto-wonder.alibaba.net"), description);
        }

        WorkitemCliUploadTokenVO vo = (WorkitemCliUploadTokenVO) privateService.call(principal,
                "autowonder.workitem_cli_upload_token", withWorkspaceId(Map.of("id", 50063L)));
        assertEquals("http://autowonder.internal.example.com:8080", vo.getServerUrl());
        assertEquals("0.9.9-rc.1", vo.getRuntimeVersion());
        assertFalse(vo.getCommand().contains("autowonder@latest"));
        assertFalse(vo.getCommand().contains("auto-wonder.alibaba.net"));
        assertFalse(vo.getPowershellCommand().contains("auto-wonder.alibaba.net"));
    }

    @Test
    void listToolsExposeOutputSchemasForOpenPlatform() {
        Map<String, Object> createWorkitem = outputSchemaFor("autowonder.create_workitem");
        Map<String, Object> createWorkitemProperties = properties(createWorkitem);

        assertTrue(createWorkitemProperties.keySet().containsAll(List.of("id", "workType", "title", "statusNodeId",
                "assigneeType", "creatorId", "gmtCreate")));
        assertFalse(createWorkitemProperties.containsKey("data"));

        Map<String, Object> listWorkitems = outputSchemaFor("autowonder.list_workitems");
        assertListOutputSchema(listWorkitems);
        Map<String, Object> itemProperties = properties(itemSchema(property(listWorkitems, "items")));
        assertTrue(itemProperties.keySet().containsAll(List.of("id", "title", "statusName")));

        Map<String, Object> sdlc = outputSchemaFor("autowonder.get_sdlc");
        Map<String, Object> steps = property(sdlc, "steps");
        // CR50796-01: steps is a nullable array (list_sdlcs returns steps=null), so the declared type
        // must allow both "array" and "null" while still exposing the step item schema.
        assertEquals(List.of("array", "null"), steps.get("type"));
        assertTrue(properties(itemSchema(steps)).keySet().containsAll(List.of("id", "stepOrder", "name", "kind")));
    }

    @Test
    void workitemOutputSchemasDeclareNullableFields() {
        for (String tool : List.of("autowonder.create_workitem", "autowonder.get_workitem",
                "autowonder.update_workitem", "autowonder.assign_workitem",
                "autowonder.transition_workitem", "autowonder.pause_workitem",
                "autowonder.resume_workitem")) {
            Map<String, Object> workitem = properties(outputSchemaFor(tool));
            assertNullableField(workitem, "sdlcId", "integer");
            assertNullableField(workitem, "sdlcName", "string");
            assertNullableField(workitem, "gmtCreate", "string");
            assertNullableField(workitem, "gmtModified", "string");
            assertNullableField(workitem, "health", "string");
            assertNullableField(workitem, "healthReason", "string");
            assertNullableField(workitem, "deletableReason", "string");
            assertWorkitemNullableActorFields(workitem);
        }

        Map<String, Object> listWorkitems = outputSchemaFor("autowonder.list_workitems");
        Map<String, Object> listItemProperties = properties(itemSchema(property(listWorkitems, "items")));
        assertNullableField(listItemProperties, "sdlcId", "integer");
        assertNullableField(listItemProperties, "sdlcName", "string");
        assertNullableField(listItemProperties, "gmtCreate", "string");
        assertNullableField(listItemProperties, "gmtModified", "string");
        assertNullableField(listItemProperties, "health", "string");
        assertNullableField(listItemProperties, "healthReason", "string");
        assertNullableField(listItemProperties, "deletableReason", "string");
        assertWorkitemNullableActorFields(listItemProperties);

        Map<String, Object> runDetail = outputSchemaFor("autowonder.get_scheduled_task_run");
        assertWorkitemNullableActorFields(properties(itemSchema(property(runDetail, "derivedWorkitems"))));

        Map<String, Object> comment = properties(itemSchema(
                property(outputSchemaFor("autowonder.list_workitem_comments"), "items")));
        assertNullableField(comment, "gmtCreate", "string");

        Map<String, Object> document = properties(itemSchema(
                property(outputSchemaFor("autowonder.list_workitem_documents"), "items")));
        assertNullableField(document, "gmtCreate", "string");
    }

    /** Workitem columns that are DEFAULT NULL, plus actor names that stay unresolved for deleted agents or users. */
    private void assertWorkitemNullableActorFields(Map<String, Object> workitem) {
        assertNullableField(workitem, "contentMd", "string");
        assertNullableField(workitem, "templateId", "integer");
        assertNullableField(workitem, "statusNodeId", "integer");
        assertNullableField(workitem, "statusName", "string");
        assertNullableField(workitem, "assigneeType", "string");
        assertNullableField(workitem, "assigneeRef", "integer");
        assertNullableField(workitem, "assigneeName", "string");
        assertNullableField(workitem, "assigneeDisplayName", "string");
        assertNullableField(workitem, "creatorId", "integer");
        assertNullableField(workitem, "creatorName", "string");
        assertNullableField(workitem, "creatorDisplayName", "string");
    }

    @Test
    void agentOutputSchemasMatchNullableFieldsAndSerializedDateTypes() {
        for (String tool : List.of("autowonder.get_agent", "autowonder.update_agent")) {
            Map<String, Object> agent = properties(outputSchemaFor(tool));
            assertNullableField(agent, "avatarUrl", "string");
            assertNullableField(agent, "onlineVersionId", "integer");
            assertNullableField(agent, "editingVersionId", "integer");
            assertNullableField(agent, "latestVersionNo", "integer");
            assertNullableField(agent, "gmtCreate", "string");
            assertNullableField(agent, "roleName", "string");
            assertNullableField(agent, "roleCode", "string");
            assertNullableField(agent, "businessBackground", "string");
            assertNullableField(agent, "responsibilities", "string");
        }

        Map<String, Object> versionStatus = outputSchemaFor("autowonder.get_agent_version_status");
        Map<String, Object> nestedAgent = properties(property(versionStatus, "agent"));
        assertNullableField(nestedAgent, "avatarUrl", "string");
        assertNullableField(nestedAgent, "gmtCreate", "string");
        Map<String, Object> version = properties(itemSchema(property(versionStatus, "versions")));
        assertNullableField(version, "roleName", "string");
        assertNullableField(version, "gmtCreate", "string");
    }

    @Test
    void sdlcOutputSchemasDeclareNullableFields() {
        // Regression for -32602: a template-created SDLC returns null for unconfigured optional
        // fields, so the output schema must allow null or strict clients reject the response even
        // though the read/write already succeeded server-side.
        for (String tool : List.of("autowonder.create_sdlc", "autowonder.get_sdlc",
                "autowonder.update_sdlc", "autowonder.enable_sdlc")) {
            Map<String, Object> sdlc = properties(outputSchemaFor(tool));
            assertNullableField(sdlc, "description", "string");
            assertNullableField(sdlc, "workType", "string");
            assertNullableField(sdlc, "entryStepId", "integer");
            assertNullableField(sdlc, "gmtCreate", "string");
            // CR50796-01: steps itself must allow null; list_sdlcs reuses this schema and
            // SdlcService.list() returns steps=null for every item.
            assertNullableField(sdlc, "steps", "array");
            // stepCount is an Integer that stays null on any VO built without steps or a batch count.
            assertNullableField(sdlc, "stepCount", "integer");

            Map<String, Object> step = properties(itemSchema(property(
                    outputSchemaFor(tool), "steps")));
            assertSdlcStepNullableFields(step);
        }

        Map<String, Object> listItems = properties(itemSchema(property(
                outputSchemaFor("autowonder.list_sdlcs"), "items")));
        assertNullableField(listItems, "workType", "string");
        assertNullableField(listItems, "steps", "array");
        assertNullableField(listItems, "stepCount", "integer");

        for (String tool : List.of("autowonder.add_sdlc_step", "autowonder.update_sdlc_step")) {
            assertSdlcStepNullableFields(properties(outputSchemaFor(tool)));
        }
    }

    private void assertSdlcStepNullableFields(Map<String, Object> step) {
        assertNullableField(step, "instructionMd", "string");
        assertNullableField(step, "checklistJson", "string");
        assertNullableField(step, "gatePolicyJson", "string");
        assertNullableField(step, "timeoutSeconds", "integer");
        assertNullableField(step, "retryBudget", "integer");
        assertNullableField(step, "code", "string");
        assertNullableField(step, "handlerType", "string");
        assertNullableField(step, "handlerRoleRef", "string");
        assertNullableField(step, "statusOnEnterCode", "string");
        assertNullableField(step, "onSuccess", "string");
        assertNullableField(step, "onFail", "string");
    }

    @Test
    void sdlcStructuredContentWithNullOptionalFieldsMatchesOutputSchema() {
        // Faithful reproduction of the client-side -32602 failure. structuredContent is serialized
        // with the app's real ObjectMapper bean (JacksonConfig), which keeps null keys. A
        // template-created SDLC/step leaves most optional fields null; every serialized field's JSON
        // type must be allowed by the declared outputSchema, otherwise a strict MCP client rejects the
        // response even though the operation already succeeded.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(JacksonConfig.class)
                .run(context -> {
                    ObjectMapper appMapper = context.getBean(ObjectMapper.class);

                    StepVO step = new StepVO();
                    step.setId(400769L);
                    step.setSdlcId(40195L);
                    step.setStepOrder(1);
                    step.setName("编码实现");
                    step.setKind("CODE");
                    step.setRequired(true);
                    // instructionMd, checklistJson, gatePolicyJson, timeoutSeconds, retryBudget, code,
                    // handlerType, handlerRoleRef, statusOnEnterCode, onSuccess, onFail stay null.

                    SdlcVO sdlc = new SdlcVO();
                    sdlc.setId(40195L);
                    sdlc.setName("独立开发者SDLC");
                    sdlc.setStatus("ENABLED");
                    sdlc.setIsDefault(0);
                    sdlc.setVersion(2);
                    sdlc.setGmtCreate(new Date());
                    sdlc.setSteps(List.of(step));
                    // description, workType, entryStepId stay null.

                    Map<String, Object> sdlcSchema = outputSchemaFor("autowonder.get_sdlc");
                    JsonNode serializedSdlc = appMapper.readTree(appMapper.writeValueAsString(sdlc));
                    Map<String, Object> sdlcProps = properties(sdlcSchema);
                    assertTrue(serializedSdlc.get("workType").isNull(),
                            "fixture must serialize a null workType to reproduce the bug");
                    serializedSdlc.fieldNames().forEachRemaining(field -> {
                        if (!"steps".equals(field)) {
                            assertSerializedTypeAllowedBySchema(serializedSdlc, sdlcProps, field);
                        }
                    });

                    JsonNode serializedStep = appMapper.readTree(appMapper.writeValueAsString(step));
                    assertTrue(serializedStep.get("checklistJson").isNull(),
                            "fixture must serialize null step fields to reproduce the bug");
                    Map<String, Object> nestedStepProps = properties(itemSchema(property(sdlcSchema, "steps")));
                    serializedStep.fieldNames().forEachRemaining(field ->
                            assertSerializedTypeAllowedBySchema(serializedStep, nestedStepProps, field));

                    Map<String, Object> updateStepProps = properties(outputSchemaFor("autowonder.update_sdlc_step"));
                    serializedStep.fieldNames().forEachRemaining(field ->
                            assertSerializedTypeAllowedBySchema(serializedStep, updateStepProps, field));
                });
    }

    @Test
    void listSdlcsStructuredContentWithNullStepsMatchesOutputSchema() {
        // CR50796-01 faithful reproduction. SdlcService.list() builds every item with toVO(s, null), so
        // steps is null. list_sdlcs reuses sdlcSchema via listOutputSchema; if steps is declared as a
        // non-null array, a strict MCP client rejects the response with "data/items/0/steps must be array"
        // even though the read already succeeded. Serialize a steps=null SdlcVO with the app's real
        // ObjectMapper (keeps null keys) and validate every field against the list item schema.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(JacksonConfig.class)
                .run(context -> {
                    ObjectMapper appMapper = context.getBean(ObjectMapper.class);

                    SdlcVO listItem = new SdlcVO();
                    listItem.setId(40195L);
                    listItem.setName("独立开发者SDLC");
                    listItem.setStatus("ENABLED");
                    listItem.setIsDefault(0);
                    listItem.setVersion(2);
                    listItem.setGmtCreate(new Date());
                    // steps stays null exactly as SdlcService.list() returns it;
                    // description, workType, entryStepId stay null too.
                    // 列表接口用一次聚合填 stepCount，MCP list_sdlcs 因此也能直接给出步骤数。
                    listItem.setStepCount(3);

                    Map<String, Object> listItems = properties(itemSchema(property(
                            outputSchemaFor("autowonder.list_sdlcs"), "items")));
                    JsonNode serialized = appMapper.readTree(appMapper.writeValueAsString(listItem));
                    assertTrue(serialized.get("steps").isNull(),
                            "fixture must serialize a null steps to reproduce the list_sdlcs -32602 bug");
                    assertEquals(3, serialized.get("stepCount").asInt());
                    serialized.fieldNames().forEachRemaining(field ->
                            assertSerializedTypeAllowedBySchema(serialized, listItems, field));
                });
    }

    @Test
    void skillOutputSchemasDeclareNullableOptionalFields() {
        // NB-01: a skill created via the non-package path never sets package* fields, and
        // description/installSpec/modifier* are optional. The output schema must allow null for them or
        // get_skill/list_skills report the same -32602 on those records.
        Map<String, Object> skill = properties(outputSchemaFor("autowonder.get_skill"));
        assertNullableField(skill, "installSpec", "string");
        assertNullableField(skill, "description", "string");
        assertNullableField(skill, "packageOssRef", "string");
        assertNullableField(skill, "packageFileName", "string");
        assertNullableField(skill, "packageSize", "integer");
        assertNullableField(skill, "packageMd5", "string");
        assertNullableField(skill, "modifierId", "integer");
        assertNullableField(skill, "modifierName", "string");

        Map<String, Object> listItems = properties(itemSchema(property(
                outputSchemaFor("autowonder.list_skills"), "items")));
        assertNullableField(listItems, "packageOssRef", "string");
    }

    @Test
    void skillStructuredContentWithNullPackageFieldsMatchesOutputSchema() {
        // NB-01 faithful reproduction. Serialize a non-package SkillVO with the app's real ObjectMapper
        // and validate every serialized field's JSON type against the declared get_skill schema, so a
        // null package*/description/installSpec/modifier* field cannot slip through as it did for steps.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(JacksonConfig.class)
                .run(context -> {
                    ObjectMapper appMapper = context.getBean(ObjectMapper.class);

                    SkillVO skill = new SkillVO();
                    skill.setId(88L);
                    skill.setType("MCP");
                    skill.setName("autowonder");
                    skill.setSourceType("USER");
                    skill.setVersion(1);
                    skill.setGmtCreate(new Date());
                    skill.setGmtModified(new Date());
                    // installSpec, description, packageOssRef, packageFileName, packageSize,
                    // packageMd5, modifierId, modifierName stay null.

                    Map<String, Object> skillProps = properties(outputSchemaFor("autowonder.get_skill"));
                    JsonNode serialized = appMapper.readTree(appMapper.writeValueAsString(skill));
                    assertTrue(serialized.get("packageOssRef").isNull(),
                            "fixture must serialize null package fields to reproduce the skill -32602 bug");
                    serialized.fieldNames().forEachRemaining(field ->
                            assertSerializedTypeAllowedBySchema(serialized, skillProps, field));
                });
    }

    @Test
    void serializedTimestampMatchesDeclaredOutputSchemaType() {
        // Faithful reproduction of the client-side failure. The MCP tools/call response serializes
        // structuredContent with the app's real ObjectMapper bean (JacksonConfig). Spring Boot's
        // JacksonAutoConfiguration disables WRITE_DATES_AS_TIMESTAMPS, so java.util.Date renders as an
        // ISO-8601 string, not epoch millis. If outputSchema declares the field as integer, qodercli
        // rejects the response with "data/gmtCreate must be integer,null". Build the mapper the same way
        // the running app does so this mismatch is caught here rather than by digital workers.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(JacksonConfig.class)
                .run(context -> {
                    ObjectMapper appMapper = context.getBean(ObjectMapper.class);

                    WorkitemVO vo = new WorkitemVO();
                    vo.setGmtCreate(new Date());
                    vo.setGmtModified(new Date());
                    JsonNode serialized = appMapper.readTree(appMapper.writeValueAsString(vo));

                    Map<String, Object> workitem = properties(outputSchemaFor("autowonder.create_workitem"));
                    assertSerializedTypeAllowedBySchema(serialized, workitem, "gmtCreate");
                    assertSerializedTypeAllowedBySchema(serialized, workitem, "gmtModified");
                });
    }

    @SuppressWarnings("unchecked")
    private void assertSerializedTypeAllowedBySchema(JsonNode serialized, Map<String, Object> properties,
                                                     String field) {
        JsonNode value = serialized.get(field);
        assertNotNull(value, "serialized VO is missing field " + field);
        String jsonType = value.isTextual() ? "string"
                : value.isIntegralNumber() ? "integer"
                : value.isNumber() ? "number"
                : value.isBoolean() ? "boolean"
                : value.isNull() ? "null"
                : "object";
        Object declared = ((Map<String, Object>) properties.get(field)).get("type");
        List<String> allowed = declared instanceof List
                ? (List<String>) declared
                : List.of(String.valueOf(declared));
        assertTrue(allowed.contains(jsonType),
                "outputSchema declares " + field + " as " + allowed
                        + " but the Spring Jackson serializer emits a " + jsonType);
    }

    @Test
    void serializedWorkitemNullsAreDeclaredNullableInOutputSchema() {
        // Faithful reproduction of the client-side failure: WorkitemService.toVO leaves the actor names null
        // when the assignee agent or the creator user cannot be resolved, so qodercli rejected the whole
        // tools/call response with "-32602 ... data/assigneeName must be string, data/assigneeDisplayName
        // must be string". Serialize with the app's real mapper so the mismatch is caught here.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(JacksonConfig.class)
                .run(context -> {
                    ObjectMapper appMapper = context.getBean(ObjectMapper.class);
                    Map<String, Object> getWorkitem = properties(outputSchemaFor("autowonder.get_workitem"));
                    Map<String, Object> listItems = properties(itemSchema(
                            property(outputSchemaFor("autowonder.list_workitems"), "items")));

                    WorkitemVO unresolvedActors = new WorkitemVO();
                    unresolvedActors.setId(53033L);
                    unresolvedActors.setWorkType("BUG");
                    unresolvedActors.setTitle("mcp agent 调用总是异常");
                    unresolvedActors.setAssigneeType("AGENT");
                    unresolvedActors.setAssigneeRef(40999L);
                    unresolvedActors.setPriority(2);
                    unresolvedActors.setVersion(3);
                    unresolvedActors.setSourceType("NATIVE");
                    unresolvedActors.setDeletable(true);
                    unresolvedActors.setPendingDecision(false);
                    unresolvedActors.setTags(List.of());

                    JsonNode serialized = appMapper.readTree(appMapper.writeValueAsString(unresolvedActors));
                    assertSerializedAsNull(serialized, "assigneeName");
                    assertSerializedAsNull(serialized, "assigneeDisplayName");
                    assertSerializedNullsAreNullable(serialized, getWorkitem);
                    assertSerializedNullsAreNullable(serialized, listItems);

                    WorkitemVO unassignedWithoutBody = new WorkitemVO();
                    unassignedWithoutBody.setId(53049L);
                    unassignedWithoutBody.setWorkType("TASK");
                    unassignedWithoutBody.setTitle("未指派且无正文");
                    unassignedWithoutBody.setPriority(2);
                    unassignedWithoutBody.setVersion(0);
                    unassignedWithoutBody.setSourceType("NATIVE");
                    unassignedWithoutBody.setDeletable(true);
                    unassignedWithoutBody.setPendingDecision(false);
                    unassignedWithoutBody.setTags(List.of());
                    JsonNode serializedUnassigned =
                            appMapper.readTree(appMapper.writeValueAsString(unassignedWithoutBody));
                    assertSerializedAsNull(serializedUnassigned, "assigneeType");
                    assertSerializedAsNull(serializedUnassigned, "contentMd");
                    assertSerializedNullsAreNullable(serializedUnassigned, getWorkitem);
                    assertSerializedNullsAreNullable(serializedUnassigned, listItems);
                });
    }

    @SuppressWarnings("unchecked")
    private void assertSerializedNullsAreNullable(JsonNode serialized, Map<String, Object> declaredProperties) {
        List<String> violations = new ArrayList<>();
        serialized.fields().forEachRemaining(entry -> {
            Map<String, Object> declared = (Map<String, Object>) declaredProperties.get(entry.getKey());
            if (!entry.getValue().isNull() || declared == null) {
                return;
            }
            Object type = declared.get("type");
            List<String> allowed = type instanceof List
                    ? (List<String>) type
                    : List.of(String.valueOf(type));
            if (!allowed.contains("null")) {
                violations.add(entry.getKey() + " declared as " + allowed);
            }
        });
        assertTrue(violations.isEmpty(),
                "outputSchema rejects the serialized null values: " + violations);
    }

    private void assertSerializedAsNull(JsonNode serialized, String field) {
        JsonNode value = serialized.get(field);
        assertNotNull(value, "serializer dropped " + field + "; null values must reach the MCP client");
        assertTrue(value.isNull(), field + " must serialize as null in this scenario");
    }

    /** Only the derived attribution fields are in scope; the rest of the schema keeps its existing strictness. */
    @SuppressWarnings("unchecked")
    private void assertAttributionNullsAreNullable(JsonNode serialized,
            Map<String, Object> declaredProperties, String... fields) {
        List<String> violations = new ArrayList<>();
        for (String field : fields) {
            JsonNode value = serialized.get(field);
            if (value == null) {
                violations.add(field + " was dropped by the serializer");
                continue;
            }
            if (!value.isNull()) {
                violations.add(field + " did not serialize as null");
                continue;
            }
            Map<String, Object> declared = (Map<String, Object>) declaredProperties.get(field);
            if (declared == null) {
                violations.add(field + " is not declared in the outputSchema");
                continue;
            }
            Object type = declared.get("type");
            List<String> allowed = type instanceof List
                    ? (List<String>) type
                    : List.of(String.valueOf(type));
            if (!allowed.contains("null")) {
                violations.add(field + " declared as " + allowed);
            }
        }
        assertTrue(violations.isEmpty(),
                "outputSchema rejects the serialized squad attribution nulls: " + violations);
    }

    @Test
    void listToolOutputSchemasUseMcpCompatibleObjectEnvelope() {
        assertListOutputSchema(outputSchemaFor("autowonder.list_projects"), "id", "name");
        assertListOutputSchema(outputSchemaFor("autowonder.list_workitems"), "id", "title", "statusName");
        assertListOutputSchema(outputSchemaFor("autowonder.list_workitem_comments"), "id", "contentMd");
        assertListOutputSchema(outputSchemaFor("autowonder.list_status_templates"), "id", "name");
        assertListOutputSchema(outputSchemaFor("autowonder.list_sdlcs"), "id", "name", "status");
        assertListOutputSchema(outputSchemaFor("autowonder.list_agents"), "id", "name", "status");
        assertListOutputSchema(outputSchemaFor("autowonder.list_skills"), "id", "type", "name");
        assertListOutputSchema(outputSchemaFor("autowonder.list_categories"), "id", "parentId", "name");
        assertListOutputSchema(outputSchemaFor("autowonder.list_platform_skills"), "id", "name");

        assertTrue(service.listTools().stream()
                .allMatch(tool -> "object".equals(tool.getOutputSchema().get("type"))));
    }

    @Test
    void listToolCallsReturnPlainLists() {
        WorkitemVO workitem = new WorkitemVO();
        workitem.setId(1L);
        when(workitemService.list(null, null, null, null, null, false, null, 100L, 7L, null, null, null, 1, 20))
                .thenReturn(new PageResult<>(List.of(workitem), 1, 1, 20));

        SdlcVO sdlc = new SdlcVO();
        sdlc.setId(2L);
        when(sdlcService.list(100L, null, null, null, 1, 20)).thenReturn(List.of(sdlc));

        AgentVO agent = new AgentVO();
        agent.setId(3L);
        when(agentService.list(100L, null, null, null, 1, 20)).thenReturn(List.of(agent));

        SkillVO skill = new SkillVO();
        skill.setId(4L);
        when(skillService.list(100L, null, null, true, false, 1, 20)).thenReturn(List.of(skill));

        assertEquals(List.of(workitem), call(principal, "autowonder.list_workitems", Map.of()));
        assertEquals(List.of(sdlc), call(principal, "autowonder.list_sdlcs", Map.of()));
        assertEquals(List.of(agent), call(principal, "autowonder.list_agents", Map.of()));
        assertEquals(List.of(skill), call(principal, "autowonder.list_skills", Map.of()));
    }

    @Test
    void squadScopedListToolsExposeAnOptionalSquadIdFilter() {
        for (String tool : List.of("autowonder.list_sdlcs", "autowonder.list_agents",
                "autowonder.list_executors")) {
            Map<String, Object> schema = schemaFor(tool);
            Map<String, Object> squadId = property(schema, "squadId");
            assertNotNull(squadId, tool + " must declare a squadId filter");
            assertEquals("integer", squadId.get("type"), tool);
            assertNotNull(squadId.get("description"), tool);
            // Existing callers pass no squadId, so it must never become required.
            assertFalse(String.valueOf(schema.get("required")).contains("squadId"), tool);
        }
    }

    @Test
    void squadScopedListToolsExposeDerivedSquadAttribution() {
        for (String tool : List.of("autowonder.list_sdlcs", "autowonder.list_agents",
                "autowonder.list_executors")) {
            Map<String, Object> items = properties(itemsSchema(outputSchemaFor(tool)));
            assertNullableArray(items, "squadIds", "integer", tool);
            assertNullableArray(items, "squadNames", "string", tool);
        }

        Map<String, Object> squad = outputProperties(toolByName("autowonder.get_squad"));
        assertNullableArray(squad, "sdlcs", "object", "autowonder.get_squad");
        assertNullableArray(squad, "executors", "object", "autowonder.get_squad");
        assertTrue(properties(itemSchema((Map<String, Object>) squad.get("sdlcs")))
                .keySet().containsAll(List.of("id", "name", "workType", "status")));
        assertTrue(properties(itemSchema((Map<String, Object>) squad.get("executors")))
                .keySet().containsAll(List.of("id", "agentId", "agentName", "name", "status", "clientKind")));
    }

    @Test
    void serializedSquadAttributionNullsAreDeclaredNullableInOutputSchema() {
        // Squad attribution is derived, so callers that never ask for it get nulls. Only
        // mcp/dto/McpRpcResponse is @JsonInclude(NON_NULL); the nested VOs serialize their nulls into
        // structuredContent, and a strict MCP client rejects the whole tools/call response when the
        // declared outputSchema does not allow null. Serialize with the app's real mapper so that
        // mismatch is caught here rather than by digital workers.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(JacksonConfig.class)
                .run(context -> {
                    ObjectMapper appMapper = context.getBean(ObjectMapper.class);

                    AgentVO agent = new AgentVO();
                    agent.setId(3L);
                    agent.setName("全栈开发");
                    JsonNode serializedAgent = appMapper.readTree(appMapper.writeValueAsString(agent));
                    assertAttributionNullsAreNullable(serializedAgent,
                            properties(outputSchemaFor("autowonder.get_agent")), "squadIds", "squadNames");
                    assertAttributionNullsAreNullable(serializedAgent,
                            properties(itemsSchema(outputSchemaFor("autowonder.list_agents"))),
                            "squadIds", "squadNames");

                    SdlcVO sdlc = new SdlcVO();
                    sdlc.setId(2L);
                    sdlc.setName("全栈交付");
                    JsonNode serializedSdlc = appMapper.readTree(appMapper.writeValueAsString(sdlc));
                    assertAttributionNullsAreNullable(serializedSdlc,
                            properties(outputSchemaFor("autowonder.get_sdlc")), "squadIds", "squadNames");
                    assertAttributionNullsAreNullable(serializedSdlc,
                            properties(itemsSchema(outputSchemaFor("autowonder.list_sdlcs"))),
                            "squadIds", "squadNames");

                    JsonNode serializedExecutor = appMapper.readTree(
                            appMapper.writeValueAsString(executor(9L, 5L, "QODER_CLI")));
                    assertAttributionNullsAreNullable(serializedExecutor,
                            properties(outputSchemaFor("autowonder.get_executor")), "squadIds", "squadNames");
                    assertAttributionNullsAreNullable(serializedExecutor,
                            properties(itemsSchema(outputSchemaFor("autowonder.list_executors"))),
                            "squadIds", "squadNames");

                    SquadVO squad = new SquadVO();
                    squad.setId(7L);
                    squad.setName("测试小队");
                    JsonNode serializedSquad = appMapper.readTree(appMapper.writeValueAsString(squad));
                    assertAttributionNullsAreNullable(serializedSquad,
                            outputProperties(toolByName("autowonder.get_squad")), "sdlcs", "executors");
                    assertAttributionNullsAreNullable(serializedSquad,
                            properties(itemsSchema(outputSchemaFor("autowonder.list_squads"))),
                            "sdlcs", "executors");
                });
    }

    @Test
    void serializedExecutorClientKindNullIsDeclaredNullableInOutputSchema() {
        // 存量数据里 clientKind 可为 null；用应用真实的 ObjectMapper 序列化，确认 list/get 的
        // 输出 Schema 都允许 null，而不是只在声明层测试。
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(JacksonConfig.class)
                .run(context -> {
                    ObjectMapper appMapper = context.getBean(ObjectMapper.class);

                    JsonNode serializedExecutor = appMapper.readTree(
                            appMapper.writeValueAsString(executor(9L, 5L, null)));
                    assertTrue(serializedExecutor.has("clientKind"), "clientKind was dropped by the serializer");
                    assertTrue(serializedExecutor.get("clientKind").isNull(),
                            "clientKind did not serialize as null");
                    assertAttributionNullsAreNullable(serializedExecutor,
                            properties(outputSchemaFor("autowonder.get_executor")), "clientKind");
                    assertAttributionNullsAreNullable(serializedExecutor,
                            properties(itemsSchema(outputSchemaFor("autowonder.list_executors"))),
                            "clientKind");
                });
    }

    @Test
    void listSdlcsAndListAgentsPushTheSquadFilterDownToTheServices() {
        SdlcVO sdlc = new SdlcVO();
        sdlc.setId(2L);
        when(sdlcService.list(100L, null, null, List.of(7L), 1, 20)).thenReturn(List.of(sdlc));

        AgentVO agent = new AgentVO();
        agent.setId(3L);
        when(agentService.list(100L, null, null, List.of(7L), 1, 20)).thenReturn(List.of(agent));

        assertEquals(List.of(sdlc), call(principal, "autowonder.list_sdlcs", Map.of("squadId", 7L)));
        assertEquals(List.of(agent), call(principal, "autowonder.list_agents", Map.of("squadId", 7L)));

        verify(sdlcService).list(100L, null, null, List.of(7L), 1, 20);
        verify(agentService).list(100L, null, null, List.of(7L), 1, 20);
    }

    @Test
    void listExecutorsFiltersBySquadOnBothTheWorkspaceAndTheAgentScope() {
        ExecutorVO agentFive = executor(9L, 5L, "QODER_CLI");
        ExecutorVO agentSix = executor(13L, 6L, "QODER_CLI");
        when(executorService.listAll(WORKSPACE_ID, List.of(7L))).thenReturn(List.of(agentFive, agentSix));

        assertEquals(List.of(agentFive, agentSix),
                call(principal, "autowonder.list_executors", Map.of("squadId", 7L)));
        assertEquals(List.of(agentFive), call(principal, "autowonder.list_executors",
                Map.of("agentId", 5L, "squadId", 7L)));

        verify(executorService, times(2)).listAll(WORKSPACE_ID, List.of(7L));
        // squadId is pushed into SQL on both scopes; only the agentId column is narrowed in memory.
        verify(executorService, never()).listAll(eq(WORKSPACE_ID), isNull());
        verify(executorService, never()).listByAgent(anyLong(), anyLong());
    }

    @Test
    void listExecutorsAgentScopeKeepsSquadFilteringWhenAttributionIsNotWired() {
        // squadIds stays null on every row, exactly as it does when the optional attribution bean
        // is absent; narrowing on it used to silently return an empty list.
        ExecutorVO inScope = executor(9L, 5L, "QODER_CLI");
        ExecutorVO otherAgent = executor(13L, 6L, "QODER_CLI");
        when(executorService.listAll(WORKSPACE_ID, List.of(7L))).thenReturn(List.of(inScope, otherAgent));

        assertEquals(List.of(inScope), call(principal, "autowonder.list_executors",
                Map.of("agentId", 5L, "squadId", 7L)));
    }

    @SuppressWarnings("unchecked")
    private void assertNullableArray(Map<String, Object> properties, String name, String itemType,
            String tool) {
        Map<String, Object> property = (Map<String, Object>) properties.get(name);
        assertNotNull(property, tool + " must declare " + name);
        assertEquals(List.of("array", "null"), property.get("type"), tool + "." + name);
        assertEquals(itemType, ((Map<String, Object>) property.get("items")).get("type"),
                tool + "." + name);
    }

    @Test
    void addSdlcStepSchemaIncludesSupportedCreateFields() {
        Map<String, Object> schema = schemaFor("autowonder.add_sdlc_step");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertTrue(properties.keySet().containsAll(List.of("sdlcId", "stepOrder", "name", "kind",
                "instructionMd", "checklistJson", "gatePolicyJson", "required", "timeoutSeconds",
                "retryBudget", "code", "handlerType", "handlerRoleRef", "statusOnEnterCode",
                "onSuccess", "onFail")));
        assertEquals(List.of("workspaceId", "sdlcId"), schema.get("required"));
    }

    @Test
    void updateSdlcStepSchemaOnlyIncludesSupportedUpdateFields() {
        Map<String, Object> schema = schemaFor("autowonder.update_sdlc_step");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertTrue(properties.keySet().containsAll(List.of("sdlcId", "stepId", "name", "kind",
                "instructionMd", "checklistJson", "gatePolicyJson", "required", "timeoutSeconds",
                "retryBudget", "code", "handlerType", "handlerRoleRef", "statusOnEnterCode",
                "onSuccess", "onFail")));
        assertFalse(properties.containsKey("stepOrder"));
        assertEquals(List.of("workspaceId", "sdlcId", "stepId"), schema.get("required"));
    }

    @Test
    void sdlcMutationToolsAdvertiseEnabledLifecycle() {
        for (String name : List.of("autowonder.update_sdlc", "autowonder.add_sdlc_step",
                "autowonder.update_sdlc_step", "autowonder.delete_sdlc_step",
                "autowonder.reorder_sdlc_steps")) {
            assertTrue(toolFor(name).getDescription().contains("including enabled"), name);
        }
    }

    @Test
    void updateSdlcStepAdvertisesActiveFlowContentEdits() {
        String description = toolFor("autowonder.update_sdlc_step").getDescription();
        assertTrue(description.contains("active flows"), description);
        assertTrue(description.contains("checklistJson"), description);
    }

    @Test
    void updateSdlcStepDocumentsPatchSemantics() {
        String description = toolFor("autowonder.update_sdlc_step").getDescription();
        assertTrue(description.contains("Omitted fields keep their current values"), description);
        assertTrue(description.contains("pass an empty string to clear a nullable field"), description);
    }

    @Test
    void sdlcStepToolsDocumentChecklistAndGatePolicyExamples() {
        for (String name : List.of("autowonder.add_sdlc_step", "autowonder.update_sdlc_step")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> properties =
                    (Map<String, Object>) schemaFor(name).get("properties");
            @SuppressWarnings("unchecked")
            Map<String, Object> checklist = (Map<String, Object>) properties.get("checklistJson");
            @SuppressWarnings("unchecked")
            Map<String, Object> gatePolicy = (Map<String, Object>) properties.get("gatePolicyJson");
            assertTrue(String.valueOf(checklist.get("description")).contains("e.g."), name);
            assertTrue(String.valueOf(gatePolicy.get("description")).contains("passCriteria"), name);
        }
    }

    @Test
    void listProjectsOutputSchemaExposesTheAccessLevel() {
        Map<String, Object> itemSchema = itemsSchema(
                outputSchemaFor("autowonder.list_projects"));

        assertTrue(properties(itemSchema).containsKey("accessLevel"));
        assertTrue(properties(itemSchema).containsKey("id"));
    }

    @Test
    void listProjectsDiscoversEveryAccessibleWorkspaceWithItsAccessLevel() {
        WorkspaceVO first = new WorkspaceVO();
        first.setId(100L);
        first.setName("token-workspace");
        first.setAccessLevel(WorkspaceAccessLevel.ADMIN);
        WorkspaceVO second = new WorkspaceVO();
        second.setId(200L);
        second.setName("other-workspace");
        second.setAccessLevel(WorkspaceAccessLevel.READ_ONLY);
        when(workspaceService.listByUserWithAccess(USER_ID)).thenReturn(List.of(first, second));

        Object result = service.call(
                McpAccessTokenService.Principal.personal(USER_ID, 1L),
                "autowonder.list_projects", Map.of());

        assertEquals(List.of(first, second), result);
        verify(workspaceService).listByUserWithAccess(USER_ID);
        verify(workspaceService, never()).getCurrent(anyLong());
    }

    @Test
    void taskScopedTokenListsOnlyItsOwnWorkspace() {
        WorkspaceVO pinned = new WorkspaceVO();
        pinned.setId(WORKSPACE_ID);
        when(workspaceService.scopedWorkspace(WORKSPACE_ID, WorkspaceAccessLevel.READ_WRITE)).thenReturn(pinned);

        Object result = service.call(dispatchPrincipal(-321L),
                "autowonder.list_projects", Map.of());

        assertEquals(List.of(pinned), result);
        verify(workspaceService, never()).listByUserWithAccess(anyLong());
    }

    @Test
    void listProjectsNeedsNoWorkspaceIdAndNoMembershipResolution() {
        when(workspaceService.listByUserWithAccess(USER_ID)).thenReturn(List.of());

        service.call(McpAccessTokenService.Principal.personal(USER_ID, 1L),
                "autowonder.list_projects", Map.of());

        verify(workspaceService, never()).activeAccessLevel(anyLong(), anyLong());
    }

    @Test
    void createWorkitemChecksPermissionAndDelegates() {
        WorkitemVO created = new WorkitemVO();
        created.setId(99L);
        when(workitemService.create(any(CreateWorkitemRequest.class), eq(100L), eq(7L))).thenReturn(created);

        Object result = call(principal, "autowonder.create_workitem",
                Map.of("workType", "REQ", "title", "MCP create", "contentMd", "body"));

        assertSame(created, result);
        verify(workitemService).create(argThat(req ->
                "REQ".equals(req.getWorkType()) && "MCP create".equals(req.getTitle())), eq(100L), eq(7L));
    }

    @Test
    void createWorkitemSchemaExposesOptionalAssigneeFields() {
        Map<String, Object> schema = schemaFor("autowonder.create_workitem");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertTrue(properties.keySet().containsAll(List.of("workType", "title", "contentMd",
                "priority", "assigneeType", "assigneeRef", "sdlcId", "squadId", "scheduledStartAt")));
        // Assignee fields are optional so existing callers keep working; only
        // workType and title remain required.
        assertEquals(List.of("workspaceId", "workType", "title"), schema.get("required"));
    }

    @Test
    void createWorkitemDelegatesAssigneeFieldsToService() {
        WorkitemVO created = new WorkitemVO();
        created.setId(99L);
        when(workitemService.create(any(CreateWorkitemRequest.class), eq(100L), eq(7L))).thenReturn(created);

        Object result = call(principal, "autowonder.create_workitem",
                Map.of("workType", "REQ", "title", "MCP create", "assigneeType", "AGENT",
                        "assigneeRef", 12L, "sdlcId", 8L, "squadId", 4L));

        assertSame(created, result);
        verify(workitemService).create(argThat(req -> "REQ".equals(req.getWorkType())
                && "AGENT".equals(req.getAssigneeType())
                && Long.valueOf(12L).equals(req.getAssigneeRef())
                && Long.valueOf(8L).equals(req.getSdlcId())
                && Long.valueOf(4L).equals(req.getSquadId())), eq(100L), eq(7L));
    }

    @Test
    void assignWorkitemDelegatesWithDeliveryOptions() {
        WorkitemVO assigned = new WorkitemVO();
        assigned.setId(99L);
        when(workitemService.assign(99L, "AGENT", 12L, 8L, 4L, null, 100L, 7L)).thenReturn(assigned);

        Object result = call(principal, "autowonder.assign_workitem",
                Map.of("id", 99L, "assigneeType", "AGENT", "assigneeRef", 12L, "sdlcId", 8L, "squadId", 4L));

        assertSame(assigned, result);
    }

    @Test
    void assignWorkitemParsesScheduledStartAtIsoInstant() {
        WorkitemVO assigned = new WorkitemVO();
        assigned.setId(99L);
        java.util.Date scheduled = java.util.Date.from(java.time.Instant.parse("2026-09-01T02:00:00Z"));
        when(workitemService.assign(99L, "AGENT", 12L, null, null, scheduled, 100L, 7L)).thenReturn(assigned);

        Object result = call(principal, "autowonder.assign_workitem",
                Map.of("id", 99L, "assigneeType", "AGENT", "assigneeRef", 12L,
                        "scheduledStartAt", "2026-09-01T02:00:00Z"));

        assertSame(assigned, result);
    }

    @Test
    void assignWorkitemRejectsInvalidScheduledStartAt() {
        assertThrows(BizException.class, () -> call(principal, "autowonder.assign_workitem",
                Map.of("id", 99L, "assigneeType", "AGENT", "assigneeRef", 12L,
                        "scheduledStartAt", "not-a-time")));

        verify(workitemService, never()).assign(anyLong(), anyString(), any(), any(), any(),
                any(java.util.Date.class), anyLong(), anyLong());
    }

    @Test
    void assignWorkitemSchemaExposesScheduledStartAt() {
        Map<String, Object> assignProperties = property(schemaFor("autowonder.assign_workitem"), "scheduledStartAt");
        assertEquals("string", assignProperties.get("type"));
        Map<String, Object> createProperties = property(schemaFor("autowonder.create_workitem"), "scheduledStartAt");
        assertEquals("string", createProperties.get("type"));
        Map<String, Object> tagProperties = property(schemaFor("autowonder.list_workitems"), "tag");
        assertEquals("string", tagProperties.get("type"));
    }

    @Test
    void scheduledStartAtDescriptionWarnsNotToFillWithoutExplicitUserRequest() {
        for (String toolName : new String[] { "autowonder.create_workitem", "autowonder.assign_workitem" }) {
            Map<String, Object> props = property(schemaFor(toolName), "scheduledStartAt");
            String description = (String) props.get("description");
            assertNotNull(description, toolName + " scheduledStartAt description missing");
            assertTrue(description.startsWith("Optional."), toolName + " must state the parameter is optional");
            assertTrue(description.contains("ISO-8601 instant"), toolName + " must state the time format");
            assertTrue(description.contains("Do not fill this parameter unless the user explicitly "
                    + "requests scheduled execution"),
                    toolName + " must instruct not to fill without an explicit user request");
        }
    }

    @Test
    void dispatchTokenAssignmentUsesDispatchAgentAsActorAndKeepsDispatchScope() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal(-321L);
        DispatchDO dispatch = dispatch(321L, 100L, 99L, 40014L);
        AgentVO agent = new AgentVO();
        agent.setId(40014L);
        agent.setName("AW开发数字人");
        WorkitemVO assigned = new WorkitemVO();
        assigned.setId(99L);
        when(dispatchDao.findById(321L)).thenReturn(dispatch);
        when(agentService.get(40014L, WORKSPACE_ID)).thenReturn(agent);
        when(workitemService.assignAs(99L, "HUMAN", 77L, null, 4L, null, 100L, 7L,
                AssignmentActor.agent(40014L, "AW开发数字人"))).thenReturn(assigned);

        Object result = call(dispatchPrincipal, "autowonder.assign_workitem",
                Map.of("id", 99L, "assigneeType", "HUMAN", "assigneeRef", 77L, "squadId", 4L));

        assertSame(assigned, result);
        verify(workitemService).assignAs(99L, "HUMAN", 77L, null, 4L, null, 100L, 7L,
                AssignmentActor.agent(40014L, "AW开发数字人"));
        verify(workitemService, never()).assign(anyLong(), anyString(), any(), any(), any(), any(), anyLong(), anyLong());
    }

    @Test
    void dispatchTokenCannotAssignAnotherWorkitem() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal(-321L);
        when(dispatchDao.findById(321L)).thenReturn(dispatch(321L, 100L, 98L, 40014L));

        assertThrows(BizException.class, () -> call(dispatchPrincipal,
                "autowonder.assign_workitem",
                Map.of("id", 99L, "assigneeType", "HUMAN", "assigneeRef", 77L)));

        verify(workitemService, never()).assignAs(anyLong(), anyString(), any(), any(), any(), any(), anyLong(), anyLong(), any());
        verify(workitemService, never()).assign(anyLong(), anyString(), any(), any(), any(), any(), anyLong(), anyLong());
    }

    @Test
    void addWorkitemCommentSchemaExposesTargetAgentIds() {
        Map<String, Object> targetAgentIds = property(
                schemaFor("autowonder.add_workitem_comment"), "targetAgentIds");
        Map<String, Object> targetHumanIds = property(
                schemaFor("autowonder.add_workitem_comment"), "targetHumanIds");

        assertEquals("array", targetAgentIds.get("type"));
        assertEquals("integer", itemSchema(targetAgentIds).get("type"));
        assertEquals("array", targetHumanIds.get("type"));
        assertEquals("integer", itemSchema(targetHumanIds).get("type"));
    }

    @Test
    void addWorkitemCommentCreatesDistinctTargetInteractions() {
        CommentVO comment = new CommentVO();
        comment.setId(55L);
        when(workitemService.addComment(99L, "@workers please review",
                java.util.Arrays.asList(9L, null, 9L), 100L, 7L)).thenReturn(comment);

        Object result = call(principal, "autowonder.add_workitem_comment",
                Map.of("id", 99L, "contentMd", "@workers please review",
                        "targetAgentIds", java.util.Arrays.asList(12L, null, 12L, 13L),
                        "targetHumanIds", java.util.Arrays.asList(9L, null, 9L)));

        assertSame(comment, result);
        verify(workitemService).addComment(99L, "@workers please review",
                java.util.Arrays.asList(9L, null, 9L), 100L, 7L);
        verify(guidanceService).createForComment(100L, 99L, 55L, "@workers please review",
                java.util.Arrays.asList(12L, null, 12L, 13L), 7L);
        verifyNoMoreInteractions(guidanceService);
    }

    @Test
    void dispatchTokenAddsCommentAsDispatchAgent() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal(-321L);
        DispatchDO dispatch = dispatch(321L, 100L, 99L, 40014L);
        CommentVO comment = new CommentVO();
        comment.setId(56L);
        when(dispatchDao.findById(321L)).thenReturn(dispatch);
        when(workitemService.addAgentComment(99L, "review finished", List.of(77L), 100L, 40014L, 7L))
                .thenReturn(comment);

        Object result = call(dispatchPrincipal, "autowonder.add_workitem_comment",
                Map.of("id", 99L, "contentMd", "review finished", "targetHumanIds", List.of(77L)));

        assertSame(comment, result);
        verify(workitemService).addAgentComment(99L, "review finished", List.of(77L), 100L, 40014L, 7L);
        verify(workitemService, never()).addComment(anyLong(), anyString(), anyList(), anyLong(), anyLong());
        verifyNoInteractions(capabilityGuard);
    }

    @Test
    void dispatchTokenCannotAddCommentToAnotherWorkitem() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal(-321L);
        when(dispatchDao.findById(321L)).thenReturn(dispatch(321L, 100L, 98L, 40014L));

        assertThrows(BizException.class, () -> call(dispatchPrincipal,
                "autowonder.add_workitem_comment", Map.of("id", 99L, "contentMd", "wrong scope")));

        verify(workitemService, never()).addAgentComment(anyLong(), anyString(), anyList(), anyLong(), anyLong(), any());
        verify(workitemService, never()).addComment(anyLong(), anyString(), anyList(), anyLong(), anyLong());
    }

    @Test
    void scheduledRunDispatchCommentNeverFallsThroughToEqualNumberedWorkitem() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal(-321L);
        DispatchDO dispatch = dispatch(321L, 100L, 99L, 40014L);
        dispatch.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        ScheduledTaskRunCommentService runComments = mock(ScheduledTaskRunCommentService.class);
        CommentVO comment = new CommentVO(); comment.setId(56L);
        ReflectionTestUtils.setField(service, "scheduledTaskRunCommentService", runComments);
        when(dispatchDao.findById(321L)).thenReturn(dispatch);
        when(runComments.addAgentComment(100L, 99L, 40014L, "run-only", List.of(), List.of())).thenReturn(comment);

        assertSame(comment, call(dispatchPrincipal, "autowonder.add_workitem_comment",
                Map.of("id", 99L, "contentMd", "run-only")));

        verify(runComments).addAgentComment(100L, 99L, 40014L, "run-only", List.of(), List.of());
        verifyNoInteractions(workitemService, guidanceService);
    }

    @Test
    void unavailableScheduledDispatchFailsBeforeRunCommentOrEqualNumberedWorkitem() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal(-321L);
        DispatchDO dispatch = dispatch(321L, 100L, 99L, 40014L);
        dispatch.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        ScheduledTaskRunCommentService runComments = mock(ScheduledTaskRunCommentService.class);
        ReflectionTestUtils.setField(service, "scheduledTaskRunCommentService", runComments);
        when(dispatchDao.findById(321L)).thenReturn(dispatch);
        doThrow(new BizException(ErrorCode.SCHEDULED_TASK_SCHEMA_NOT_READY))
                .when(capabilityGuard).requireAvailable("mcp");

        BizException failure = assertThrows(BizException.class, () -> call(dispatchPrincipal,
                "autowonder.add_workitem_comment", Map.of("id", 99L, "contentMd", "run-only")));

        assertEquals("30006", failure.getCode());
        verifyNoInteractions(runComments, workitemService, guidanceService);
    }

    @Test
    void unavailableScheduledDispatchRejectsGenericMutationBeforeWorkitemService() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal(-321L);
        DispatchDO dispatch = dispatch(321L, 100L, 99L, 40014L);
        dispatch.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        when(dispatchDao.findById(321L)).thenReturn(dispatch);
        doThrow(new BizException(ErrorCode.SCHEDULED_TASK_SCHEMA_NOT_READY))
                .when(capabilityGuard).requireAvailable("mcp");

        BizException failure = assertThrows(BizException.class, () -> call(dispatchPrincipal,
                "autowonder.update_workitem", Map.of("id", 99L, "title", "must-not-mutate")));

        assertEquals("30006", failure.getCode());
        verifyNoInteractions(workitemService);
    }

    @Test
    void scheduledRunDispatchCommentForwardsExplicitGuidanceTargets() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal(-321L);
        DispatchDO dispatch = dispatch(321L, 100L, 99L, 40014L);
        dispatch.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        ScheduledTaskRunCommentService runComments = mock(ScheduledTaskRunCommentService.class);
        ReflectionTestUtils.setField(service, "scheduledTaskRunCommentService", runComments);
        when(dispatchDao.findById(321L)).thenReturn(dispatch);
        when(runComments.addAgentComment(100L, 99L, 40014L, "@tester", List.of(40015L), List.of()))
                .thenReturn(new CommentVO());

        service.call(dispatchPrincipal, "autowonder.add_workitem_comment",
                Map.of("id", 99L, "contentMd", "@tester", "targetAgentIds", List.of(40015L)));

        verify(runComments).addAgentComment(100L, 99L, 40014L, "@tester", List.of(40015L), List.of());
        verifyNoInteractions(workitemService, guidanceService);
    }

    @Test
    void scheduledRunDispatchCommentForwardsExplicitHumanTargets() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal(-321L);
        DispatchDO dispatch = dispatch(321L, 100L, 99L, 40014L);
        dispatch.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        ScheduledTaskRunCommentService runComments = mock(ScheduledTaskRunCommentService.class);
        ReflectionTestUtils.setField(service, "scheduledTaskRunCommentService", runComments);
        when(dispatchDao.findById(321L)).thenReturn(dispatch);
        when(runComments.addAgentComment(100L, 99L, 40014L, "分析完成 @蔡何", List.of(), List.of(10000L)))
                .thenReturn(new CommentVO());

        service.call(dispatchPrincipal, "autowonder.add_workitem_comment",
                Map.of("id", 99L, "contentMd", "分析完成 @蔡何", "targetHumanIds", List.of(10000L)));

        verify(runComments).addAgentComment(100L, 99L, 40014L, "分析完成 @蔡何", List.of(), List.of(10000L));
        verifyNoInteractions(workitemService, guidanceService);
    }

    @Test
    void workitemDispatchTokenDoesNotImposeUniversalWorkitemScopeOnOtherTools() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal(-321L);
        WorkitemVO workitem = new WorkitemVO();
        workitem.setId(123L);
        when(workitemService.get(123L)).thenReturn(workitem);

        Object result = call(
                dispatchPrincipal, "autowonder.get_workitem", Map.of("id", 123L));

        assertSame(workitem, result);
        verify(workitemService).get(123L);
        verify(dispatchDao).findById(321L);
        verifyNoInteractions(capabilityGuard);
    }

    @Test
    void scheduledTaskToolsAreRegisteredWithRequiredArgumentsAndEnums() {
        Map<String, Object> create = toolByName("autowonder.create_scheduled_task").getInputSchema();
        assertEquals(List.of("workspaceId", "name", "instructionMd", "squadId", "initialAgentId",
                "scheduleType", "timezone"), create.get("required"));
        assertEquals(List.of("CRON", "ONCE"),
                ((Map<?, ?>) properties(create).get("scheduleType")).get("enum"));

        Map<String, Object> update = toolByName("autowonder.update_scheduled_task").getInputSchema();
        assertEquals(List.of("workspaceId", "id", "version"), update.get("required"));

        Map<String, Object> transition = toolByName("autowonder.transition_scheduled_task").getInputSchema();
        assertEquals(List.of("workspaceId", "id", "action", "version"), transition.get("required"));
        assertEquals(List.of("enable", "pause", "archive", "run-now", "pause-run", "resume-run", "cancel-run"),
                ((Map<?, ?>) properties(transition).get("action")).get("enum"));

        assertEquals(List.of("ACTIVE", "PAUSED", "EXHAUSTED", "ARCHIVED"),
                ((Map<?, ?>) properties(toolByName("autowonder.list_scheduled_tasks").getInputSchema())
                        .get("status")).get("enum"));
        assertEquals(List.of("workspaceId", "runId"),
                toolByName("autowonder.get_scheduled_task_run").getInputSchema().get("required"));
        assertEquals(List.of("workspaceId", "runId", "contentMd"),
                toolByName("autowonder.add_scheduled_task_run_comment").getInputSchema().get("required"));
        assertEquals(List.of("workspaceId", "id"),
                toolByName("autowonder.list_scheduled_task_runs").getInputSchema().get("required"));
        assertEquals(List.of("workspaceId", "id", "version"),
                toolByName("autowonder.delete_scheduled_task").getInputSchema().get("required"));

        assertNotNull(toolByName("autowonder.get_scheduled_task").getOutputSchema());
        assertTrue(outputProperties(toolByName("autowonder.list_scheduled_tasks")).containsKey("list"));
        assertTrue(outputProperties(toolByName("autowonder.list_scheduled_tasks")).containsKey("total"));
        assertNotNull(toolByName("autowonder.transition_scheduled_task").getOutputSchema().get("anyOf"));
    }

    @Test
    void scheduledTaskSchemasAlignWithValidatorEnumsAndExposeTimeoutFields() {
        // MCP schema 必须使用校验器认的枚举值，否则按 schema 传参必被拒。
        Map<String, Object> create = properties(toolByName("autowonder.create_scheduled_task").getInputSchema());
        assertEquals(List.of("ISOLATED", "CONTINUOUS"), ((Map<?, ?>) create.get("sessionMode")).get("enum"));
        assertEquals(List.of("SKIP", "QUEUE", "ALLOW"), ((Map<?, ?>) create.get("overlapPolicy")).get("enum"));
        assertEquals(List.of("FIRE_LATEST", "FIRE_ALL", "SKIP_ALL"),
                ((Map<?, ?>) create.get("misfirePolicy")).get("enum"));

        Map<String, Object> update = properties(toolByName("autowonder.update_scheduled_task").getInputSchema());
        assertEquals(List.of("ISOLATED", "CONTINUOUS"), ((Map<?, ?>) update.get("sessionMode")).get("enum"));
        assertEquals(List.of("SKIP", "QUEUE", "ALLOW"), ((Map<?, ?>) update.get("overlapPolicy")).get("enum"));
        assertEquals(List.of("FIRE_LATEST", "FIRE_ALL", "SKIP_ALL"),
                ((Map<?, ?>) update.get("misfirePolicy")).get("enum"));
        assertTrue(update.containsKey("startDeadlineSeconds"));
        assertTrue(update.containsKey("affinityTimeoutSeconds"));
    }

    @Test
    void listScheduledTaskRunsOutputSchemaOmitsTotalAndUsesRunShape() {
        McpToolVO tool = toolByName("autowonder.list_scheduled_task_runs");
        assertEquals(List.of("list"), tool.getOutputSchema().get("required"));
        Map<String, Object> properties = outputProperties(tool);
        assertEquals(Set.of("list", "offset", "size"), properties.keySet());
        assertEquals("integer",
                ((Map<?, ?>) properties.get("offset")).get("type"));
        assertEquals("array", ((Map<?, ?>) properties.get("list")).get("type"));
    }

    @Test
    void deleteScheduledTaskOutputSchemaReportsDeleted() {
        assertTrue(outputProperties(toolByName("autowonder.delete_scheduled_task")).containsKey("deleted"));
    }

    @Test
    void scheduledTaskToolDescriptionsGuideDocumentUploadThroughCli() {
        String getDescription = toolByName("autowonder.get_scheduled_task").getDescription();
        assertTrue(getDescription.contains("autowonder.workitem_cli_upload_token"));
        assertTrue(getDescription.contains("scheduled-task upload"));
        assertTrue(getDescription.contains("--scheduled-task-id"));
        assertTrue(getDescription.contains("AUTOWONDER_UPLOAD_TOKEN"));

        String createDescription = toolByName("autowonder.create_scheduled_task").getDescription();
        assertTrue(createDescription.contains("scheduled-task upload")
                || createDescription.contains("get_scheduled_task"));
    }

    @Test
    void dispatchCredentialCannotCallScheduledTaskManagementTools() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal(-321L);
        for (String tool : List.of("autowonder.create_scheduled_task", "autowonder.list_scheduled_tasks",
                "autowonder.update_scheduled_task", "autowonder.transition_scheduled_task",
                "autowonder.delete_scheduled_task")) {
            BizException exception = assertThrows(BizException.class,
                    () -> call(dispatchPrincipal, tool, Map.of()));
            assertEquals("10403", exception.getCode(), tool);
        }
    }

    @Test
    void scheduledTaskToolsFailClosedWhenCapabilityUnavailable() {
        doThrow(new BizException(ErrorCode.SCHEDULED_TASK_SCHEMA_NOT_READY))
                .when(capabilityGuard).requireAvailable("mcp");

        BizException exception = assertThrows(BizException.class,
                () -> call(principal, "autowonder.list_scheduled_tasks", Map.of()));
        assertEquals("30006", exception.getCode());
    }

    @Test
    void scheduledTaskToolsFailClosedWhenDependencyMissing() {
        BizException exception = assertThrows(BizException.class,
                () -> call(principal, "autowonder.list_scheduled_tasks", Map.of()));
        assertEquals("30006", exception.getCode());
    }

    @Test
    void listScheduledTasksDelegatesAndReturnsPagedEnvelope() {
        ScheduledTaskService taskService = mock(ScheduledTaskService.class);
        ReflectionTestUtils.setField(service, "scheduledTaskService", taskService);
        ScheduledTaskVO task = new ScheduledTaskVO();
        task.setId(11L);
        task.setName("nightly");
        task.setStatus("ACTIVE");
        task.setVersion(3);
        when(taskService.list(WORKSPACE_ID, "ACTIVE", null, null, null, 20, 0))
                .thenReturn(new PageResult<>(List.of(task), 1L, 1, 20));

        Map<?, ?> result = (Map<?, ?>) call(principal, "autowonder.list_scheduled_tasks",
                Map.of("status", "active"));

        assertEquals(1L, ((Number) result.get("total")).longValue());
        assertEquals(0, ((Number) result.get("offset")).intValue());
        assertEquals(20, ((Number) result.get("size")).intValue());
        assertEquals(11L, ((Number) ((Map<?, ?>) ((List<?>) result.get("list")).get(0)).get("id")).longValue());

        BizException badStatus = assertThrows(BizException.class,
                () -> call(principal, "autowonder.list_scheduled_tasks", Map.of("status", "RUNNING")));
        assertEquals("27003", badStatus.getCode());
        BizException badSize = assertThrows(BizException.class,
                () -> call(principal, "autowonder.list_scheduled_tasks", Map.of("size", 101)));
        assertEquals("27003", badSize.getCode());
    }

    @Test
    void createScheduledTaskDelegatesWithParsedArgumentsAndFirePreviews() {
        ScheduledTaskService taskService = mock(ScheduledTaskService.class);
        ReflectionTestUtils.setField(service, "scheduledTaskService", taskService);
        ScheduledTaskVO created = new ScheduledTaskVO();
        created.setId(77L);
        created.setScheduleType("CRON");
        created.setCronExpression("0 0 2 * * *");
        created.setTimezone("Asia/Shanghai");
        when(taskService.create(any(CreateScheduledTaskRequest.class), eq(WORKSPACE_ID), eq(USER_ID)))
                .thenReturn(created);
        when(taskService.preview("0 0 2 * * *", "Asia/Shanghai", 5))
                .thenReturn(List.of(Instant.parse("2026-08-26T18:00:00Z")));

        Map<?, ?> result = (Map<?, ?>) call(principal, "autowonder.create_scheduled_task", Map.of(
                "name", "nightly", "instructionMd", "run it", "squadId", 42L, "initialAgentId", 9L,
                "scheduleType", "CRON", "cronExpression", "0 0 2 * * *", "timezone", "Asia/Shanghai"));

        ArgumentCaptor<CreateScheduledTaskRequest> captor =
                ArgumentCaptor.forClass(CreateScheduledTaskRequest.class);
        verify(taskService).create(captor.capture(), eq(WORKSPACE_ID), eq(USER_ID));
        assertEquals("nightly", captor.getValue().getName());
        assertEquals(42L, captor.getValue().getSquadId());
        assertEquals("0 0 2 * * *", captor.getValue().getCronExpression());
        assertEquals(List.of("2026-08-26T18:00:00Z"), result.get("nextFirePreviews"));

        BizException badRunAt = assertThrows(BizException.class,
                () -> call(principal, "autowonder.create_scheduled_task", Map.of(
                        "name", "once", "instructionMd", "run", "squadId", 42L, "initialAgentId", 9L,
                        "scheduleType", "ONCE", "timezone", "Asia/Shanghai", "runAt", "not-a-time")));
        assertEquals("27003", badRunAt.getCode());
    }

    @Test
    void updateScheduledTaskRequiresOwnerOrAdminAndValidVersion() {
        ScheduledTaskService taskService = mock(ScheduledTaskService.class);
        ReflectionTestUtils.setField(service, "scheduledTaskService", taskService);
        ScheduledTaskVO existing = new ScheduledTaskVO();
        existing.setId(11L);
        existing.setCreatorId(8L);
        existing.setVersion(3);
        when(taskService.get(11L, WORKSPACE_ID)).thenReturn(existing);

        BizException notOwner = assertThrows(BizException.class,
                () -> call(principal, "autowonder.update_scheduled_task",
                        Map.of("id", 11L, "version", 3L, "name", "renamed")));
        assertEquals("10403", notOwner.getCode());

        BizException missingVersion = assertThrows(BizException.class,
                () -> call(principal(WorkspaceAccessLevel.ADMIN), "autowonder.update_scheduled_task",
                        Map.of("id", 11L, "name", "renamed")));
        assertEquals("27003", missingVersion.getCode());

        ScheduledTaskVO updated = new ScheduledTaskVO();
        updated.setId(11L);
        updated.setName("renamed");
        updated.setVersion(4);
        when(taskService.update(eq(11L), any(), eq(WORKSPACE_ID), eq(USER_ID))).thenReturn(updated);

        Map<?, ?> result = (Map<?, ?>) call(principal(WorkspaceAccessLevel.ADMIN),
                "autowonder.update_scheduled_task", Map.of("id", 11L, "version", 3L, "name", "renamed"));
        assertEquals("renamed", result.get("name"));
        assertEquals(4L, ((Number) result.get("version")).longValue());
    }

    @Test
    void updateScheduledTaskBackfillsOmittedFieldsFromCurrentTask() {
        ScheduledTaskService taskService = mock(ScheduledTaskService.class);
        ReflectionTestUtils.setField(service, "scheduledTaskService", taskService);
        ScheduledTaskVO existing = scheduledTask("CRON");
        when(taskService.get(11L, WORKSPACE_ID)).thenReturn(existing);
        when(taskService.update(eq(11L), any(UpdateScheduledTaskRequest.class), eq(WORKSPACE_ID), eq(USER_ID)))
                .thenReturn(existing);

        Map<?, ?> result = (Map<?, ?>) call(principal, "autowonder.update_scheduled_task",
                Map.of("id", 11L, "version", 3L, "name", "renamed"));

        ArgumentCaptor<UpdateScheduledTaskRequest> captor =
                ArgumentCaptor.forClass(UpdateScheduledTaskRequest.class);
        verify(taskService).update(eq(11L), captor.capture(), eq(WORKSPACE_ID), eq(USER_ID));
        UpdateScheduledTaskRequest request = captor.getValue();
        assertEquals(3, request.getVersion().intValue());
        assertEquals("renamed", request.getName());
        assertEquals("run it", request.getInstructionMd());
        assertEquals(42L, request.getSquadId());
        assertEquals(9L, request.getInitialAgentId());
        assertEquals("CRON", request.getScheduleType());
        assertEquals("0 0 2 * * *", request.getCronExpression());
        assertEquals("Asia/Shanghai", request.getTimezone());
        assertEquals("ISOLATED", request.getSessionMode());
        assertEquals("SKIP", request.getOverlapPolicy());
        assertEquals("FIRE_LATEST", request.getMisfirePolicy());
        assertEquals(21_600, request.getStartDeadlineSeconds().intValue());
        assertEquals(1_800, request.getAffinityTimeoutSeconds().intValue());
        assertNull(request.getRunAt());
        // 响应回传服务端结果，回填后的超时字段必须仍在。
        assertEquals(21_600, ((Number) result.get("startDeadlineSeconds")).intValue());
        assertEquals(1_800, ((Number) result.get("affinityTimeoutSeconds")).intValue());
    }

    @Test
    void updateScheduledTaskForwardsExplicitTimeoutAndModeFields() {
        ScheduledTaskService taskService = mock(ScheduledTaskService.class);
        ReflectionTestUtils.setField(service, "scheduledTaskService", taskService);
        ScheduledTaskVO existing = scheduledTask("CRON");
        when(taskService.get(11L, WORKSPACE_ID)).thenReturn(existing);
        when(taskService.update(eq(11L), any(UpdateScheduledTaskRequest.class), eq(WORKSPACE_ID), eq(USER_ID)))
                .thenReturn(existing);

        call(principal, "autowonder.update_scheduled_task", Map.of(
                "id", 11L, "version", 3L, "startDeadlineSeconds", 90, "affinityTimeoutSeconds", 45,
                "sessionMode", "CONTINUOUS", "overlapPolicy", "QUEUE", "misfirePolicy", "SKIP_ALL"));

        ArgumentCaptor<UpdateScheduledTaskRequest> captor =
                ArgumentCaptor.forClass(UpdateScheduledTaskRequest.class);
        verify(taskService).update(eq(11L), captor.capture(), eq(WORKSPACE_ID), eq(USER_ID));
        assertEquals(90, captor.getValue().getStartDeadlineSeconds().intValue());
        assertEquals(45, captor.getValue().getAffinityTimeoutSeconds().intValue());
        assertEquals("CONTINUOUS", captor.getValue().getSessionMode());
        assertEquals("QUEUE", captor.getValue().getOverlapPolicy());
        assertEquals("SKIP_ALL", captor.getValue().getMisfirePolicy());
    }

    @Test
    void updateScheduledTaskClearsCounterpartFieldOnlyOnScheduleTypeSwitch() {
        ScheduledTaskService taskService = mock(ScheduledTaskService.class);
        ReflectionTestUtils.setField(service, "scheduledTaskService", taskService);
        ScheduledTaskVO existing = scheduledTask("CRON");
        when(taskService.get(11L, WORKSPACE_ID)).thenReturn(existing);
        when(taskService.update(eq(11L), any(UpdateScheduledTaskRequest.class), eq(WORKSPACE_ID), eq(USER_ID)))
                .thenReturn(existing);

        call(principal, "autowonder.update_scheduled_task", Map.of(
                "id", 11L, "version", 3L, "scheduleType", "ONCE", "runAt", "2026-09-01T00:00:00Z"));

        ArgumentCaptor<UpdateScheduledTaskRequest> first =
                ArgumentCaptor.forClass(UpdateScheduledTaskRequest.class);
        verify(taskService).update(eq(11L), first.capture(), eq(WORKSPACE_ID), eq(USER_ID));
        assertEquals("ONCE", first.getValue().getScheduleType());
        assertEquals(Date.from(Instant.parse("2026-09-01T00:00:00Z")), first.getValue().getRunAt());
        assertNull(first.getValue().getCronExpression());

        existing.setScheduleType("ONCE");
        existing.setRunAt(Date.from(Instant.parse("2026-09-01T00:00:00Z")));
        existing.setCronExpression(null);
        call(principal, "autowonder.update_scheduled_task", Map.of(
                "id", 11L, "version", 3L, "scheduleType", "CRON", "cronExpression", "0 0 3 * * *"));

        ArgumentCaptor<UpdateScheduledTaskRequest> second =
                ArgumentCaptor.forClass(UpdateScheduledTaskRequest.class);
        verify(taskService, times(2)).update(eq(11L), second.capture(), eq(WORKSPACE_ID), eq(USER_ID));
        assertEquals("CRON", second.getValue().getScheduleType());
        assertEquals("0 0 3 * * *", second.getValue().getCronExpression());
        assertNull(second.getValue().getRunAt());
    }

    @Test
    void listScheduledTaskRunsReturnsPagedEnvelopeWithoutTotal() {
        ScheduledTaskService taskService = mock(ScheduledTaskService.class);
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        ReflectionTestUtils.setField(service, "scheduledTaskService", taskService);
        ReflectionTestUtils.setField(service, "scheduledTaskRunDao", runDao);
        ScheduledTaskVO task = new ScheduledTaskVO();
        task.setId(11L);
        task.setCreatorId(USER_ID);
        task.setVersion(1);
        when(taskService.get(11L, WORKSPACE_ID)).thenReturn(task);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(99L);
        run.setWorkspaceId(WORKSPACE_ID);
        run.setScheduledTaskId(11L);
        run.setStatus("SUCCEEDED");
        run.setVersion(2);
        when(runDao.listByTask(WORKSPACE_ID, 11L, 2, 4)).thenReturn(List.of(run));

        Map<?, ?> result = (Map<?, ?>) call(principal, "autowonder.list_scheduled_task_runs",
                Map.of("id", 11L, "size", 2, "offset", 4));

        assertEquals(2, ((Number) result.get("size")).intValue());
        assertEquals(4, ((Number) result.get("offset")).intValue());
        assertFalse(result.containsKey("total"));
        assertEquals(99L, ((Number) ((Map<?, ?>) ((List<?>) result.get("list")).get(0)).get("id")).longValue());

        for (Map<String, Object> invalid : List.<Map<String, Object>>of(Map.of("id", 11L, "size", 0),
                Map.of("id", 11L, "size", 101), Map.of("id", 11L, "offset", -1))) {
            BizException exception = assertThrows(BizException.class,
                    () -> call(principal, "autowonder.list_scheduled_task_runs", invalid));
            assertEquals("27003", exception.getCode());
        }
    }

    @Test
    void listScheduledTaskRunsRequiresExistingTaskAndOwnershipForDispatch() {
        ScheduledTaskService taskService = mock(ScheduledTaskService.class);
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        ReflectionTestUtils.setField(service, "scheduledTaskService", taskService);
        ReflectionTestUtils.setField(service, "scheduledTaskRunDao", runDao);
        when(taskService.get(12L, WORKSPACE_ID))
                .thenThrow(new BizException(ErrorCode.SCHEDULED_TASK_NOT_FOUND));
        BizException missingTask = assertThrows(BizException.class,
                () -> call(principal, "autowonder.list_scheduled_task_runs", Map.of("id", 12L)));
        assertEquals("30001", missingTask.getCode());

        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal(-321L);
        DispatchDO dispatch = dispatch(321L, WORKSPACE_ID, 99L, 40014L);
        dispatch.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        when(dispatchDao.findById(321L)).thenReturn(dispatch);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(99L);
        run.setWorkspaceId(WORKSPACE_ID);
        run.setScheduledTaskId(11L);
        when(runDao.findById(WORKSPACE_ID, 99L)).thenReturn(run);
        ScheduledTaskVO task = new ScheduledTaskVO();
        task.setId(11L);
        task.setCreatorId(40014L);
        task.setVersion(1);
        when(taskService.get(11L, WORKSPACE_ID)).thenReturn(task);
        when(runDao.listByTask(WORKSPACE_ID, 11L, 20, 0)).thenReturn(List.of());

        Map<?, ?> ownTask = (Map<?, ?>) call(dispatchPrincipal, "autowonder.list_scheduled_task_runs",
                Map.of("id", 11L));
        assertEquals(0, ((List<?>) ownTask.get("list")).size());

        BizException foreignTask = assertThrows(BizException.class,
                () -> call(dispatchPrincipal, "autowonder.list_scheduled_task_runs", Map.of("id", 12L)));
        assertEquals("10403", foreignTask.getCode());
    }

    @Test
    void deleteScheduledTaskRequiresOwnerOrAdminAndDelegates() {
        ScheduledTaskService taskService = mock(ScheduledTaskService.class);
        ReflectionTestUtils.setField(service, "scheduledTaskService", taskService);
        ScheduledTaskVO existing = new ScheduledTaskVO();
        existing.setId(11L);
        existing.setCreatorId(8L);
        existing.setVersion(3);
        when(taskService.get(11L, WORKSPACE_ID)).thenReturn(existing);

        BizException notOwner = assertThrows(BizException.class,
                () -> call(principal, "autowonder.delete_scheduled_task", Map.of("id", 11L, "version", 3L)));
        assertEquals("10403", notOwner.getCode());
        verify(taskService, never()).delete(anyLong(), any(), anyLong(), anyLong());

        BizException missingVersion = assertThrows(BizException.class,
                () -> call(principal(WorkspaceAccessLevel.ADMIN), "autowonder.delete_scheduled_task",
                        Map.of("id", 11L)));
        assertEquals("27003", missingVersion.getCode());

        assertEquals(Map.of("deleted", true), call(principal(WorkspaceAccessLevel.ADMIN),
                "autowonder.delete_scheduled_task", Map.of("id", 11L, "version", 3L)));
        verify(taskService).delete(11L, 3, WORKSPACE_ID, USER_ID);
    }

    @Test
    void scheduledTaskDocumentSourceTypeRoutesToOwnerOverload() {
        ArtifactOwnerRef owner = new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, 11L);
        ArtifactVO artifact = new ArtifactVO();
        artifact.setId(77L);
        when(requirementDocumentService.uploadMcp(eq(owner), eq("plan.md"), any(byte[].class),
                eq(WORKSPACE_ID), eq(USER_ID), isNull())).thenReturn(artifact);
        when(requirementDocumentService.list(eq(owner), eq(WORKSPACE_ID))).thenReturn(List.of(artifact));

        assertSame(artifact, call(principal, "autowonder.upload_workitem_document", Map.of(
                "id", 11L, "sourceType", "SCHEDULED_TASK", "filename", "plan.md", "contentMd", "# Plan")));
        assertEquals(List.of(artifact), call(principal, "autowonder.list_workitem_documents",
                Map.of("id", 11L, "sourceType", "scheduled_task")));
        assertEquals(Map.of("deleted", true), call(principal, "autowonder.delete_workitem_document",
                Map.of("id", 11L, "artifactId", 77L, "sourceType", " SCHEDULED_TASK ")));

        verify(requirementDocumentService).list(eq(owner), eq(WORKSPACE_ID));
        verify(requirementDocumentService).delete(eq(owner), eq(77L), eq(WORKSPACE_ID), eq(USER_ID));
        verify(requirementDocumentService, never()).list(anyLong(), anyLong());
        verify(requirementDocumentService, never()).delete(anyLong(), anyLong(), anyLong(), anyLong());

        // 显式 WORKITEM 与缺省一样走旧工单重载，行为零回归。
        when(requirementDocumentService.list(11L, WORKSPACE_ID)).thenReturn(List.of());
        assertEquals(List.of(), call(principal, "autowonder.list_workitem_documents",
                Map.of("id", 11L, "sourceType", "WORKITEM")));
        verify(requirementDocumentService).list(11L, WORKSPACE_ID);
    }

    @Test
    void scheduledTaskDocumentSourceTypeRejectsUnknownValueAndRequiresCapability() {
        BizException unknown = assertThrows(BizException.class,
                () -> call(principal, "autowonder.list_workitem_documents",
                        Map.of("id", 11L, "sourceType", "WORKFLOW")));
        assertEquals("27003", unknown.getCode());

        doThrow(new BizException(ErrorCode.SCHEDULED_TASK_SCHEMA_NOT_READY))
                .when(capabilityGuard).requireAvailable("mcp");
        for (String tool : List.of("autowonder.upload_workitem_document",
                "autowonder.list_workitem_documents", "autowonder.delete_workitem_document")) {
            BizException gated = assertThrows(BizException.class,
                    () -> call(principal, tool, Map.of("id", 11L, "sourceType", "SCHEDULED_TASK",
                            "filename", "plan.md", "artifactId", 77L)));
            assertEquals("30006", gated.getCode(), tool);
        }
    }

    @Test
    void dispatchCredentialCannotMutateScheduledTaskDocumentsAndListsOnlyItsOwnTask() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal(-321L);
        DispatchDO dispatch = dispatch(321L, WORKSPACE_ID, 99L, 40014L);
        dispatch.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        when(dispatchDao.findById(321L)).thenReturn(dispatch);
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        ReflectionTestUtils.setField(service, "scheduledTaskRunDao", runDao);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(99L);
        run.setWorkspaceId(WORKSPACE_ID);
        run.setScheduledTaskId(11L);
        when(runDao.findById(WORKSPACE_ID, 99L)).thenReturn(run);

        BizException uploadDenied = assertThrows(BizException.class,
                () -> call(dispatchPrincipal, "autowonder.upload_workitem_document", Map.of(
                        "id", 11L, "sourceType", "SCHEDULED_TASK", "filename", "plan.md",
                        "contentMd", "# Plan")));
        assertEquals("10403", uploadDenied.getCode());
        BizException deleteDenied = assertThrows(BizException.class,
                () -> call(dispatchPrincipal, "autowonder.delete_workitem_document", Map.of(
                        "id", 11L, "artifactId", 77L, "sourceType", "SCHEDULED_TASK")));
        assertEquals("10403", deleteDenied.getCode());
        verify(requirementDocumentService, never()).uploadMcp(any(ArtifactOwnerRef.class), anyString(),
                any(byte[].class), anyLong(), anyLong(), any());
        verify(requirementDocumentService, never()).delete(any(ArtifactOwnerRef.class), anyLong(),
                anyLong(), anyLong());

        when(requirementDocumentService.list(
                eq(new ArtifactOwnerRef(ExecutionSourceType.SCHEDULED_TASK, 11L)), eq(WORKSPACE_ID)))
                .thenReturn(List.of());
        assertEquals(List.of(), call(dispatchPrincipal, "autowonder.list_workitem_documents",
                Map.of("id", 11L, "sourceType", "SCHEDULED_TASK")));

        BizException foreignTask = assertThrows(BizException.class,
                () -> call(dispatchPrincipal, "autowonder.list_workitem_documents",
                        Map.of("id", 12L, "sourceType", "SCHEDULED_TASK")));
        assertEquals("10403", foreignTask.getCode());
    }

    private ScheduledTaskVO scheduledTask(String scheduleType) {
        ScheduledTaskVO task = new ScheduledTaskVO();
        task.setId(11L);
        task.setCreatorId(USER_ID);
        task.setVersion(3);
        task.setName("nightly");
        task.setInstructionMd("run it");
        task.setSquadId(42L);
        task.setInitialAgentId(9L);
        task.setScheduleType(scheduleType);
        task.setCronExpression("CRON".equals(scheduleType) ? "0 0 2 * * *" : null);
        task.setTimezone("Asia/Shanghai");
        task.setSessionMode("ISOLATED");
        task.setOverlapPolicy("SKIP");
        task.setMisfirePolicy("FIRE_LATEST");
        task.setStartDeadlineSeconds(21_600);
        task.setAffinityTimeoutSeconds(1_800);
        return task;
    }

    @Test
    void transitionScheduledTaskTaskLevelActionsAndRunNowDelegate() {
        ScheduledTaskService taskService = mock(ScheduledTaskService.class);
        ScheduledTaskTriggerService triggerService = mock(ScheduledTaskTriggerService.class);
        ReflectionTestUtils.setField(service, "scheduledTaskService", taskService);
        ReflectionTestUtils.setField(service, "scheduledTaskTriggerService", triggerService);
        ScheduledTaskVO existing = new ScheduledTaskVO();
        existing.setId(11L);
        existing.setCreatorId(USER_ID);
        existing.setStatus("PAUSED");
        existing.setVersion(3);
        when(taskService.get(11L, WORKSPACE_ID)).thenReturn(existing);

        ScheduledTaskVO enabled = new ScheduledTaskVO();
        enabled.setId(11L);
        enabled.setStatus("ACTIVE");
        enabled.setVersion(4);
        when(taskService.enable(11L, 3, WORKSPACE_ID, USER_ID)).thenReturn(enabled);
        Map<?, ?> result = (Map<?, ?>) call(principal, "autowonder.transition_scheduled_task",
                Map.of("id", 11L, "action", "enable", "version", 3L));
        assertEquals("ACTIVE", result.get("status"));

        BizException badAction = assertThrows(BizException.class,
                () -> call(principal, "autowonder.transition_scheduled_task",
                        Map.of("id", 11L, "action", "delete", "version", 3L)));
        assertEquals("27003", badAction.getCode());

        BizException missingRequestId = assertThrows(BizException.class,
                () -> call(principal, "autowonder.transition_scheduled_task",
                        Map.of("id", 11L, "action", "run-now", "version", 3L)));
        assertEquals("27003", missingRequestId.getCode());

        existing.setVersion(4);
        BizException staleVersion = assertThrows(BizException.class,
                () -> call(principal, "autowonder.transition_scheduled_task",
                        Map.of("id", 11L, "action", "run-now", "version", 3L, "requestId", "req-1")));
        assertEquals("30002", staleVersion.getCode());

        ScheduledTaskRunDO fired = new ScheduledTaskRunDO();
        fired.setId(99L);
        fired.setWorkspaceId(WORKSPACE_ID);
        fired.setScheduledTaskId(11L);
        fired.setStatus("QUEUED");
        fired.setVersion(0);
        when(triggerService.fireManual(WORKSPACE_ID, 11L, "req-1")).thenReturn(fired);
        Map<?, ?> runResult = (Map<?, ?>) call(principal, "autowonder.transition_scheduled_task",
                Map.of("id", 11L, "action", "run-now", "version", 4L, "requestId", "req-1"));
        assertEquals(99L, ((Number) runResult.get("id")).longValue());
        assertEquals("QUEUED", runResult.get("status"));
    }

    @Test
    void transitionScheduledTaskRunActionsReplicateRestControllerSemantics() {
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        ScheduledTaskRunService runService = mock(ScheduledTaskRunService.class);
        ScheduledTaskRunDispatchControlService control = mock(ScheduledTaskRunDispatchControlService.class);
        ScheduledTaskRunOrchestrator orchestrator = mock(ScheduledTaskRunOrchestrator.class);
        ReflectionTestUtils.setField(service, "scheduledTaskRunDao", runDao);
        ReflectionTestUtils.setField(service, "scheduledTaskRunService", runService);
        ReflectionTestUtils.setField(service, "scheduledTaskRunDispatchControlService", control);
        ReflectionTestUtils.setField(service, "scheduledTaskRunOrchestrator", orchestrator);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(99L);
        run.setWorkspaceId(WORKSPACE_ID);
        run.setScheduledTaskId(11L);
        run.setOwnerId(USER_ID);
        run.setStatus("RUNNING");
        run.setVersion(5);
        when(runDao.findById(WORKSPACE_ID, 99L)).thenReturn(run);

        ScheduledTaskRunDO paused = new ScheduledTaskRunDO();
        paused.setId(99L);
        paused.setWorkspaceId(WORKSPACE_ID);
        paused.setStatus("PAUSED");
        paused.setVersion(6);
        when(runService.transition(WORKSPACE_ID, 99L, 5, "PAUSED", USER_ID)).thenReturn(paused);
        Map<?, ?> pauseResult = (Map<?, ?>) call(principal, "autowonder.transition_scheduled_task",
                Map.of("id", 11L, "action", "pause-run", "version", 5L, "runId", 99L));
        assertEquals("PAUSED", pauseResult.get("status"));
        verify(control).pauseActive(WORKSPACE_ID, 99L, USER_ID, false);

        when(runService.transition(WORKSPACE_ID, 99L, 6, "QUEUED", USER_ID)).thenReturn(run);
        when(orchestrator.resumePaused(WORKSPACE_ID, 99L, USER_ID)).thenReturn(true);
        ScheduledTaskRunDO resumed = new ScheduledTaskRunDO();
        resumed.setId(99L);
        resumed.setWorkspaceId(WORKSPACE_ID);
        resumed.setStatus("RUNNING");
        resumed.setVersion(7);
        when(runDao.findById(WORKSPACE_ID, 99L)).thenReturn(run, resumed);
        Map<?, ?> resumeResult = (Map<?, ?>) call(principal, "autowonder.transition_scheduled_task",
                Map.of("id", 11L, "action", "resume-run", "version", 6L, "runId", 99L));
        assertEquals("RUNNING", resumeResult.get("status"));

        ScheduledTaskRunDO ownedBySomeoneElse = new ScheduledTaskRunDO();
        ownedBySomeoneElse.setId(100L);
        ownedBySomeoneElse.setWorkspaceId(WORKSPACE_ID);
        ownedBySomeoneElse.setOwnerId(8L);
        ownedBySomeoneElse.setVersion(1);
        when(runDao.findById(WORKSPACE_ID, 100L)).thenReturn(ownedBySomeoneElse);
        BizException notOwner = assertThrows(BizException.class,
                () -> call(principal, "autowonder.transition_scheduled_task",
                        Map.of("id", 11L, "action", "cancel-run", "version", 1L, "runId", 100L)));
        assertEquals("10403", notOwner.getCode());

        when(runDao.findById(WORKSPACE_ID, 99L)).thenReturn(run);
        when(runService.markCancelIntent(run, USER_ID)).thenReturn(true);
        ScheduledTaskRunDO canceled = new ScheduledTaskRunDO();
        canceled.setId(99L);
        canceled.setWorkspaceId(WORKSPACE_ID);
        canceled.setStatus("CANCELED");
        canceled.setVersion(8);
        when(runDao.findById(WORKSPACE_ID, 99L)).thenReturn(run, canceled);
        Map<?, ?> cancelResult = (Map<?, ?>) call(principal, "autowonder.transition_scheduled_task",
                Map.of("id", 11L, "action", "cancel-run", "version", 5L, "runId", 99L));
        assertEquals("CANCELED", cancelResult.get("status"));
        verify(control).pauseActive(WORKSPACE_ID, 99L, USER_ID, true);
    }

    @Test
    void dispatchTokenScheduledTaskReadsAreLimitedToItsOwnRun() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal(-321L);
        DispatchDO dispatch = dispatch(321L, WORKSPACE_ID, 99L, 40014L);
        dispatch.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        when(dispatchDao.findById(321L)).thenReturn(dispatch);
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        ScheduledTaskService taskService = mock(ScheduledTaskService.class);
        ReflectionTestUtils.setField(service, "scheduledTaskRunDao", runDao);
        ReflectionTestUtils.setField(service, "scheduledTaskService", taskService);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(99L);
        run.setWorkspaceId(WORKSPACE_ID);
        run.setScheduledTaskId(11L);
        run.setOwnerId(40014L);
        run.setStatus("RUNNING");
        run.setVersion(2);
        when(runDao.findById(WORKSPACE_ID, 99L)).thenReturn(run);

        ScheduledTaskVO task = new ScheduledTaskVO();
        task.setId(11L);
        task.setCreatorId(40014L);
        task.setVersion(1);
        when(taskService.get(11L, WORKSPACE_ID)).thenReturn(task);
        Map<?, ?> taskResult = (Map<?, ?>) call(dispatchPrincipal,
                "autowonder.get_scheduled_task", Map.of("id", 11L, "includeRuns", false));
        assertEquals(11L, ((Number) taskResult.get("id")).longValue());

        BizException otherTask = assertThrows(BizException.class,
                () -> call(dispatchPrincipal, "autowonder.get_scheduled_task", Map.of("id", 12L)));
        assertEquals("10403", otherTask.getCode());

        Map<?, ?> runResult = (Map<?, ?>) call(dispatchPrincipal, "autowonder.get_scheduled_task_run",
                Map.of("runId", 99L, "includeEvents", false, "includeArtifacts", false,
                        "includeComments", false));
        assertEquals(99L, ((Number) runResult.get("id")).longValue());

        BizException otherRun = assertThrows(BizException.class,
                () -> call(dispatchPrincipal, "autowonder.get_scheduled_task_run", Map.of("runId", 100L)));
        assertEquals("10403", otherRun.getCode());
    }

    @Test
    void scheduledTaskRunCommentRoutesDispatchAndHumanPathsDifferently() {
        ScheduledTaskRunCommentService runComments = mock(ScheduledTaskRunCommentService.class);
        ReflectionTestUtils.setField(service, "scheduledTaskRunCommentService", runComments);
        CommentVO comment = new CommentVO();
        comment.setId(56L);
        when(runComments.addHumanComment(WORKSPACE_ID, 99L, USER_ID, "check logs")).thenReturn(comment);
        assertSame(comment, call(principal, "autowonder.add_scheduled_task_run_comment",
                Map.of("runId", 99L, "contentMd", "check logs")));

        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal(-321L);
        DispatchDO dispatch = dispatch(321L, WORKSPACE_ID, 99L, 40014L);
        dispatch.setSourceType(ExecutionSourceType.SCHEDULED_TASK_RUN.name());
        when(dispatchDao.findById(321L)).thenReturn(dispatch);
        CommentVO agentComment = new CommentVO();
        agentComment.setId(57L);
        when(runComments.addAgentComment(WORKSPACE_ID, 99L, 40014L, "need guidance", List.of(), List.of()))
                .thenReturn(agentComment);
        assertSame(agentComment, call(dispatchPrincipal, "autowonder.add_scheduled_task_run_comment",
                Map.of("runId", 99L, "contentMd", "need guidance")));

        BizException otherRun = assertThrows(BizException.class,
                () -> call(dispatchPrincipal, "autowonder.add_scheduled_task_run_comment",
                        Map.of("runId", 100L, "contentMd", "not mine")));
        assertEquals("10403", otherRun.getCode());
    }

    @Test
    void getScheduledTaskAggregatesRunsHealthAndDocuments() {
        ScheduledTaskService taskService = mock(ScheduledTaskService.class);
        ScheduledTaskRunDao runDao = mock(ScheduledTaskRunDao.class);
        ArtifactService artifactService = mock(ArtifactService.class);
        ReflectionTestUtils.setField(service, "scheduledTaskService", taskService);
        ReflectionTestUtils.setField(service, "scheduledTaskRunDao", runDao);
        ReflectionTestUtils.setField(service, "artifactService", artifactService);
        ScheduledTaskVO task = new ScheduledTaskVO();
        task.setId(11L);
        task.setCreatorId(USER_ID);
        task.setVersion(1);
        when(taskService.get(11L, WORKSPACE_ID)).thenReturn(task);
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(99L);
        run.setWorkspaceId(WORKSPACE_ID);
        run.setScheduledTaskId(11L);
        run.setStatus("SUCCEEDED");
        run.setVersion(3);
        when(runDao.listByTask(WORKSPACE_ID, 11L, 10, 0)).thenReturn(List.of(run));
        when(runDao.countCompletedByTaskSince(eq(WORKSPACE_ID), eq(11L), any(Date.class))).thenReturn(4L);
        when(runDao.countSucceededByTaskSince(eq(WORKSPACE_ID), eq(11L), any(Date.class))).thenReturn(3L);
        when(requirementDocumentService.list(any(com.aliyun.autowonder.artifact.ArtifactOwnerRef.class),
                eq(WORKSPACE_ID))).thenReturn(List.of());

        Map<?, ?> result = (Map<?, ?>) call(principal, "autowonder.get_scheduled_task",
                Map.of("id", 11L, "includeDocuments", true));

        assertEquals(1, ((List<?>) result.get("recentRuns")).size());
        assertEquals(4L, ((Number) ((Map<?, ?>) result.get("health")).get("completed30d")).longValue());
        assertEquals(3L, ((Number) ((Map<?, ?>) result.get("health")).get("success30d")).longValue());
        assertNotNull(result.get("documents"));
        assertFalse(result.containsKey("nextFirePreviews"));
        verify(artifactService, never()).listByOwner(any(), anyLong());
    }

    @Test
    void pauseDispatchDelegatesToPauseServiceAndReturnsStatusMap() {
        DispatchDO paused = new DispatchDO();
        paused.setId(555L);
        paused.setStatus("PAUSING");
        when(dispatchPauseService.requestPause(WORKSPACE_ID, 99L, 555L, USER_ID)).thenReturn(paused);

        Object result = call(principal, "autowonder.pause_dispatch",
                Map.of("workitemId", 99L, "dispatchId", 555L));

        assertEquals(Map.of("dispatchId", 555L, "status", "PAUSING"), result);
        verify(dispatchPauseService).requestPause(WORKSPACE_ID, 99L, 555L, USER_ID);
    }

    @Test
    void listCommentsRequiresReadPermission() {

        call(principal, "autowonder.list_workitem_comments", Map.of("id", 99L));

        verify(workitemService).listComments(99L);
    }

    @Test
    void workitemDocumentSchemasExposeExpectedFields() {
        Map<String, Object> uploadSchema = schemaFor("autowonder.upload_workitem_document");
        assertEquals(List.of("workspaceId", "id", "filename"), uploadSchema.get("required"));
        assertTrue(properties(uploadSchema).keySet().containsAll(List.of(
                "id", "filename", "contentMd", "contentBase64", "sourcePath")));

        // upload_workitem_document ordering: upload before assign
        McpToolVO upload = toolFor("autowonder.upload_workitem_document");
        assertTrue(upload.getDescription().contains("assign_workitem"));
        assertTrue(upload.getDescription().contains("first dispatch"));
        assertTrue(upload.getDescription().contains("PNG"));
        assertTrue(upload.getDescription().contains("JPEG"));
        assertTrue(upload.getDescription().contains("WebP"));
        assertTrue(upload.getDescription().contains(".txt"));
        assertTrue(upload.getDescription().contains(".html"));
        assertTrue(upload.getDescription().contains(".pdf"));
        assertTrue(upload.getDescription().contains("contentBase64"));
        assertTrue(upload.getDescription().contains("contentMd"));

        assertTrue(toolFor("autowonder.list_workitem_documents").getDescription()
                .contains("requirement/design context attachment"));
        assertTrue(toolFor("autowonder.delete_workitem_document").getDescription()
                .contains("requirement/design context attachment"));

        Map<String, Object> output = outputSchemaFor("autowonder.upload_workitem_document");
        assertTrue(properties(output).keySet().containsAll(List.of("id", "workitemId", "name", "type", "size")));
        assertEquals(List.of("integer", "null"), property(output, "dispatchId").get("type"));
        assertEquals(List.of("string", "null"), property(output, "gmtCreate").get("type"));
        assertListOutputSchema(outputSchemaFor("autowonder.list_workitem_documents"), "id", "name", "type");
    }

    @Test
    void uploadWorkitemDocumentDelegatesBase64ContentToRequirementDocumentService() {
        ArtifactVO artifact = new ArtifactVO();
        artifact.setId(77L);
        when(requirementDocumentService.uploadMcp(eq(99L), eq("spec.md"), any(byte[].class),
                eq(100L), eq(7L), eq("/tmp/spec.md"))).thenReturn(artifact);

        Object result = call(principal, "autowonder.upload_workitem_document",
                Map.of("id", 99L, "filename", "spec.md", "contentMd", "ignored",
                        "contentBase64", java.util.Base64.getEncoder().encodeToString("# Spec".getBytes()),
                        "sourcePath", "/tmp/spec.md"));

        assertSame(artifact, result);
        verify(requirementDocumentService).uploadMcp(eq(99L), eq("spec.md"),
                argThat(bytes -> "# Spec".equals(new String(bytes))), eq(100L), eq(7L), eq("/tmp/spec.md"));
    }

    @Test
    void uploadWorkitemDocumentDelegatesBase64VisualContentToRequirementDocumentService() {
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        ArtifactVO artifact = new ArtifactVO();
        when(requirementDocumentService.uploadMcp(eq(99L), eq("screen.png"), any(byte[].class),
                eq(100L), eq(7L), isNull())).thenReturn(artifact);

        call(principal, "autowonder.upload_workitem_document", Map.of(
                "id", 99L, "filename", "screen.png",
                "contentBase64", java.util.Base64.getEncoder().encodeToString(png)));

        verify(requirementDocumentService).uploadMcp(eq(99L), eq("screen.png"),
                argThat(bytes -> java.util.Arrays.equals(png, bytes)), eq(100L), eq(7L), isNull());
    }

    @Test
    void clarificationConversationCanUploadConfirmedSpecOrPlanDocuments() {
        ArtifactVO artifact = new ArtifactVO();
        artifact.setId(77L);
        when(requirementDocumentService.uploadMcp(eq(99L), eq("draft.md"), any(byte[].class),
                eq(WORKSPACE_ID), eq(USER_ID), isNull())).thenReturn(artifact);
        McpAccessTokenService.Principal clarificationPrincipal = new McpAccessTokenService.Principal(
                WORKSPACE_ID, USER_ID, 88L, WorkspaceAccessLevel.READ_WRITE,
                McpAccessTokenService.CredentialType.CONVERSATION);

        assertSame(artifact, call(clarificationPrincipal,
                "autowonder.upload_workitem_document", Map.of("id", 99L, "filename", "draft.md",
                        "contentMd", "# Confirmed spec")));
        verify(requirementDocumentService).uploadMcp(eq(99L), eq("draft.md"),
                argThat(bytes -> "# Confirmed spec".equals(new String(bytes))), eq(WORKSPACE_ID), eq(USER_ID), isNull());
        assertFalse(service.listTools().stream()
                .anyMatch(tool -> "autowonder.save_workitem_clarification".equals(tool.getName())));
    }

    @Test
    void listAndDeleteWorkitemDocumentsDelegateToRequirementDocumentService() {
        ArtifactVO artifact = new ArtifactVO();
        artifact.setId(77L);
        when(requirementDocumentService.list(99L, 100L)).thenReturn(List.of(artifact));

        assertEquals(List.of(artifact), call(principal,
                "autowonder.list_workitem_documents", Map.of("id", 99L)));
        assertEquals(Map.of("deleted", true), call(principal,
                "autowonder.delete_workitem_document", Map.of("id", 99L, "artifactId", 77L)));

        verify(requirementDocumentService).list(99L, 100L);
        verify(requirementDocumentService).delete(99L, 77L, 100L, 7L);
    }

    @Test
    void skillPackageSchemasExposeUploadCreateAndUpdateFlow() {
        Map<String, Object> uploadSchema = schemaFor("autowonder.upload_skill_package");
        assertEquals(List.of("workspaceId"), uploadSchema.get("required"));
        assertEquals(2, ((List<?>) uploadSchema.get("oneOf")).size());
        assertTrue(properties(uploadSchema).keySet().containsAll(List.of(
                "fileName", "contentBase64", "files", "type", "expectedMd5")));

        Map<String, Object> createSchema = schemaFor("autowonder.create_skill_from_package");
        assertEquals(List.of("workspaceId", "packageOssRef"), createSchema.get("required"));
        assertTrue(properties(createSchema).keySet().containsAll(List.of(
                "packageOssRef", "idempotencyKey", "expectedMd5")));

        Map<String, Object> updateSchema = schemaFor("autowonder.update_skill_package");
        assertEquals(List.of("workspaceId", "id", "packageOssRef"), updateSchema.get("required"));
        assertTrue(properties(updateSchema).keySet().containsAll(List.of(
                "id", "packageOssRef", "expectedMd5", "idempotencyKey")));

        assertTrue(properties(outputSchemaFor("autowonder.upload_skill_package")).keySet().containsAll(List.of(
                "packageOssRef", "packageMd5", "packageSha256", "fileName")));
    }

    @Test
    void inspectSkillPackageDelegatesDecodedBytesToService() {
        SkillPackageInspectVO inspect = new SkillPackageInspectVO();
        inspect.setName("demo");
        when(skillPackageService.inspect(eq("demo.zip"), any(byte[].class))).thenReturn(inspect);

        Object result = call(principal, "autowonder.inspect_skill_package",
                Map.of("fileName", "demo.zip", "contentBase64",
                        java.util.Base64.getEncoder().encodeToString("zip".getBytes())));

        assertSame(inspect, result);
        verify(skillPackageService).inspect(eq("demo.zip"), argThat(bytes -> "zip".equals(new String(bytes))));
    }

    @Test
    void directoryInputWorksForInspectAndUploadAndRejectsAmbiguousInput() {
        Map<String, String> files = Map.of("SKILL.md", "ZGVtbw==");
        byte[] archive = new byte[]{1, 2, 3};
        when(skillPackageService.packDirectory(files)).thenReturn(archive);
        SkillPackageInspectVO inspect = new SkillPackageInspectVO();
        when(skillPackageService.inspect("directory.zip", archive)).thenReturn(inspect);
        assertSame(inspect, call(principal, "autowonder.inspect_skill_package", Map.of("files", files)));
        when(skillPackageService.uploadMcpPackage(eq("directory.zip"), eq(archive), isNull(), isNull(),
                isNull(), isNull(), isNull(), eq(100L)))
                .thenReturn(new SkillPackageService.UploadedPackage("bucket/demo.zip", "demo.zip",
                        3L, "md5", "sha", "SKILL", "demo", "Demo"));
        Map<?, ?> result = (Map<?, ?>) call(principal, "autowonder.upload_skill_package", Map.of("files", files));
        assertEquals("bucket/demo.zip", result.get("packageOssRef"));
        for (Map<String, Object> args : List.<Map<String, Object>>of(
                Map.of("files", files, "contentBase64", "ZGVtbw=="),
                Map.of("files", files, "fileName", "demo.tar.gz"),
                Map.of("files", Map.of("SKILL.md", 123)),
                Map.of("files", "local/path"))) {
            BizException ex = assertThrows(BizException.class,
                    () -> call(principal, "autowonder.inspect_skill_package", args));
            assertEquals(ErrorCode.MCP_TOOL_ARGUMENT_INVALID.getCode(), ex.getCode());
        }
    }

    @Test
    void uploadSkillPackageReturnsPackageReferenceMetadata() {
        when(skillPackageService.uploadMcpPackage(eq("demo.zip"), any(byte[].class), eq("SKILL"), isNull(),
                isNull(), isNull(), eq("md5"), eq(100L)))
                .thenReturn(new SkillPackageService.UploadedPackage("bucket/key/demo.zip", "demo.zip",
                        3L, "md5", "sha", "SKILL", "demo", "Demo skill"));

        Object result = call(principal, "autowonder.upload_skill_package",
                Map.of("fileName", "demo.zip", "contentBase64",
                        java.util.Base64.getEncoder().encodeToString("zip".getBytes()),
                        "type", "SKILL", "expectedMd5", "md5"));

        assertTrue(result instanceof Map<?, ?>);
        Map<?, ?> map = (Map<?, ?>) result;
        assertEquals("bucket/key/demo.zip", map.get("packageOssRef"));
        assertEquals("md5", map.get("packageMd5"));
        assertEquals("sha", map.get("packageSha256"));
    }

    @Test
    void createAndUpdateSkillPackageDelegatePackageReferences() {
        SkillVO created = new SkillVO();
        created.setId(5L);
        when(skillPackageService.createFromUploadedPackage("bucket/key/demo.zip", "SKILL", null, null,
                null, "md5", "idem", 100L, 7L)).thenReturn(created);
        SkillVO updated = new SkillVO();
        updated.setId(5L);
        when(skillPackageService.updateUploadedPackage(5L, "bucket/key/demo.zip", null, null,
                null, "md5", "idem", 100L, 7L)).thenReturn(updated);

        assertSame(created, call(principal, "autowonder.create_skill_from_package",
                Map.of("packageOssRef", "bucket/key/demo.zip", "type", "SKILL",
                        "expectedMd5", "md5", "idempotencyKey", "idem")));
        assertSame(updated, call(principal, "autowonder.update_skill_package",
                Map.of("id", 5L, "packageOssRef", "bucket/key/demo.zip",
                        "expectedMd5", "md5", "idempotencyKey", "idem")));
    }


    @Test
    void dispatchCredentialCreateMemoryDerivesProvenanceAndForcesAgentOwnership() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal();
        when(dispatchDao.findById(321L)).thenReturn(dispatch(321L, 100L, 99L, 40014L));
        MemoryVO created = memory(500L, "AGENT", 40014L);
        when(memoryService.createFromMcp(any(CreateMemoryRequest.class), eq(100L), eq(321L),
                eq(99L), eq(40014L), eq(7L), anyString())).thenReturn(created);

        Object result = service.call(dispatchPrincipal, "autowonder.create_memory",
                Map.of("title", "MyBatis keyword 检索", "contentMd", "用参数化 LIKE",
                        "type", "PITFALL", "ownerRef", 99999L));

        assertSame(created, result);
        verify(memoryService).createFromMcp(argThat(req -> "AGENT".equals(req.getScope())
                        && Long.valueOf(40014L).equals(req.getOwnerRef())
                        && "MyBatis keyword 检索".equals(req.getTitle())),
                eq(100L), eq(321L), eq(99L), eq(40014L), eq(7L),
                argThat(key -> key.startsWith("dispatch:321:mcp:")));
        verify(memoryService, never()).create(any(), anyLong(), anyLong());
    }

    @Test
    void dispatchCredentialCreateMemoryUsesCallerIdempotencyKey() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal();
        when(dispatchDao.findById(321L)).thenReturn(dispatch(321L, 100L, 99L, 40014L));

        service.call(dispatchPrincipal, "autowonder.create_memory",
                Map.of("title", "标题", "contentMd", "正文", "idempotencyKey", "step-400165"));

        verify(memoryService).createFromMcp(any(CreateMemoryRequest.class), eq(100L), eq(321L),
                eq(99L), eq(40014L), eq(7L), eq("dispatch:321:mcp:step-400165"));
    }

    @Test
    void createMemoryRejectsUnknownScope() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal();
        when(dispatchDao.findById(321L)).thenReturn(dispatch(321L, 100L, 99L, 40014L));

        BizException ex = assertThrows(BizException.class, () -> service.call(dispatchPrincipal,
                "autowonder.create_memory", Map.of("title", "标题", "scope", "GLOBAL")));

        assertEquals("27003", ex.getCode());
        verify(memoryService, never()).createFromMcp(any(), anyLong(), anyLong(), anyLong(),
                anyLong(), anyLong(), anyString());
    }

    @Test
    void dispatchCredentialCannotCreateSquadOrWorkspaceScopedMemory() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal();
        when(dispatchDao.findById(321L)).thenReturn(dispatch(321L, 100L, 99L, 40014L));

        for (String scope : List.of("SQUAD", "ORG", "squad", "org")) {
            BizException ex = assertThrows(BizException.class, () -> service.call(dispatchPrincipal,
                    "autowonder.create_memory", Map.of("title", "标题", "scope", scope)));
            assertEquals("27003", ex.getCode(), scope);
        }

        verify(memoryService, never()).createFromMcp(any(), anyLong(), anyLong(), anyLong(),
                anyLong(), anyLong(), anyString());
    }

    @Test
    void dispatchCanSearchAndReadOtherAgentMemoriesWithinWorkspace() {
        when(dispatchDao.findById(321L)).thenReturn(dispatch(321L, 100L, 99L, 40014L));
        MemoryVO other = memory(502L, "AGENT", 40015L);
        when(memoryService.list(100L, "AGENT", 40015L, null, "PENDING", "MyBatis", null, 2, 20))
                .thenReturn(List.of(other));
        when(memoryService.getScoped(502L, 100L)).thenReturn(other);
        assertEquals(List.of(other), service.call(dispatchPrincipal(), "autowonder.search_memories",
                Map.of("scope", "AGENT", "ownerRef", 40015L, "status", "PENDING", "keyword", "MyBatis", "page", 2)));
        assertSame(other, service.call(dispatchPrincipal(), "autowonder.get_memory", Map.of("id", 502L)));
        service.call(dispatchPrincipal(), "autowonder.search_memories", Map.of());
        verify(memoryService).list(100L, null, null, null, "ADOPTED", null, null, 1, 20);
    }

    @Test
    void searchMemoriesWithLongLivedTokenPassesNoVisibilityConstraint() {
        call(principal, "autowonder.search_memories", Map.of("keyword", "MyBatis"));
        verify(memoryService).list(100L, null, null, null, "ADOPTED", "MyBatis", null, 1, 20);
        verifyNoInteractions(dispatchDao);
    }

    @Test
    void dispatchCanMaintainOtherAgentAndSharedMemoriesWithinWorkspace() {
        when(dispatchDao.findById(321L)).thenReturn(dispatch(321L, 100L, 99L, 40014L));
        for (String scope : List.of("AGENT", "SQUAD", "ORG")) {
            when(memoryService.getScoped(502L, 100L)).thenReturn(memory(502L, scope, 40015L));
            service.call(dispatchPrincipal(), "autowonder.update_memory", Map.of("id", 502L, "title", "修订"));
            service.call(dispatchPrincipal(), "autowonder.deprecate_memory", Map.of("id", 502L));
            assertEquals(Map.of("deleted", true), service.call(dispatchPrincipal(),
                    "autowonder.delete_memory", Map.of("id", 502L)));
        }
        verify(memoryService, times(3)).update(eq(502L), any(), eq(100L), eq(7L));
        verify(memoryService, times(3)).deprecateFromMcp(502L, null, 100L, 7L);
        verify(memoryService, times(3)).delete(502L, 100L, 7L);
    }

    @Test
    void reviewMemorySupportsAdoptRejectAndScopePromotion() {
        when(dispatchDao.findById(321L)).thenReturn(dispatch(321L, 100L, 99L, 40014L));
        MemoryVO other = memory(502L, "AGENT", 40015L);
        when(memoryService.getScoped(502L, 100L)).thenReturn(other);
        for (String decision : List.of("ADOPT", "REJECT")) {
            assertSame(other, service.call(dispatchPrincipal(), "autowonder.review_memory",
                    Map.of("id", 502L, "decision", decision, "comment", "已核对 master",
                            "editedContentMd", "修正内容", "scope", "SQUAD", "ownerRef", 20L)));
            verify(memoryService).review(eq(502L), argThat(req -> decision.equals(req.getDecision())
                    && "已核对 master".equals(req.getComment()) && "修正内容".equals(req.getEditedContentMd())
                    && "SQUAD".equals(req.getScope()) && Long.valueOf(20L).equals(req.getOwnerRef())), eq(100L), eq(7L));
        }
    }

    @Test
    void deleteMemoryPreservesBoundMemoryProtection() {
        when(dispatchDao.findById(321L)).thenReturn(dispatch(321L, 100L, 99L, 40014L));
        when(memoryService.getScoped(502L, 100L)).thenReturn(memory(502L, "AGENT", 40015L));
        doThrow(new BizException(ErrorCode.MEMORY_DELETE_IN_USE)).when(memoryService).delete(502L, 100L, 7L);
        assertEquals(ErrorCode.MEMORY_DELETE_IN_USE.getCode(), assertThrows(BizException.class,
                () -> service.call(dispatchPrincipal(), "autowonder.delete_memory", Map.of("id", 502L))).getCode());
        verify(memoryService, never()).deprecateFromMcp(anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    void countPendingMemoriesCountsRowsAcrossWorkspace() {
        when(dispatchDao.findById(321L)).thenReturn(dispatch(321L, 100L, 99L, 40014L));
        when(memoryService.countPendingReviews(100L)).thenReturn(80L);
        assertEquals(Map.of("count", 80L), service.call(dispatchPrincipal(), "autowonder.count_pending_memories", Map.of()));
    }

    @Test
    void memoryManagementKeepsWorkspaceBoundaryAndWritePermission() {
        for (String tool : List.of("autowonder.review_memory", "autowonder.delete_memory",
                "autowonder.update_memory", "autowonder.deprecate_memory")) {
            assertEquals("10403", assertThrows(BizException.class, () -> call(
                    principal(WorkspaceAccessLevel.READ_ONLY), tool, Map.of("id", 502L, "decision", "ADOPT"))).getCode());
        }
        for (String tool : List.of("autowonder.review_memory", "autowonder.delete_memory",
                "autowonder.search_memories", "autowonder.count_pending_memories")) {
            assertEquals("10403", assertThrows(BizException.class, () -> service.call(dispatchPrincipal(), tool,
                    Map.of("workspaceId", 200L, "id", 502L, "decision", "ADOPT"))).getCode());
        }
        verifyNoInteractions(memoryService);
    }

    @Test
    void memoryManagementRejectsForeignWorkspaceIdsBeforeMutation() {
        when(dispatchDao.findById(321L)).thenReturn(dispatch(321L, 100L, 99L, 40014L));
        when(memoryService.getScoped(502L, 100L)).thenThrow(new BizException(ErrorCode.MEMORY_NOT_FOUND));
        for (String tool : List.of("autowonder.get_memory", "autowonder.review_memory", "autowonder.delete_memory",
                "autowonder.update_memory", "autowonder.deprecate_memory")) {
            assertEquals(ErrorCode.MEMORY_NOT_FOUND.getCode(), assertThrows(BizException.class, () ->
                    service.call(dispatchPrincipal(), tool, Map.of("id", 502L, "decision", "ADOPT"))).getCode());
        }
        verify(memoryService, never()).review(anyLong(), any(), anyLong(), anyLong());
        verify(memoryService, never()).delete(anyLong(), anyLong(), anyLong());
        verify(memoryService, never()).update(anyLong(), any(), anyLong(), anyLong());
        verify(memoryService, never()).deprecateFromMcp(anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    void memoryMutationsDelegateForOwnedAgentMemory() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal();
        when(dispatchDao.findById(321L)).thenReturn(dispatch(321L, 100L, 99L, 40014L));
        when(memoryService.getScoped(500L, 100L)).thenReturn(memory(500L, "AGENT", 40014L));
        MemoryVO updated = memory(500L, "AGENT", 40014L);
        MemoryVO deprecated = memory(500L, "AGENT", 40014L);
        when(memoryService.update(eq(500L), any(UpdateMemoryRequest.class), eq(100L), eq(7L)))
                .thenReturn(updated);
        when(memoryService.deprecateFromMcp(500L, "已过时", 100L, 7L)).thenReturn(deprecated);

        assertSame(updated, service.call(dispatchPrincipal, "autowonder.update_memory",
                Map.of("id", 500L, "title", "新标题")));
        assertSame(deprecated, service.call(dispatchPrincipal, "autowonder.deprecate_memory",
                Map.of("id", 500L, "comment", "已过时")));
        assertEquals(Map.of("deleted", true), service.call(dispatchPrincipal,
                "autowonder.delete_memory", Map.of("id", 500L)));

        verify(memoryService).update(eq(500L), argThat(req -> "新标题".equals(req.getTitle())), eq(100L), eq(7L));
        verify(memoryService).deprecateFromMcp(500L, "已过时", 100L, 7L);
        verify(memoryService).delete(500L, 100L, 7L);
    }

    @Test
    void longLivedTokenCreateMemoryRequiresExplicitScopeAndUsesManualPath() {
        BizException ex = assertThrows(BizException.class, () -> call(principal,
                "autowonder.create_memory", Map.of("title", "标题")));
        assertEquals("27003", ex.getCode());

        MemoryVO created = memory(600L, "ORG", null);
        when(memoryService.create(any(CreateMemoryRequest.class), eq(100L), eq(7L))).thenReturn(created);

        assertSame(created, call(principal, "autowonder.create_memory",
                Map.of("title", "标题", "scope", "org")));
        verify(memoryService).create(argThat(req -> "ORG".equals(req.getScope())), eq(100L), eq(7L));
        verify(memoryService, never()).createFromMcp(any(), anyLong(), anyLong(), anyLong(),
                anyLong(), anyLong(), anyString());
        verifyNoInteractions(dispatchDao);
    }

    @Test
    void memoryToolSchemasExposeExpectedFields() {
        assertEquals(List.of("workspaceId", "id", "decision"), schemaFor("autowonder.review_memory").get("required"));
        assertTrue(properties(outputSchemaFor("autowonder.review_memory")).containsKey("status"));
        assertTrue(properties(outputSchemaFor("autowonder.count_pending_memories")).containsKey("count"));
        Map<String, Object> createSchema = schemaFor("autowonder.create_memory");
        assertEquals(List.of("workspaceId", "title"), createSchema.get("required"));
        assertTrue(properties(createSchema).keySet().containsAll(List.of(
                "workspaceId", "title", "contentMd", "type", "scope", "ownerRef", "idempotencyKey")));

        Map<String, Object> searchSchema = schemaFor("autowonder.search_memories");
        assertEquals(List.of("workspaceId"), searchSchema.get("required"));
        assertTrue(properties(searchSchema).keySet().containsAll(List.of(
                "workspaceId", "keyword", "scope", "ownerRef", "type", "status", "page", "size")));

        assertEquals(List.of("workspaceId", "id"), schemaFor("autowonder.update_memory").get("required"));
        assertTrue(properties(schemaFor("autowonder.deprecate_memory")).containsKey("comment"));

        assertTrue(properties(outputSchemaFor("autowonder.create_memory")).keySet().containsAll(List.of(
                "id", "scope", "ownerRef", "title", "status", "source", "sourceRef",
                "gmtCreate", "gmtModified")));
        assertListOutputSchema(outputSchemaFor("autowonder.search_memories"), "id", "title", "status");
        assertEquals(Map.of("deleted", true).keySet(),
                properties(outputSchemaFor("autowonder.delete_memory")).keySet());
    }

    @Test
    void memoryToolDescriptionsTellAgentsHowProvenanceAndReviewWork() {
        McpToolVO create = toolFor("autowonder.create_memory");
        assertTrue(create.getDescription().contains("learning delta"));
        assertTrue(create.getDescription().contains("PENDING"));
        assertTrue(create.getDescription().contains("idempotent"));
        assertTrue(create.getDescription().contains("contentMd"));
        assertTrue(create.getDescription().contains("Do not pass content"));
        assertTrue(create.getDescription().contains("GLOBAL"));
        assertTrue(create.getDescription().contains("Personal or long-lived MCP tokens must pass scope"));
        assertTrue(create.getDescription().contains("Dispatch-scoped SDLC workers should omit scope and ownerRef"));

        assertTrue(toolFor("autowonder.search_memories").getDescription().contains("ADOPTED"));
        assertTrue(toolFor("autowonder.deprecate_memory").getDescription().contains("REJECTED"));
    }

    @Test
    void createSquadDelegatesToSquadService() {
        SquadVO created = new SquadVO();
        created.setId(50L);
        created.setName("新小队");
        when(squadService.create(any(CreateSquadRequest.class), eq(WORKSPACE_ID), eq(USER_ID)))
                .thenReturn(created);

        Object result = call(principal, "autowonder.create_squad",
                Map.of("name", "新小队", "description", "测试小队"));

        assertSame(created, result);
        verify(squadService).create(argThat(req ->
                "新小队".equals(req.getName()) && "测试小队".equals(req.getDescription())),
                eq(WORKSPACE_ID), eq(USER_ID));
    }

    @Test
    void createSquadSchemaRequiresName() {
        Map<String, Object> schema = schemaFor("autowonder.create_squad");
        assertEquals(List.of("workspaceId", "name"), schema.get("required"));
        assertTrue(properties(schema).keySet().containsAll(List.of("name", "description")));
    }

    @Test
    void createSquadWithoutPermissionFails() {
        McpAccessTokenService.Principal readOnly = principal(WorkspaceAccessLevel.READ_ONLY);

        BizException ex = assertThrows(BizException.class,
                () -> call(readOnly, "autowonder.create_squad",
                        Map.of("name", "新小队")));

        assertEquals("10403", ex.getCode());
        verifyNoInteractions(squadService);
    }

    @Test
    void createSquadOutputSchemaReturnsSquad() {
        Map<String, Object> output = outputSchemaFor("autowonder.create_squad");
        assertTrue(properties(output).keySet().containsAll(List.of("id", "name", "version")));
    }

    @Test
    void setAgentDefaultSdlcPreservesExistingConfigAndSetsSdlcId() {
        AgentVO agent = new AgentVO();
        agent.setId(10L);
        agent.setRoleName("开发");
        agent.setRoleCode("DEV");
        agent.setBusinessBackground("soul content");
        agent.setResponsibilities("agent content");
        when(agentService.get(10L, WORKSPACE_ID)).thenReturn(agent);
        AgentVersionVO versionVO = new AgentVersionVO();
        versionVO.setId(200L);
        versionVO.setAgentId(10L);
        versionVO.setSdlcId(40103L);
        when(agentService.editConfig(eq(10L), any(UpdateConfigRequest.class), eq(WORKSPACE_ID), eq(USER_ID)))
                .thenReturn(versionVO);

        Object result = call(principal, "autowonder.set_agent_default_sdlc",
                Map.of("agentId", 10L, "sdlcId", 40103L));

        assertTrue(result instanceof Map<?, ?>);
        Map<?, ?> map = (Map<?, ?>) result;
        assertEquals(10L, map.get("agentId"));
        assertEquals(200L, map.get("editingVersionId"));
        assertEquals(40103L, map.get("sdlcId"));
        verify(agentService).editConfig(eq(10L), argThat(req ->
                "开发".equals(req.getRoleName())
                        && "DEV".equals(req.getRoleCode())
                        && "soul content".equals(req.getBusinessBackground())
                        && "agent content".equals(req.getResponsibilities())
                        && Long.valueOf(40103L).equals(req.getSdlcId())),
                eq(WORKSPACE_ID), eq(USER_ID));
    }

    @Test
    void setAgentDefaultSdlcSchemaRequiresAgentIdAndSdlcId() {
        Map<String, Object> schema = schemaFor("autowonder.set_agent_default_sdlc");
        assertEquals(List.of("workspaceId", "agentId", "sdlcId"), schema.get("required"));
        assertTrue(properties(schema).keySet().containsAll(List.of("agentId", "sdlcId")));
    }

    @Test
    void setAgentDefaultSdlcWithoutPermissionFails() {
        McpAccessTokenService.Principal readOnly = principal(WorkspaceAccessLevel.READ_ONLY);

        BizException ex = assertThrows(BizException.class,
                () -> call(readOnly, "autowonder.set_agent_default_sdlc",
                        Map.of("agentId", 10L, "sdlcId", 40103L)));

        assertEquals("10403", ex.getCode());
        verifyNoInteractions(agentService);
    }

    @Test
    void setAgentDefaultSdlcOutputSchemaExposesExpectedFields() {
        Map<String, Object> output = outputSchemaFor("autowonder.set_agent_default_sdlc");
        assertTrue(properties(output).keySet().containsAll(
                List.of("agentId", "editingVersionId", "sdlcId")));
    }

    @Test
    void setAgentDefaultSdlcDescriptionMentionsReviewAndPublish() {
        McpToolVO tool = toolFor("autowonder.set_agent_default_sdlc");
        assertTrue(tool.getDescription().contains("submit_agent_for_review"));
        assertTrue(tool.getDescription().contains("publish_agent"));
    }

    @Test
    void createSquadAndSetAgentDefaultSdlcAreRegisteredInCatalog() {
        Set<String> names = service.listTools().stream()
                .map(McpToolVO::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(names.contains("autowonder.create_squad"));
        assertTrue(names.contains("autowonder.set_agent_default_sdlc"));
    }

    private McpAccessTokenService.Principal dispatchPrincipal() {
        return dispatchPrincipal(-321L);
    }

    @Test
    void dispatchCredentialUsesOriginalGenericTransitionForEveryWorkitemStatusTool() {
        for (String tool : List.of("autowonder.transition_workitem",
                "autowonder.pause_workitem", "autowonder.resume_workitem")) {
            call(dispatchPrincipal(), tool, Map.of("id", 55L, "toNodeId", 99L));
        }

        verify(workitemService, times(3)).transition(55L, 99L, WORKSPACE_ID, USER_ID);
    }

    @Test
    void personalCredentialKeepsHumanWorkitemTransition() {
        call(principal, "autowonder.transition_workitem", Map.of("id", 55L, "toNodeId", 99L));

        verify(workitemService).transition(55L, 99L, WORKSPACE_ID, USER_ID);
        verify(workitemService, never()).agentTransition(anyLong(), anyString(), anyLong(), anyLong());
    }

    @Test
    void conversationCredentialUsesOriginalGenericTransition() {
        McpAccessTokenService.Principal conversationPrincipal = new McpAccessTokenService.Principal(
                WORKSPACE_ID, USER_ID, 88L, WorkspaceAccessLevel.READ_WRITE,
                McpAccessTokenService.CredentialType.CONVERSATION);

        call(conversationPrincipal, "autowonder.transition_workitem",
                Map.of("id", 55L, "toNodeId", 99L));

        verify(workitemService).transition(55L, 99L, WORKSPACE_ID, USER_ID);
    }

    private MemoryVO memory(long id, String scope, Long ownerRef) {
        MemoryVO memory = new MemoryVO();
        memory.setId(id);
        memory.setScope(scope);
        memory.setOwnerRef(ownerRef);
        memory.setStatus("ADOPTED");
        return memory;
    }

    private DispatchDO dispatch(long id, long tenantId, long workitemId, long agentId) {
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(id);
        dispatch.setTenantId(tenantId);
        dispatch.setWorkitemId(workitemId);
        dispatch.setAgentId(agentId);
        return dispatch;
    }

    private CategoryVO category(long id, Long parentId, String name) {
        CategoryVO vo = new CategoryVO();
        vo.setId(id);
        vo.setParentId(parentId);
        vo.setName(name);
        vo.setPath(name);
        vo.setVersion(0);
        return vo;
    }

    @Test
    void createSdlcDelegatesToSdlcService() {
        SdlcVO sdlc = new SdlcVO();
        sdlc.setId(3L);
        when(sdlcService.create(any(), eq(100L), eq(7L))).thenReturn(sdlc);

        Object result = call(principal, "autowonder.create_sdlc",
                Map.of("name", "研发流程", "workType", "REQ"));

        assertSame(sdlc, result);
        verify(sdlcService).create(argThat(req -> "研发流程".equals(req.getName())), eq(100L), eq(7L));
    }

    @Test
    void updateSkillCanManagePluginRecords() {
        SkillVO skill = new SkillVO();
        skill.setId(5L);
        skill.setType("PLUGIN");
        when(skillService.update(eq(5L), any(), eq(100L), eq(7L))).thenReturn(skill);

        Object result = call(principal, "autowonder.update_skill",
                Map.of("id", 5L, "type", "PLUGIN", "name", "GitHub"));

        assertSame(skill, result);
        verify(skillService).update(eq(5L), argThat(req -> "PLUGIN".equals(req.getType())), eq(100L), eq(7L));
    }

    @Test
    void listCategoriesDelegatesAndFiltersByKeywordOnNameOrPath() {
        CategoryVO front = category(1L, null, "前端");
        CategoryVO backend = category(2L, null, "后端 → 数据库");
        when(categoryService.list(100L)).thenReturn(List.of(front, backend));

        assertEquals(List.of(front, backend), call(principal, "autowonder.list_categories", Map.of()));
        assertEquals(List.of(front), call(principal, "autowonder.list_categories", Map.of("keyword", "前端")));
        // keyword 命中 path 也要保留该节点
        assertEquals(List.of(backend), call(principal, "autowonder.list_categories", Map.of("keyword", "数据库")));
        assertEquals(List.of(), call(principal, "autowonder.list_categories", Map.of("keyword", "不存在")));
    }

    @Test
    void getCategoryDelegatesAndRequiresId() {
        CategoryVO vo = category(3L, null, "编码");
        when(categoryService.get(3L, 100L)).thenReturn(vo);

        assertSame(vo, call(principal, "autowonder.get_category", Map.of("id", 3L)));

        BizException missing = assertThrows(BizException.class,
                () -> call(principal, "autowonder.get_category", Map.of()));
        assertEquals(ErrorCode.MCP_TOOL_ARGUMENT_INVALID.getCode(), missing.getCode());
    }

    @Test
    void createCategoryDelegatesAndRequiresName() {
        BizException missing = assertThrows(BizException.class,
                () -> call(principal(WorkspaceAccessLevel.ADMIN), "autowonder.create_category", Map.of("parentId", 2L)));
        assertEquals(ErrorCode.MCP_TOOL_ARGUMENT_INVALID.getCode(), missing.getCode());
        verify(categoryService, never()).create(any(), anyLong(), anyLong());

        CategoryVO vo = category(9L, 2L, "前端");
        when(categoryService.create(any(), eq(100L), eq(7L))).thenReturn(vo);

        assertSame(vo, call(principal(WorkspaceAccessLevel.ADMIN), "autowonder.create_category",
                Map.of("name", "前端", "parentId", 2L, "description", "页面开发")));

        verify(categoryService).create(argThat(req -> "前端".equals(req.getName())
                && Long.valueOf(2L).equals(req.getParentId())
                && "页面开发".equals(req.getDescription())), eq(100L), eq(7L));
    }

    @Test
    void updateCategoryMapsOnlyPresentFields() {
        CategoryVO vo = category(4L, null, "新名");
        when(categoryService.update(eq(4L), any(), eq(100L), eq(7L))).thenReturn(vo);

        assertSame(vo, call(principal(WorkspaceAccessLevel.ADMIN), "autowonder.update_category", Map.of("id", 4L, "name", "新名")));

        verify(categoryService).update(eq(4L), argThat(req -> req.isNamePresent()
                && "新名".equals(req.getName())
                && !req.isParentIdPresent() && !req.isDescriptionPresent()),
                eq(100L), eq(7L));
    }

    @Test
    void updateCategoryTreatsExplicitNullParentAsMoveToRoot() {
        when(categoryService.update(eq(4L), any(), eq(100L), eq(7L))).thenReturn(category(4L, null, "编码"));

        Map<String, Object> moveToRoot = new HashMap<>();
        moveToRoot.put("id", 4L);
        moveToRoot.put("parentId", null);

        call(principal(WorkspaceAccessLevel.ADMIN), "autowonder.update_category", moveToRoot);

        verify(categoryService).update(eq(4L), argThat(req -> req.isParentIdPresent()
                && req.getParentId() == null && !req.isNamePresent()), eq(100L), eq(7L));
    }

    @Test
    void deleteCategoryDelegatesAndReturnsDeletedFlag() {
        assertEquals(Map.of("deleted", true),
                call(principal(WorkspaceAccessLevel.ADMIN), "autowonder.delete_category", Map.of("id", 4L)));

        verify(categoryService).delete(4L, 100L, 7L);
    }

    @Test
    void setSkillCategoryRequiresExplicitCategoryIdArgument() {
        BizException missing = assertThrows(BizException.class,
                () -> call(principal, "autowonder.set_skill_category", Map.of("skillId", 5L)));
        assertEquals(ErrorCode.MCP_TOOL_ARGUMENT_INVALID.getCode(), missing.getCode());
        verify(categoryService, never()).setSkillCategory(anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    void setSkillCategoryTagsWithNumberAndClearsWithExplicitNull() {
        when(categoryService.setSkillCategory(5L, 9L, 100L, 7L)).thenReturn(9L);

        assertEquals(Map.of("categoryId", 9L),
                call(principal, "autowonder.set_skill_category", Map.of("skillId", 5L, "categoryId", 9L)));

        when(categoryService.setSkillCategory(5L, null, 100L, 7L)).thenReturn(null);
        Map<String, Object> clear = new HashMap<>();
        clear.put("skillId", 5L);
        clear.put("categoryId", null);

        // 取消打标返回的 categoryId 是 null，结果体也必须允许 null 值
        assertEquals(java.util.Collections.singletonMap("categoryId", null),
                call(principal, "autowonder.set_skill_category", clear));

        verify(categoryService).setSkillCategory(5L, 9L, 100L, 7L);
        verify(categoryService).setSkillCategory(5L, null, 100L, 7L);
    }

    @Test
    void batchSetSkillCategoryDelegatesWithNullableCategoryAndPerItemResults() {
        BatchSkillCategoryResultVO ok = new BatchSkillCategoryResultVO();
        ok.setSkillId(5L);
        ok.setSuccess(true);
        ok.setMessage("已设置分类");
        BatchSkillCategoryResultVO failed = new BatchSkillCategoryResultVO();
        failed.setSkillId(6L);
        failed.setSuccess(false);
        failed.setMessage("技能不存在");
        when(categoryService.batchSetSkillCategory(List.of(5L, 6L), 9L, 100L, 7L))
                .thenReturn(List.of(ok, failed));

        assertEquals(List.of(ok, failed), call(principal, "autowonder.batch_set_skill_category",
                Map.of("skillIds", List.of(5L, 6L), "categoryId", 9L)));

        Map<String, Object> clearAll = new HashMap<>();
        clearAll.put("skillIds", List.of(5L));
        clearAll.put("categoryId", null);
        when(categoryService.batchSetSkillCategory(List.of(5L), null, 100L, 7L)).thenReturn(List.of(ok));

        assertEquals(List.of(ok), call(principal, "autowonder.batch_set_skill_category", clearAll));

        BizException missing = assertThrows(BizException.class,
                () -> call(principal, "autowonder.batch_set_skill_category", Map.of("skillIds", List.of(5L))));
        assertEquals(ErrorCode.MCP_TOOL_ARGUMENT_INVALID.getCode(), missing.getCode());
    }

    @Test
    void categoryMaintenanceRejectsEditorsButTaggingRemainsAllowed() {
        var editor = principal(WorkspaceAccessLevel.READ_WRITE);
        for (String tool : List.of("autowonder.create_category", "autowonder.update_category", "autowonder.delete_category")) {
            BizException ex = assertThrows(BizException.class,
                    () -> call(editor, tool, Map.of("id", 1L, "name", "test")));
            assertEquals("10403", ex.getCode());
        }
        call(editor, "autowonder.set_skill_category", Map.of("skillId", 1L, "categoryId", 2L));
        verify(categoryService).setSkillCategory(1L, 2L, 100L, 7L);
    }

    @Test
    void categoryToolsRejectInvalidIdsBeforeCallingService() {
        var admin = principal(WorkspaceAccessLevel.ADMIN);
        for (Object id : List.of(10000.9, new java.math.BigInteger("9223372036854775808"), "10000", true, 0L, -1L)) {
            assertThrows(BizException.class, () -> call(admin, "autowonder.set_skill_category", Map.of("skillId", 1L, "categoryId", id)));
            assertThrows(BizException.class, () -> call(admin, "autowonder.batch_set_skill_category", Map.of("skillIds", List.of(id), "categoryId", 2L)));
            assertThrows(BizException.class, () -> call(admin, "autowonder.create_category", Map.of("name", "test", "parentId", id)));
            assertThrows(BizException.class, () -> call(admin, "autowonder.update_category", Map.of("id", 1L, "parentId", id)));
            assertThrows(BizException.class, () -> call(admin, "autowonder.delete_category", Map.of("id", id)));
        }
        verifyNoInteractions(categoryService);
    }

    @Test
    void categoryMaintenanceAndTaggingToolsRequireReadWrite() {
        McpAccessTokenService.Principal readOnly = principal(WorkspaceAccessLevel.READ_ONLY);

        for (String tool : List.of("autowonder.create_category", "autowonder.update_category",
                "autowonder.delete_category", "autowonder.set_skill_category",
                "autowonder.batch_set_skill_category")) {
            BizException ex = assertThrows(BizException.class,
                    () -> call(readOnly, tool, Map.of("id", 1L, "name", "x", "skillId", 1L,
                            "skillIds", List.of(1L), "categoryId", 1L)),
                    tool + " 必须拒绝 READ_ONLY 调用");
            assertEquals("10403", ex.getCode(), tool);
        }

        // 查询类分类工具对只读成员保持可用
        when(categoryService.list(100L)).thenReturn(List.of(category(1L, null, "编码")));
        assertEquals(1, ((List<?>) call(readOnly, "autowonder.list_categories", Map.of())).size());
        when(categoryService.get(1L, 100L)).thenReturn(category(1L, null, "编码"));
        assertNotNull(call(readOnly, "autowonder.get_category", Map.of("id", 1L)));
    }

    @Test
    void listSkillsForwardsCategoryFiltersWithDefaults() {
        SkillVO skill = new SkillVO();
        skill.setId(4L);
        when(skillService.list(100L, "SKILL", 3L, false, true, 1, 20)).thenReturn(List.of(skill));

        assertEquals(List.of(skill), call(principal, "autowonder.list_skills",
                Map.of("type", "SKILL", "categoryId", 3L, "includeDescendants", false,
                        "uncategorized", true, "page", 1, "size", 20)));
        verify(skillService).list(100L, "SKILL", 3L, false, true, 1, 20);

        // 不传分类参数时保持既有调用契约（includeDescendants 默认 true、uncategorized 默认 false）
        when(skillService.list(100L, null, null, true, false, 1, 20)).thenReturn(List.of());
        assertEquals(List.of(), call(principal, "autowonder.list_skills", Map.of()));
        verify(skillService).list(100L, null, null, true, false, 1, 20);
    }

    @Test
    void categoryToolSchemasExposeRequiredKeysAndNullableCategoryId() {
        Map<String, Object> setSchema = schemaFor("autowonder.set_skill_category");
        assertTrue(((List<?>) setSchema.get("required")).containsAll(List.of("skillId", "categoryId")));
        assertEquals("integer", property(setSchema, "skillId").get("type"));
        // 声明 integer 的同时必须允许 null：显式 null 是唯一合法的取消打标方式
        assertEquals(List.of("integer", "null"), property(setSchema, "categoryId").get("type"));

        Map<String, Object> batchSchema = schemaFor("autowonder.batch_set_skill_category");
        assertTrue(((List<?>) batchSchema.get("required")).containsAll(List.of("skillIds", "categoryId")));
        assertEquals(List.of("integer", "null"), property(batchSchema, "categoryId").get("type"));

        for (String tool : List.of("autowonder.get_category", "autowonder.update_category",
                "autowonder.delete_category")) {
            assertTrue(((List<?>) schemaFor(tool).get("required")).contains("id"), tool);
        }
        assertFalse(((List<?>) schemaFor("autowonder.create_category").get("required")).contains("parentId"),
                "parentId 可省略表示顶级分类");
        assertTrue(((List<?>) schemaFor("autowonder.create_category").get("required")).contains("name"));
        assertEquals(List.of("integer", "null"),
                property(schemaFor("autowonder.update_category"), "parentId").get("type"),
                "parentId 显式 null 表示移动到顶级分类");
    }

    @Test
    void createWorkitemWithoutPermissionFails() {
        McpAccessTokenService.Principal readOnly =
                principal(WorkspaceAccessLevel.READ_ONLY);

        BizException ex = assertThrows(BizException.class,
                () -> call(readOnly, "autowonder.create_workitem",
                        Map.of("workType", "REQ")));

        assertEquals("10403", ex.getCode());
        verifyNoInteractions(workitemService);
    }

    @Test
    void installPlatformSkillIsIdempotentOnDuplicate() {
        when(skillService.create(any(), eq(100L), eq(7L)))
                .thenThrow(new BizException(com.aliyun.autowonder.common.error.ErrorCode.SKILL_DUPLICATE_NAME));
        SkillVO existing = new SkillVO();
        existing.setId(5L);
        existing.setName("AutoWonder Workitem Operator");
        when(skillService.list(100L, "CODEX_SKILL", 1, 100)).thenReturn(java.util.List.of(existing));

        Object result = call(principal, "autowonder.install_platform_skill",
                Map.of("skillId", "autowonder-workitem-operator"));

        assertSame(existing, result);
    }

    @Test
    void unknownToolFails() {
        BizException ex = assertThrows(BizException.class,
                () -> call(principal, "missing", Map.of()));
        assertEquals("27002", ex.getCode());
    }

    @Test
    void deleteAgentSchemaDeclaresIdOnly() {
        Map<String, Object> schema = schemaFor("autowonder.delete_agent");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        assertTrue(properties.containsKey("id"));
        assertEquals(List.of("workspaceId", "id"), schema.get("required"));
    }

    @Test
    void deleteAgentDeletesWhenPermitted() {

        Object result = call(principal, "autowonder.delete_agent", Map.of("id", 5L));

        assertEquals(Map.of("deleted", true), result);
        verify(agentService).delete(5L, 100L, 7L);
    }

    @Test
    void deleteAgentWithoutPermissionFails() {
        McpAccessTokenService.Principal readOnly =
                principal(WorkspaceAccessLevel.READ_ONLY);

        BizException ex = assertThrows(BizException.class,
                () -> call(readOnly, "autowonder.delete_agent",
                        Map.of("id", 5L)));

        assertEquals("10403", ex.getCode());
        verifyNoInteractions(agentService);
    }

    @Test
    void submitAgentForReviewDelegatesToAgentService() {
        AgentVO submitted = new AgentVO();
        submitted.setId(10L);
        submitted.setStatus("PENDING_REVIEW");
        when(agentService.submit(10L, 100L, 7L)).thenReturn(submitted);

        Object result = call(principal, "autowonder.submit_agent_for_review",
                Map.of("id", 10L));

        assertSame(submitted, result);
        verify(agentService).submit(10L, 100L, 7L);
    }

    @Test
    void submitAgentForReviewSchemaDeclaresIdAndOptionalComment() {
        Map<String, Object> schema = schemaFor("autowonder.submit_agent_for_review");
        assertEquals(List.of("workspaceId", "id"), schema.get("required"));
        assertTrue(properties(schema).keySet().containsAll(List.of("id", "comment")));
    }

    @Test
    void publishAgentDelegatesToAgentServiceApprove() {
        AgentVO published = new AgentVO();
        published.setId(10L);
        published.setStatus("ONLINE");
        when(agentService.approve(10L, 100L, 7L, null)).thenReturn(published);

        Object result = call(principal, "autowonder.publish_agent",
                Map.of("id", 10L));

        assertSame(published, result);
        verify(agentService).approve(10L, 100L, 7L, null);
    }

    @Test
    void publishAgentSchemaDeclaresOnlyIdRequired() {
        Map<String, Object> schema = schemaFor("autowonder.publish_agent");
        assertEquals(List.of("workspaceId", "id"), schema.get("required"));
        assertTrue(properties(schema).containsKey("id"));
    }

    @Test
    void getAgentVersionStatusReturnsAgentAndVersions() {
        AgentVO agent = new AgentVO();
        agent.setId(10L);
        agent.setStatus("ONLINE");
        AgentVersionSummaryVO v1 = new AgentVersionSummaryVO();
        AgentVersionSummaryVO v2 = new AgentVersionSummaryVO();
        when(agentService.get(10L, WORKSPACE_ID)).thenReturn(agent);
        when(agentService.listVersions(10L, WORKSPACE_ID)).thenReturn(List.of(v1, v2));

        Object result = call(principal, "autowonder.get_agent_version_status",
                Map.of("id", 10L));

        assertTrue(result instanceof Map<?, ?>);
        Map<?, ?> map = (Map<?, ?>) result;
        assertSame(agent, map.get("agent"));
        assertEquals(List.of(v1, v2), map.get("versions"));
        verify(agentService).get(10L, WORKSPACE_ID);
        verify(agentService).listVersions(10L, WORKSPACE_ID);
    }

    @Test
    void getAgentCannotReturnAnotherWorkspacesAgent() {
        when(agentService.get(10L, WORKSPACE_ID)).thenThrow(new BizException(ErrorCode.AGENT_NOT_FOUND));

        BizException error = assertThrows(BizException.class,
                () -> call(principal, "autowonder.get_agent", Map.of("id", 10L)));

        assertEquals("14001", error.getCode());
        verify(agentService).get(10L, WORKSPACE_ID);
        verifyNoMoreInteractions(agentService);
    }

    @Test
    void getAgentVersionStatusCannotEnumerateAnotherWorkspacesAgent() {
        when(agentService.get(10L, WORKSPACE_ID)).thenThrow(new BizException(ErrorCode.AGENT_NOT_FOUND));

        BizException error = assertThrows(BizException.class,
                () -> call(principal, "autowonder.get_agent_version_status", Map.of("id", 10L)));

        assertEquals("14001", error.getCode());
        verify(agentService, never()).listVersions(anyLong(), anyLong());
    }

    @Test
    void getAgentVersionReturnsFullEditingVersion() {
        AgentVersionVO version = new AgentVersionVO();
        version.setAgentId(10L);
        version.setVersionNo(6);
        when(agentService.getVersion(10L, 6, WORKSPACE_ID)).thenReturn(version);

        Object result = call(principal, "autowonder.get_agent_version",
                Map.of("agentId", 10L, "versionNo", 6));

        assertSame(version, result);
        verify(agentService).getVersion(10L, 6, WORKSPACE_ID);
    }

    @Test
    void updateAgentConfigMapsMarkdownAndSdlcToExistingService() {
        AgentVersionVO version = new AgentVersionVO();
        version.setAgentId(10L);
        when(agentService.editConfig(eq(10L), any(UpdateConfigRequest.class), eq(WORKSPACE_ID), eq(USER_ID)))
                .thenReturn(version);

        Object result = call(principal, "autowonder.update_agent_config", Map.of(
                "agentId", 10L,
                "roleName", "Terraform engineer",
                "roleCode", "jarvis-terraform",
                "soulMd", "SOUL",
                "agentMd", "AGENT",
                "sdlcId", 88L,
                "evolutionMode", "MANUAL"));

        assertSame(version, result);
        verify(agentService).editConfig(eq(10L), argThat(request ->
                        "Terraform engineer".equals(request.getRoleName())
                                && "jarvis-terraform".equals(request.getRoleCode())
                                && "SOUL".equals(request.getBusinessBackground())
                                && "AGENT".equals(request.getResponsibilities())
                                && Long.valueOf(88L).equals(request.getSdlcId())
                                && "MANUAL".equals(request.getEvolutionMode())
                                && request.getProvidedFields() != null
                                && request.getProvidedFields().containsAll(java.util.Set.of(
                                        "roleName", "roleCode", "businessBackground",
                                        "responsibilities", "sdlcId", "evolutionMode"))),
                eq(WORKSPACE_ID), eq(USER_ID));
    }

    @Test
    void agentVersionMcpSchemasExposeConfigAndExactBindings() {
        Map<String, Object> getInput = schemaFor("autowonder.get_agent_version");
        assertEquals(List.of("workspaceId", "agentId", "versionNo"), getInput.get("required"));

        Map<String, Object> updateInput = properties(schemaFor("autowonder.update_agent_config"));
        assertTrue(updateInput.keySet().containsAll(List.of(
                "agentId", "roleName", "roleCode", "soulMd", "agentMd", "sdlcId", "evolutionMode")));

        Map<String, Object> output = properties(outputSchemaFor("autowonder.get_agent_version"));
        assertTrue(output.keySet().containsAll(List.of(
                "agentId", "versionNo", "status", "sdlcId", "repoPerms", "skills", "memoryRefs")));
        assertEquals("array", output.get("repoPerms") instanceof Map<?, ?> repoPerms
                ? repoPerms.get("type") : null);
    }

    @Test
    void getAgentVersionStatusOutputSchemaExposesAgentAndVersions() {
        Map<String, Object> output = outputSchemaFor("autowonder.get_agent_version_status");
        assertTrue(properties(output).keySet().containsAll(List.of("agent", "versions")));
        Map<String, Object> versions = property(output, "versions");
        assertEquals("array", versions.get("type"));
    }

    @Test
    void agentLifecycleToolsAreRegisteredInCatalog() {
        Set<String> names = service.listTools().stream()
                .map(McpToolVO::getName)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(names.contains("autowonder.submit_agent_for_review"));
        assertTrue(names.contains("autowonder.publish_agent"));
        assertTrue(names.contains("autowonder.get_agent_version"));
        assertTrue(names.contains("autowonder.update_agent_config"));
        assertTrue(names.contains("autowonder.get_agent_version_status"));
        assertTrue(names.contains("autowonder.unbind_agent_repos"));
        assertTrue(names.contains("autowonder.unbind_agent_skills"));
        assertTrue(names.contains("autowonder.unbind_agent_memories"));
    }

    @Test
    void agentIdentityInputSchemasExposeSoulAndAgentMarkdownOnly() {
        for (String toolName : List.of("autowonder.create_agent", "autowonder.update_agent")) {
            Map<String, Object> inputProperties = properties(schemaFor(toolName));

            assertTrue(inputProperties.containsKey("soulMd"), toolName);
            assertTrue(inputProperties.containsKey("agentMd"), toolName);
            assertFalse(inputProperties.containsKey("businessBackground"), toolName);
            assertFalse(inputProperties.containsKey("responsibilities"), toolName);
            assertSchemaProperty(inputProperties, "soulMd", "string",
                    "SOUL.md Markdown content for the digital worker.");
            assertSchemaProperty(inputProperties, "agentMd", "string",
                    "AGENT.md Markdown content for the digital worker.");
        }
    }

    @Test
    void createAgentMapsSoulAndAgentMarkdownToStableRequestFields() {
        call(principal, "autowonder.create_agent", Map.of(
                "name", "Writer", "soulMd", "new soul", "agentMd", "new agent"));

        verify(agentService).create(argThat(request ->
                        "new soul".equals(request.getBusinessBackground())
                                && "new agent".equals(request.getResponsibilities())),
                eq(WORKSPACE_ID), eq(USER_ID));
    }

    @Test
    void updateAgentMapsSoulAndAgentMarkdownToStableRequestFields() {
        call(principal, "autowonder.update_agent", Map.of(
                "id", 12L, "soulMd", "new soul", "agentMd", "new agent"));

        verify(agentService).updateAgent(argThat(request ->
                        request.getId() == 12L
                                && "new soul".equals(request.getBusinessBackground())
                                && "new agent".equals(request.getResponsibilities())),
                eq(WORKSPACE_ID), eq(USER_ID));
    }

    @Test
    void createAndUpdateAgentAcceptHiddenLegacyIdentityArguments() {
        call(principal, "autowonder.create_agent", Map.of(
                "name", "Writer", "businessBackground", "legacy soul",
                "responsibilities", "legacy agent"));
        call(principal, "autowonder.update_agent", Map.of(
                "id", 12L, "businessBackground", "legacy soul",
                "responsibilities", "legacy agent"));

        verify(agentService).create(argThat(request ->
                        "legacy soul".equals(request.getBusinessBackground())
                                && "legacy agent".equals(request.getResponsibilities())),
                eq(WORKSPACE_ID), eq(USER_ID));
        verify(agentService).updateAgent(argThat(request ->
                        "legacy soul".equals(request.getBusinessBackground())
                                && "legacy agent".equals(request.getResponsibilities())),
                eq(WORKSPACE_ID), eq(USER_ID));
    }

    @Test
    void newAgentIdentityArgumentsOverrideLegacyArguments() {
        call(principal, "autowonder.create_agent", Map.of(
                "name", "Writer", "businessBackground", "legacy soul",
                "responsibilities", "legacy agent", "soulMd", "new soul", "agentMd", "new agent"));
        call(principal, "autowonder.update_agent", Map.of(
                "id", 12L, "businessBackground", "legacy soul",
                "responsibilities", "legacy agent", "soulMd", "new soul", "agentMd", "new agent"));

        verify(agentService).create(argThat(request ->
                        "new soul".equals(request.getBusinessBackground())
                                && "new agent".equals(request.getResponsibilities())),
                eq(WORKSPACE_ID), eq(USER_ID));
        verify(agentService).updateAgent(argThat(request ->
                        "new soul".equals(request.getBusinessBackground())
                                && "new agent".equals(request.getResponsibilities())),
                eq(WORKSPACE_ID), eq(USER_ID));
    }

    @Test
    void nullNewAgentIdentityArgumentsOverrideLegacyArguments() {
        Map<String, Object> createArgs = new java.util.LinkedHashMap<>();
        createArgs.put("name", "Writer");
        createArgs.put("businessBackground", "legacy soul");
        createArgs.put("responsibilities", "legacy agent");
        createArgs.put("soulMd", null);
        createArgs.put("agentMd", "new agent");
        call(principal, "autowonder.create_agent", createArgs);

        Map<String, Object> updateArgs = new java.util.LinkedHashMap<>();
        updateArgs.put("id", 12L);
        updateArgs.put("businessBackground", "legacy soul");
        updateArgs.put("responsibilities", "legacy agent");
        updateArgs.put("soulMd", "new soul");
        updateArgs.put("agentMd", null);
        call(principal, "autowonder.update_agent", updateArgs);

        verify(agentService).create(argThat(request ->
                        request.getBusinessBackground() == null
                                && "new agent".equals(request.getResponsibilities())),
                eq(WORKSPACE_ID), eq(USER_ID));
        verify(agentService).updateAgent(argThat(request ->
                        "new soul".equals(request.getBusinessBackground())
                                && request.getResponsibilities() == null),
                eq(WORKSPACE_ID), eq(USER_ID));
    }

    @Test
    void updateAgentConfigOnlyMarksProvidedFieldsForPartialUpdates() {
        call(principal, "autowonder.update_agent_config", Map.of(
                "agentId", 10L,
                "agentMd", "AGENT only"));

        verify(agentService).editConfig(eq(10L), argThat(request ->
                        "AGENT only".equals(request.getResponsibilities())
                                && request.getProvidedFields() != null
                                && request.getProvidedFields().equals(java.util.Set.of("responsibilities"))),
                eq(WORKSPACE_ID), eq(USER_ID));
    }

    @Test
    void updateAgentMarksExplicitNullFieldsAsProvidedAndSkipsOmittedOnes() {
        Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("id", 12L);
        args.put("soulMd", null);
        call(principal, "autowonder.update_agent", args);

        verify(agentService).updateAgent(argThat(request ->
                        request.getBusinessBackground() == null
                                && request.getProvidedFields() != null
                                && request.getProvidedFields().contains("businessBackground")
                                && !request.getProvidedFields().contains("responsibilities")
                                && !request.getProvidedFields().contains("roleCode")),
                eq(WORKSPACE_ID), eq(USER_ID));
    }

    @Test
    void agentUpdateToolDescriptionsExplainPartialUpdateSemantics() {
        for (String toolName : List.of("autowonder.update_agent", "autowonder.update_agent_config")) {
            String description = toolFor(toolName).getDescription();
            assertTrue(description.contains("omit"), toolName);
            assertTrue(description.contains("keep its current value"), toolName);
            assertTrue(description.contains("pass null to clear"), toolName);
        }
    }

    @Test
    void updateAgentLifecycleOfflineTakesAnOnlineWorkerOffline() {
        AgentVO offlined = new AgentVO();
        offlined.setId(12L);
        offlined.setStatus("OFFLINE");
        offlined.setOnlineVersionId(null);
        when(agentService.offline(12L, WORKSPACE_ID, USER_ID)).thenReturn(offlined);

        Object result = call(principal, "autowonder.update_agent",
                Map.of("id", 12L, "lifecycleAction", "offline"));

        // AC1：下线后状态为 OFFLINE 且线上版本被清空，后续派发不再路由到该数字人
        assertSame(offlined, result);
        assertEquals("OFFLINE", ((AgentVO) result).getStatus());
        assertNull(((AgentVO) result).getOnlineVersionId());
        verify(agentService).offline(12L, WORKSPACE_ID, USER_ID);
        verify(agentService, never()).online(anyLong(), anyLong(), anyLong());
        verify(agentService, never()).updateAgent(any(), anyLong(), anyLong());
    }

    @Test
    void updateAgentLifecycleOnlineRestoresTheMostRecentApprovedVersion() {
        AgentVO onlined = new AgentVO();
        onlined.setId(12L);
        onlined.setStatus("ONLINE");
        onlined.setOnlineVersionId(88L);
        when(agentService.online(12L, WORKSPACE_ID, USER_ID)).thenReturn(onlined);

        Object result = call(principal, "autowonder.update_agent",
                Map.of("id", 12L, "lifecycleAction", "online"));

        // AC2：重新上线后状态为 ONLINE，线上版本恢复为最近一个已批准版本
        assertSame(onlined, result);
        assertEquals("ONLINE", ((AgentVO) result).getStatus());
        assertEquals(Long.valueOf(88L), ((AgentVO) result).getOnlineVersionId());
        verify(agentService).online(12L, WORKSPACE_ID, USER_ID);
        verify(agentService, never()).offline(anyLong(), anyLong(), anyLong());
        verify(agentService, never()).updateAgent(any(), anyLong(), anyLong());
    }

    @Test
    void updateAgentLifecycleOfflinePropagatesTheConsoleRejectionsUnchanged() {
        when(agentService.offline(21L, WORKSPACE_ID, USER_ID))
                .thenThrow(new BizException(ErrorCode.AGENT_PLATFORM_NO_OFFLINE));
        when(agentService.offline(22L, WORKSPACE_ID, USER_ID))
                .thenThrow(new BizException(ErrorCode.AGENT_NOT_ONLINE));
        when(agentService.offline(23L, WORKSPACE_ID, USER_ID))
                .thenThrow(new BizException(ErrorCode.AGENT_VERSION_CONFLICT));

        // AC3：平台内置数字人不可下线
        BizException platform = assertThrows(BizException.class, () -> call(principal,
                "autowonder.update_agent", Map.of("id", 21L, "lifecycleAction", "offline")));
        assertEquals("14013", platform.getCode());

        // AC4：对已下线数字人重复下线
        BizException notOnline = assertThrows(BizException.class, () -> call(principal,
                "autowonder.update_agent", Map.of("id", 22L, "lifecycleAction", "offline")));
        assertEquals("14006", notOnline.getCode());

        // FR4：并发修改冲突沿用控制台乐观锁错误码
        BizException conflict = assertThrows(BizException.class, () -> call(principal,
                "autowonder.update_agent", Map.of("id", 23L, "lifecycleAction", "offline")));
        assertEquals("14003", conflict.getCode());

        verify(agentService, never()).updateAgent(any(), anyLong(), anyLong());
    }

    @Test
    void updateAgentLifecycleOnlinePropagatesTheConsoleRejectionsUnchanged() {
        when(agentService.online(31L, WORKSPACE_ID, USER_ID))
                .thenThrow(new BizException(ErrorCode.AGENT_NOT_OFFLINE));
        when(agentService.online(32L, WORKSPACE_ID, USER_ID))
                .thenThrow(new BizException(ErrorCode.AGENT_ONLINE_NO_APPROVED_VERSION));
        when(agentService.online(33L, WORKSPACE_ID, USER_ID))
                .thenThrow(new BizException(ErrorCode.AGENT_VERSION_CONFLICT));

        // AC4：对已在线数字人重复上线
        BizException notOffline = assertThrows(BizException.class, () -> call(principal,
                "autowonder.update_agent", Map.of("id", 31L, "lifecycleAction", "online")));
        assertEquals("14010", notOffline.getCode());

        // AC5：无已批准版本时不可重新上线
        BizException noVersion = assertThrows(BizException.class, () -> call(principal,
                "autowonder.update_agent", Map.of("id", 32L, "lifecycleAction", "online")));
        assertEquals("14011", noVersion.getCode());

        // FR4：并发修改冲突沿用控制台乐观锁错误码
        BizException conflict = assertThrows(BizException.class, () -> call(principal,
                "autowonder.update_agent", Map.of("id", 33L, "lifecycleAction", "online")));
        assertEquals("14003", conflict.getCode());

        verify(agentService, never()).updateAgent(any(), anyLong(), anyLong());
    }

    @Test
    void updateAgentLifecycleActionIsMutuallyExclusiveWithFieldUpdates() {
        List<Map<String, Object>> cases = List.of(
                Map.of("id", 12L, "lifecycleAction", "offline", "name", "Writer"),
                Map.of("id", 12L, "lifecycleAction", "online", "soulMd", "new soul"),
                Map.of("id", 12L, "lifecycleAction", "offline", "agentMd", "new agent"),
                Map.of("id", 12L, "lifecycleAction", "online", "roleCode", "AW_FS_DEV"));

        for (Map<String, Object> args : cases) {
            BizException exception = assertThrows(BizException.class,
                    () -> call(principal, "autowonder.update_agent", args));
            assertEquals("27003", exception.getCode(), String.valueOf(args));
            assertTrue(exception.getMessage().contains("互斥"), String.valueOf(args));
        }

        // 互斥时既不做状态切换也不做字段更新，避免半执行
        verifyNoInteractions(agentService);
    }

    @Test
    void updateAgentLifecycleActionRejectsUnknownValuesAndMissingId() {
        List<Map<String, Object>> cases = List.of(
                Map.of("id", 12L, "lifecycleAction", "delete"),
                Map.of("id", 12L, "lifecycleAction", "pause"),
                Map.of("id", 12L, "lifecycleAction", ""),
                Map.of("lifecycleAction", "offline"));

        for (Map<String, Object> args : cases) {
            BizException exception = assertThrows(BizException.class,
                    () -> call(principal, "autowonder.update_agent", args));
            assertEquals("27003", exception.getCode(), String.valueOf(args));
        }

        verifyNoInteractions(agentService);
    }

    @Test
    void updateAgentLifecycleActionNormalizesCaseAndSurroundingWhitespace() {
        AgentVO offlined = new AgentVO();
        offlined.setId(12L);
        offlined.setStatus("OFFLINE");
        when(agentService.offline(12L, WORKSPACE_ID, USER_ID)).thenReturn(offlined);

        Object result = call(principal, "autowonder.update_agent",
                Map.of("id", 12L, "lifecycleAction", "  OFFLINE "));

        assertSame(offlined, result);
        verify(agentService).offline(12L, WORKSPACE_ID, USER_ID);
    }

    @Test
    void updateAgentWithoutLifecycleActionKeepsThePartialUpdateBehaviour() {
        AgentVO updated = new AgentVO();
        updated.setId(12L);
        updated.setStatus("ONLINE");
        when(agentService.updateAgent(any(), eq(WORKSPACE_ID), eq(USER_ID))).thenReturn(updated);

        // AC7：不传 lifecycleAction 的存量调用行为不变，不会触发任何状态切换
        Object result = call(principal, "autowonder.update_agent",
                Map.of("id", 12L, "name", "Writer", "agentMd", "new agent"));

        assertSame(updated, result);
        verify(agentService).updateAgent(argThat(request ->
                        request.getId() == 12L
                                && "Writer".equals(request.getName())
                                && "new agent".equals(request.getResponsibilities())
                                && request.getProvidedFields().contains("name")
                                && request.getProvidedFields().contains("responsibilities")),
                eq(WORKSPACE_ID), eq(USER_ID));
        verify(agentService, never()).offline(anyLong(), anyLong(), anyLong());
        verify(agentService, never()).online(anyLong(), anyLong(), anyLong());
    }

    @Test
    void updateAgentLifecycleActionRequiresReadWriteWorkspaceAccess() {
        McpAccessTokenService.Principal readOnly =
                principal(WorkspaceAccessLevel.READ_ONLY);

        // AC6：与现有数字人 MCP 工具一致，READ_ONLY 成员不可调用
        for (String action : List.of("offline", "online")) {
            BizException exception = assertThrows(BizException.class,
                    () -> call(readOnly, "autowonder.update_agent",
                            Map.of("id", 12L, "lifecycleAction", action)));
            assertEquals("10403", exception.getCode(), action);
        }

        verifyNoInteractions(agentService);
    }

    @Test
    void updateAgentSchemaExposesTheLifecycleActionEnum() {
        Map<String, Object> schema = schemaFor("autowonder.update_agent");
        Map<String, Object> inputProperties = properties(schema);

        // AC8：入参 schema 以枚举约束暴露 lifecycleAction，调用方可发现
        assertSchemaProperty(inputProperties, "lifecycleAction", "string",
                "Optional. Lifecycle action instead of a field update: offline takes an "
                        + "ONLINE worker offline, which is not a delete; online brings an "
                        + "OFFLINE worker back online with its most recent approved version. "
                        + "Mutually exclusive with name, roleCode, roleName, soulMd and "
                        + "agentMd; omit it to keep the partial-update behaviour.");
        assertEquals(List.of("offline", "online"), property(schema, "lifecycleAction").get("enum"));

        // 新参数可选：required 仍只有 workspaceId 与 id，存量调用不受影响
        assertEquals(List.of("workspaceId", "id"), schema.get("required"));
    }

    @Test
    void updateAgentDescriptionExplainsLifecycleSemanticsAndKeepsPartialUpdateWording() {
        String description = toolFor("autowonder.update_agent").getDescription();

        // 描述需说明下线不等于删除、上线恢复最近已批准版本、与控制台一致且不中断执行中的派发
        assertTrue(description.contains("lifecycleAction"), description);
        assertTrue(description.contains("not a delete"), description);
        assertTrue(description.contains("most recent approved version"), description);
        assertTrue(description.contains("console REST"), description);
        assertTrue(description.contains("mutually exclusive"), description);
        assertTrue(description.contains("does not interrupt"), description);

        // 同时保留部分更新语义关键字，避免存量描述断言回归
        assertTrue(description.contains("omit"), description);
        assertTrue(description.contains("keep its current value"), description);
        assertTrue(description.contains("pass null to clear"), description);
    }

    @Test
    void workitemToolDescriptionsExposeEnumsDefaultsSideEffectsAndExamples() {
        // create_workitem: enums, default-to-creator, scheduling side effect, and the
        // "create and assign to a digital worker" example.
        McpToolVO create = toolFor("autowonder.create_workitem");
        assertTrue(create.getDescription().contains("REQ"));
        assertTrue(create.getDescription().contains("BUG"));
        assertTrue(create.getDescription().contains("TASK"));
        assertTrue(create.getDescription().contains("assigned to the creator"));
        assertTrue(create.getDescription().contains("scheduling"));
        assertTrue(create.getDescription().contains("\"assigneeType\":\"AGENT\""));
        assertTrue(create.getDescription().contains("\"assigneeRef\":40013"));
        assertFalse(create.getDescription().contains("\"sdlcId\":40014"));
        // SDLC binding is server-resolved; clients must not be steered to pick one.
        assertTrue(create.getDescription().contains("resolved automatically by the server"));
        assertTrue(create.getDescription().contains("do NOT ask the user to choose an SDLC"));
        // create_workitem ordering: create -> upload -> assign
        assertTrue(create.getDescription().contains("upload_workitem_document"));
        assertTrue(create.getDescription().contains("assign_workitem"));
        assertTrue(create.getDescription().contains("correct order"));
        // create_workitem quality guidance: discourage vague requests, require context
        assertTrue(create.getDescription().contains("clarifying questions"));
        assertTrue(create.getDescription().contains("vague one-line request"));
        assertTrue(create.getDescription().contains("acceptance criteria"));
        assertTrue(create.getDescription().contains("non-goals"));
        assertTrue(create.getDescription().contains("constraints"));
        assertTrue(create.getDescription().contains("AGENT assignment"));

        // assign_workitem: agentId semantics, no status change, and the "reassign an
        // existing workitem to a digital worker" example.
        McpToolVO assign = toolFor("autowonder.assign_workitem");
        assertTrue(assign.getDescription().contains("agentId"));
        assertTrue(assign.getDescription().contains("does NOT change the workitem status node"));
        assertTrue(assign.getDescription().contains("\"id\":10042"));
        assertTrue(assign.getDescription().contains("\"assigneeRef\":40013"));
        assertFalse(assign.getDescription().contains("\"sdlcId\""));
        assertFalse(assign.getDescription().contains("optionally sdlcId"));
        assertTrue(assign.getDescription().contains("resolved automatically by the server"));
        assertTrue(assign.getDescription().contains("Do NOT ask the user to choose an SDLC"));
        assertTrue(assign.getDescription().contains("omit sdlcId"));
        // assign_workitem ordering: documents must be uploaded before assign
        assertTrue(assign.getDescription().contains("upload_workitem_document"));
        assertTrue(assign.getDescription().contains("dispatch scheduling"));

        // list_workitems: business query tool, not for enum discovery, page/size defaults.
        McpToolVO list = toolFor("autowonder.list_workitems");
        assertTrue(list.getDescription().contains("business query"));
        assertTrue(list.getDescription().contains("page=1"));
        assertTrue(list.getDescription().contains("size=20"));

        // list_status_templates: returns templates not SDLC, and no longer steers clients
        // to pick an sdlcId via list_sdlcs.
        McpToolVO templates = toolFor("autowonder.list_status_templates");
        assertTrue(templates.getDescription().contains("NOT SDLC"));
        assertFalse(templates.getDescription().contains("list_sdlcs"));
        assertTrue(templates.getDescription().contains("auto-resolves the correct SDLC"));
    }

    @Test
    void workitemToolPropertiesExposePerFieldDescriptions() {
        // create_workitem per-field descriptions cover the enum and agent/user id split.
        Map<String, Object> createWorkType = property(schemaFor("autowonder.create_workitem"), "workType");
        assertNotNull(createWorkType.get("description"));
        assertTrue(((String) createWorkType.get("description")).contains("REQ"));
        Map<String, Object> createAssigneeRef = property(schemaFor("autowonder.create_workitem"), "assigneeRef");
        assertNotNull(createAssigneeRef.get("description"));
        assertTrue(((String) createAssigneeRef.get("description")).contains("agentId"));
        assertTrue(((String) createAssigneeRef.get("description")).contains("userId"));
        // create_workitem contentMd field: quality guidance for executable workitems
        Map<String, Object> createContentMd = property(schemaFor("autowonder.create_workitem"), "contentMd");
        assertNotNull(createContentMd.get("description"));
        assertTrue(((String) createContentMd.get("description")).contains("background/problem"));
        assertTrue(((String) createContentMd.get("description")).contains("acceptance criteria"));
        assertTrue(((String) createContentMd.get("description")).contains("boundaries"));
        assertTrue(((String) createContentMd.get("description")).contains("ask the user"));

        // assign_workitem assigneeRef spells out agentId/userId.
        Map<String, Object> assignAssigneeRef = property(schemaFor("autowonder.assign_workitem"), "assigneeRef");
        assertNotNull(assignAssigneeRef.get("description"));
        assertTrue(((String) assignAssigneeRef.get("description")).contains("agentId"));
        Map<String, Object> assignSdlcId = property(schemaFor("autowonder.assign_workitem"), "sdlcId");
        assertNotNull(assignSdlcId.get("description"));
        assertTrue(((String) assignSdlcId.get("description")).contains("explicitly specifies"));
        assertTrue(properties(schemaFor("autowonder.create_workitem")).containsKey("sdlcId"));

        // list_workitems and list_status_templates expose per-field descriptions.
        assertNotNull(property(schemaFor("autowonder.list_workitems"), "workType").get("description"));
        Map<String, Object> tmplWorkType = property(schemaFor("autowonder.list_status_templates"), "workType");
        assertNotNull(tmplWorkType.get("description"));
        assertTrue(((String) tmplWorkType.get("description")).contains("REQ"));
    }

    private Map<String, Object> schemaFor(String name) {
        return toolFor(name).getInputSchema();
    }

    private Map<String, Object> outputSchemaFor(String name) {
        return toolFor(name).getOutputSchema();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> itemsSchema(Map<String, Object> listSchema) {
        return (Map<String, Object>) property(listSchema, "items").get("items");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> properties(Map<String, Object> schema) {
        return (Map<String, Object>) schema.get("properties");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> property(Map<String, Object> schema, String name) {
        return (Map<String, Object>) properties(schema).get(name);
    }

    @SuppressWarnings("unchecked")
    private void assertSchemaProperty(Map<String, Object> properties, String name, String type,
            String description) {
        Map<String, Object> schemaProperty = (Map<String, Object>) properties.get(name);
        assertEquals(type, schemaProperty.get("type"), name);
        assertEquals(description, schemaProperty.get("description"), name);
    }

    private void assertListOutputSchema(Map<String, Object> schema, String... itemPropertyNames) {
        assertListOutputSchema(schema);
        Map<String, Object> itemProperties = properties(itemSchema(property(schema, "items")));
        assertTrue(itemProperties.keySet().containsAll(List.of(itemPropertyNames)));
    }

    private void assertListOutputSchema(Map<String, Object> schema) {
        assertEquals("object", schema.get("type"));
        Map<String, Object> items = property(schema, "items");
        assertEquals("array", items.get("type"));
        assertTrue(items.containsKey("items"));
    }

    @SuppressWarnings("unchecked")
    private void assertNullableField(Map<String, Object> properties, String name, String type) {
        Map<String, Object> property = (Map<String, Object>) properties.get(name);
        assertEquals(List.of(type, "null"), property.get("type"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> itemSchema(Map<String, Object> arrayProperty) {
        return (Map<String, Object>) arrayProperty.get("items");
    }

    private McpToolVO toolFor(String name) {
        return service.listTools().stream()
                .filter(candidate -> name.equals(candidate.getName()))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void personalTokenSeesTheWholeCatalogBecausePermissionIsCheckedPerCall() {
        assertEquals(service.listTools().size(),
                service.listTools(McpAccessTokenService.Principal.personal(USER_ID, 1L)).size());
    }

    @Test
    void personalTokenReachesEveryWorkspaceTheOwnerBelongsTo() {
        WorkitemVO first = new WorkitemVO();
        WorkitemVO second = new WorkitemVO();
        when(workspaceService.activeAccessLevel(WORKSPACE_ID, USER_ID))
                .thenReturn(WorkspaceAccessLevel.READ_ONLY);
        when(workspaceService.activeAccessLevel(200L, USER_ID))
                .thenReturn(WorkspaceAccessLevel.READ_WRITE);
        when(workitemService.get(11L)).thenReturn(first);
        when(workitemService.get(22L)).thenReturn(second);
        McpAccessTokenService.Principal personal =
                McpAccessTokenService.Principal.personal(USER_ID, 1L);

        assertSame(first, service.call(personal, "autowonder.get_workitem",
                Map.of("workspaceId", WORKSPACE_ID, "id", 11L)));
        assertSame(second, service.call(personal, "autowonder.get_workitem",
                Map.of("workspaceId", 200L, "id", 22L)));
    }

    @Test
    void personalTokenMustPassWorkspaceIdForWorkspaceScopedTools() {
        BizException thrown = assertThrows(BizException.class,
                () -> service.call(McpAccessTokenService.Principal.personal(USER_ID, 1L),
                        "autowonder.get_workitem", Map.of("id", 11L)));

        assertEquals("10001", thrown.getCode());
        verifyNoInteractions(workitemService);
    }

    @Test
    void personalTokenRejectsNonPositiveWorkspaceId() {
        McpAccessTokenService.Principal personal =
                McpAccessTokenService.Principal.personal(USER_ID, 1L);

        for (Object invalid : List.of(0L, -1L)) {
            BizException thrown = assertThrows(BizException.class,
                    () -> service.call(personal, "autowonder.get_workitem",
                            Map.of("workspaceId", invalid, "id", 11L)));
            assertEquals("10001", thrown.getCode());
        }
        verifyNoInteractions(workitemService);
    }

    @Test
    void personalTokenCannotReachAnWorkspaceTheOwnerLeft() {
        when(workspaceService.activeAccessLevel(200L, USER_ID))
                .thenThrow(new BizException(ErrorCode.WORKSPACE_NOT_MEMBER));

        BizException thrown = assertThrows(BizException.class,
                () -> service.call(McpAccessTokenService.Principal.personal(USER_ID, 1L),
                        "autowonder.get_workitem", Map.of("workspaceId", 200L, "id", 11L)));

        assertEquals("11001", thrown.getCode());
        verifyNoInteractions(workitemService);
    }

    @Test
    void readOnlyMembershipReadsButCannotWriteInTheTargetWorkspace() {
        WorkitemVO workitem = new WorkitemVO();
        when(workspaceService.activeAccessLevel(WORKSPACE_ID, USER_ID))
                .thenReturn(WorkspaceAccessLevel.READ_ONLY);
        when(workitemService.get(11L)).thenReturn(workitem);
        McpAccessTokenService.Principal personal =
                McpAccessTokenService.Principal.personal(USER_ID, 1L);

        assertSame(workitem, service.call(personal, "autowonder.get_workitem",
                Map.of("workspaceId", WORKSPACE_ID, "id", 11L)));

        BizException thrown = assertThrows(BizException.class,
                () -> service.call(personal, "autowonder.delete_workitem",
                        Map.of("workspaceId", WORKSPACE_ID, "id", 11L)));
        assertEquals("10403", thrown.getCode());
        verify(workitemService, never()).delete(anyLong(), anyLong(), anyLong());
    }

    @Test
    void membershipIsResolvedOnEveryCallSoDowngradesTakeEffectImmediately() {
        when(workspaceService.activeAccessLevel(WORKSPACE_ID, USER_ID))
                .thenReturn(WorkspaceAccessLevel.READ_WRITE, WorkspaceAccessLevel.READ_ONLY);
        McpAccessTokenService.Principal personal =
                McpAccessTokenService.Principal.personal(USER_ID, 1L);

        service.call(personal, "autowonder.delete_workitem", Map.of("workspaceId", WORKSPACE_ID, "id", 11L));
        assertThrows(BizException.class,
                () -> service.call(personal, "autowonder.delete_workitem",
                        Map.of("workspaceId", WORKSPACE_ID, "id", 11L)));

        verify(workspaceService, times(2)).activeAccessLevel(WORKSPACE_ID, USER_ID);
    }

    @Test
    void taskScopedTokenAcceptsOmittedOrMatchingWorkspaceIdButRejectsAnother() {
        WorkitemVO workitem = new WorkitemVO();
        when(workitemService.get(11L)).thenReturn(workitem);
        McpAccessTokenService.Principal scoped = scopedPrincipal(WorkspaceAccessLevel.READ_WRITE);

        assertSame(workitem, service.call(scoped, "autowonder.get_workitem", Map.of("id", 11L)));
        assertSame(workitem, service.call(scoped, "autowonder.get_workitem",
                Map.of("workspaceId", WORKSPACE_ID, "id", 11L)));

        BizException thrown = assertThrows(BizException.class,
                () -> service.call(scoped, "autowonder.get_workitem",
                        Map.of("workspaceId", 200L, "id", 11L)));
        assertEquals("10403", thrown.getCode());
        // 跨空间在解析成员身份之前就被拒，不会拿别的空间去问成员表。
        verify(workspaceService, never()).activeAccessLevel(200L, USER_ID);
    }

    /** 会话令牌代表 Owner 本人，Owner 被降级后不能等 24 小时 TTL 到期才生效。 */
    @Test
    void conversationTokenLosesWriteAccessAsSoonAsTheOwnerIsDowngraded() {
        when(workspaceService.activeAccessLevel(WORKSPACE_ID, USER_ID))
                .thenReturn(WorkspaceAccessLevel.READ_WRITE, WorkspaceAccessLevel.READ_ONLY);
        McpAccessTokenService.Principal conversation = new McpAccessTokenService.Principal(
                WORKSPACE_ID, USER_ID, 1L, WorkspaceAccessLevel.READ_WRITE,
                McpAccessTokenService.CredentialType.CONVERSATION);

        service.call(conversation, "autowonder.delete_workitem",
                Map.of("workspaceId", WORKSPACE_ID, "id", 11L));
        BizException thrown = assertThrows(BizException.class,
                () -> service.call(conversation, "autowonder.delete_workitem",
                        Map.of("workspaceId", WORKSPACE_ID, "id", 11L)));

        assertEquals("10403", thrown.getCode());
        verify(workspaceService, times(2)).activeAccessLevel(WORKSPACE_ID, USER_ID);
        verify(workitemService, times(1)).delete(anyLong(), anyLong(), anyLong());
    }

    /** Owner 被移出工作空间后，已签出的会话令牌必须立刻整体失效。 */
    @Test
    void conversationTokenIsRejectedAfterTheOwnerLeavesTheWorkspace() {
        when(workspaceService.activeAccessLevel(WORKSPACE_ID, USER_ID))
                .thenThrow(new BizException(ErrorCode.WORKSPACE_NOT_MEMBER));
        McpAccessTokenService.Principal conversation = new McpAccessTokenService.Principal(
                WORKSPACE_ID, USER_ID, 1L, WorkspaceAccessLevel.ADMIN,
                McpAccessTokenService.CredentialType.CONVERSATION);

        BizException thrown = assertThrows(BizException.class,
                () -> service.call(conversation, "autowonder.get_workitem",
                        Map.of("workspaceId", WORKSPACE_ID, "id", 11L)));

        assertEquals("11001", thrown.getCode());
        verifyNoInteractions(workitemService);
    }

    /** 令牌里的级别只是上限，Owner 在册身份更低时以低的为准，不能靠令牌抬权。 */
    @Test
    void conversationTokenLevelIsCappedByTheOwnerLiveMembership() {
        WorkitemVO workitem = new WorkitemVO();
        when(workitemService.get(11L)).thenReturn(workitem);
        McpAccessTokenService.Principal conversation = conversationPrincipal(
                WorkspaceAccessLevel.ADMIN, WorkspaceAccessLevel.READ_ONLY);

        assertSame(workitem, service.call(conversation, "autowonder.get_workitem",
                Map.of("workspaceId", WORKSPACE_ID, "id", 11L)));

        BizException thrown = assertThrows(BizException.class,
                () -> service.call(conversation, "autowonder.delete_workitem",
                        Map.of("workspaceId", WORKSPACE_ID, "id", 11L)));
        assertEquals("10403", thrown.getCode());
        verify(workitemService, never()).delete(anyLong(), anyLong(), anyLong());
    }

    /** 派发令牌仍然完全钉死在自己的工作空间，不做成员身份解析。 */
    @Test
    void dispatchTokenStaysPinnedWithoutResolvingMembership() {
        WorkitemVO workitem = new WorkitemVO();
        when(workitemService.get(11L)).thenReturn(workitem);

        assertSame(workitem, service.call(dispatchPrincipal(-321L),
                "autowonder.get_workitem", Map.of("workspaceId", WORKSPACE_ID, "id", 11L)));

        verify(workspaceService, never()).activeAccessLevel(anyLong(), anyLong());
    }

    @Test
    void dispatchTokenCannotCrossIntoAnotherWorkspace() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal(-321L);

        BizException thrown = assertThrows(BizException.class,
                () -> service.call(dispatchPrincipal, "autowonder.get_workitem",
                        Map.of("workspaceId", 200L, "id", 11L)));

        assertEquals("10403", thrown.getCode());
        verifyNoInteractions(workspaceService);
        verifyNoInteractions(workitemService);
    }

    @Test
    void ambientWorkspaceContextIsRestoredAfterEveryCall() {
        AutoWonderContext ambient = AutoWonderContext.get();
        ambient.setCurrentWorkspaceId(900L);
        ambient.setWorkspaceAccessLevel(WorkspaceAccessLevel.READ_ONLY);
        when(workspaceService.activeAccessLevel(WORKSPACE_ID, USER_ID)).thenReturn(WorkspaceAccessLevel.ADMIN);
        try {
            service.call(McpAccessTokenService.Principal.personal(USER_ID, 1L),
                    "autowonder.delete_workitem", Map.of("workspaceId", WORKSPACE_ID, "id", 11L));

            assertEquals(900L, ambient.getCurrentWorkspaceId());
            assertEquals(WorkspaceAccessLevel.READ_ONLY, ambient.getWorkspaceAccessLevel());
        } finally {
            AutoWonderContext.destroy();
        }
    }

    @Test
    void workspaceScopedToolsAllRequireWorkspaceIdWhileGlobalToolsDoNot() {
        Set<String> globalTools = Set.of(
                "autowonder.list_projects",
                "autowonder.inspect_skill_package",
                "autowonder.list_platform_skills");

        for (McpToolVO tool : service.listTools()) {
            Object required = tool.getInputSchema().get("required");
            boolean requiresWorkspaceId = required instanceof List<?> names
                    && names.contains("workspaceId");
            if (globalTools.contains(tool.getName())) {
                assertFalse(requiresWorkspaceId, tool.getName() + " must not require workspaceId");
                assertFalse(properties(tool.getInputSchema()).containsKey("workspaceId"),
                        tool.getName() + " must not declare workspaceId");
            } else {
                assertTrue(requiresWorkspaceId, tool.getName() + " must require workspaceId");
                assertEquals("integer",
                        ((Map<?, ?>) properties(tool.getInputSchema()).get("workspaceId")).get("type"),
                        tool.getName() + " workspaceId must be an integer");
            }
        }
    }

    @Test
    void personalTokenQueriesWorkspacesOnceAndGeneratesDifferentDescriptions() {
        WorkspaceVO orgA = new WorkspaceVO();
        orgA.setId(10002L);
        orgA.setName("AutoWonder自迭代");
        orgA.setAccessLevel(WorkspaceAccessLevel.READ_ONLY);

        WorkspaceVO orgB = new WorkspaceVO();
        orgB.setId(10003L);
        orgB.setName("AutoWonder产研项目组");
        orgB.setAccessLevel(WorkspaceAccessLevel.READ_WRITE);

        when(workspaceService.listByUserWithAccess(USER_ID)).thenReturn(List.of(orgA, orgB));

        McpAccessTokenService.Principal personal = McpAccessTokenService.Principal.personal(USER_ID, 1L);
        List<McpToolVO> tools = service.listTools(personal);

        verify(workspaceService, times(1)).listByUserWithAccess(USER_ID);

        String expectedRead = "Workspace: 10002=AutoWonder自迭代;10003=AutoWonder产研项目组";
        String expectedWrite = "Workspace: 10003=AutoWonder产研项目组";

        for (McpToolVO tool : tools) {
            Map<String, Object> props = properties(tool.getInputSchema());
            if (props != null && props.containsKey("workspaceId")) {
                Map<String, Object> workspaceIdProp = (Map<String, Object>) props.get("workspaceId");
                String desc = (String) workspaceIdProp.get("description");
                String toolName = tool.getName();
                McpToolService toolServiceSpy = service;
                // determine if this is a read-only or write tool
                // read-only tools get the full (read) description, write tools get the write description
                assertNotNull(desc, toolName + " must have an workspaceId description");
                assertTrue(desc.startsWith("Workspace:"), toolName + " description must start with 'Workspace:'");
                assertTrue(desc.equals(expectedRead) || desc.equals(expectedWrite),
                        toolName + " has unexpected description: " + desc);
            }
        }

        // verify list_projects (global) has no workspaceId
        McpToolVO listProjects = tools.stream()
                .filter(t -> "autowonder.list_projects".equals(t.getName()))
                .findFirst().orElseThrow();
        assertFalse(properties(listProjects.getInputSchema()).containsKey("workspaceId"),
                "list_projects must not have workspaceId");
    }

    @Test
    void taskScopedTokenShowsOnlyBoundWorkspace() {
        WorkspaceVO scoped = new WorkspaceVO();
        scoped.setId(WORKSPACE_ID);
        scoped.setName("TestWorkspace");
        when(workspaceService.getCurrent(WORKSPACE_ID)).thenReturn(scoped);

        McpAccessTokenService.Principal conversation = scopedPrincipal(WorkspaceAccessLevel.READ_WRITE);
        List<McpToolVO> tools = service.listTools(conversation);

        verify(workspaceService, never()).listByUserWithAccess(anyLong());

        String expected = "Workspace: " + WORKSPACE_ID + "=TestWorkspace";
        for (McpToolVO tool : tools) {
            Map<String, Object> props = properties(tool.getInputSchema());
            if (props != null && props.containsKey("workspaceId")) {
                Map<String, Object> workspaceIdProp = (Map<String, Object>) props.get("workspaceId");
                assertEquals(expected, workspaceIdProp.get("description"),
                        tool.getName() + " must show only the bound workspace");
            }
        }
    }

    @Test
    void globalToolsUnchangedAfterDescriptionInjection() {
        when(workspaceService.listByUserWithAccess(USER_ID)).thenReturn(List.of());

        McpAccessTokenService.Principal personal = McpAccessTokenService.Principal.personal(USER_ID, 1L);
        List<McpToolVO> tools = service.listTools(personal);

        Set<String> globalToolNames = Set.of(
                "autowonder.list_projects",
                "autowonder.inspect_skill_package",
                "autowonder.list_platform_skills");

        for (McpToolVO tool : tools) {
            if (globalToolNames.contains(tool.getName())) {
                Map<String, Object> props = properties(tool.getInputSchema());
                assertFalse(props.containsKey("workspaceId"),
                        tool.getName() + " must not gain workspaceId after injection");
            }
        }
    }

    @Test
    void workspaceIdRemainsIntegerAndRequiredOrderUnchanged() {
        WorkspaceVO workspace = new WorkspaceVO();
        workspace.setId(100L);
        workspace.setName("Workspace");
        workspace.setAccessLevel(WorkspaceAccessLevel.READ_WRITE);
        when(workspaceService.listByUserWithAccess(USER_ID)).thenReturn(List.of(workspace));

        McpAccessTokenService.Principal personal = McpAccessTokenService.Principal.personal(USER_ID, 1L);
        List<McpToolVO> tools = service.listTools(personal);

        for (McpToolVO tool : tools) {
            Map<String, Object> props = properties(tool.getInputSchema());
            if (props != null && props.containsKey("workspaceId")) {
                Map<String, Object> workspaceIdProp = (Map<String, Object>) props.get("workspaceId");
                assertEquals("integer", workspaceIdProp.get("type"),
                        tool.getName() + " workspaceId must remain integer type");

                @SuppressWarnings("unchecked")
                List<String> required = (List<String>) tool.getInputSchema().get("required");
                assertEquals("workspaceId", required.get(0),
                        tool.getName() + " workspaceId must be first in required list");
            }
        }
    }

    @Test
    void emptyWorkspacesProducesNoDescriptionChangeForPersonalToken() {
        when(workspaceService.listByUserWithAccess(USER_ID)).thenReturn(List.of());

        McpAccessTokenService.Principal personal = McpAccessTokenService.Principal.personal(USER_ID, 1L);
        List<McpToolVO> tools = service.listTools(personal);

        // When there are no workspaces, the original static description should remain
        for (McpToolVO tool : tools) {
            Map<String, Object> props = properties(tool.getInputSchema());
            if (props != null && props.containsKey("workspaceId")) {
                Map<String, Object> workspaceIdProp = (Map<String, Object>) props.get("workspaceId");
                String desc = (String) workspaceIdProp.get("description");
                // Should still have the original static description
                assertTrue(desc.contains("autowonder.list_projects"),
                        tool.getName() + " should retain original description when no workspaces");
            }
        }
    }

    @Test
    void compactDescriptionSortsByWorkspaceIdAscending() {
        WorkspaceVO org3 = new WorkspaceVO();
        org3.setId(300L);
        org3.setName("Zeta");
        org3.setAccessLevel(WorkspaceAccessLevel.READ_WRITE);

        WorkspaceVO org1 = new WorkspaceVO();
        org1.setId(100L);
        org1.setName("Alpha");
        org1.setAccessLevel(WorkspaceAccessLevel.READ_WRITE);

        WorkspaceVO org2 = new WorkspaceVO();
        org2.setId(200L);
        org2.setName("中文工作空间");
        org2.setAccessLevel(WorkspaceAccessLevel.READ_ONLY);

        when(workspaceService.listByUserWithAccess(USER_ID)).thenReturn(List.of(org3, org1, org2));

        McpAccessTokenService.Principal personal = McpAccessTokenService.Principal.personal(USER_ID, 1L);
        List<McpToolVO> tools = service.listTools(personal);

        // Find a read-only tool to check the full description
        McpToolVO readTool = tools.stream()
                .filter(t -> "autowonder.list_workitems".equals(t.getName()))
                .findFirst().orElseThrow();
        Map<String, Object> props = properties(readTool.getInputSchema());
        Map<String, Object> workspaceIdProp = (Map<String, Object>) props.get("workspaceId");
        String desc = (String) workspaceIdProp.get("description");

        // Should be sorted by workspaceId ascending: 100, 200, 300
        assertEquals("Workspace: 100=Alpha;200=中文工作空间;300=Zeta", desc);

        // Find a write tool to check the write-only description
        McpToolVO writeTool = tools.stream()
                .filter(t -> "autowonder.create_workitem".equals(t.getName()))
                .findFirst().orElseThrow();
        props = properties(writeTool.getInputSchema());
        workspaceIdProp = (Map<String, Object>) props.get("workspaceId");
        desc = (String) workspaceIdProp.get("description");

        // Only READ_WRITE and ADMIN workspaces: 100(READ_WRITE) and 300(READ_WRITE)
        assertEquals("Workspace: 100=Alpha;300=Zeta", desc);
    }

    @Test
    void executorToolsAreRegisteredWithPageParityInputSchemas() {
        assertEquals(List.of("workspaceId"), schemaFor("autowonder.list_executors").get("required"));
        assertEquals(List.of("workspaceId"),
                schemaFor("autowonder.list_executor_client_kinds").get("required"));
        for (String tool : List.of("autowonder.get_executor", "autowonder.get_executor_token",
                "autowonder.delete_executor", "autowonder.build_executor_launch_command",
                "autowonder.get_executor_launch_config")) {
            assertEquals(List.of("workspaceId", "id"), schemaFor(tool).get("required"), tool);
        }
        assertEquals(List.of("workspaceId", "id", "version"),
                schemaFor("autowonder.update_executor_launch_config").get("required"));
        assertEquals(List.of("workspaceId", "clientKind"),
                schemaFor("autowonder.get_executor_launch_options").get("required"));
        assertEquals(List.of("workspaceId", "agentId", "name", "clientKind"),
                schemaFor("autowonder.create_executor").get("required"));

        List<String> clientKinds = List.of("QODER_CLI", "QODER_CN_CLI");
        assertEquals(clientKinds, enumValues("autowonder.create_executor", "clientKind"));
        assertEquals(clientKinds, enumValues("autowonder.get_executor_launch_options", "clientKind"));
        List<String> memoryModes = List.of("platform", "provider-local", "none");
        for (String tool : List.of("autowonder.create_executor",
                "autowonder.update_executor_launch_config")) {
            assertEquals(memoryModes, enumValues(tool, "memoryMode"), tool);
            assertEquals(List.of("max", "xhigh", "high", "medium", "low", "none"),
                    enumValues(tool, "reasoningEffort"), tool);
            assertEquals(List.of("1000000", "400000", "260000"),
                    enumValues(tool, "contextWindow"), tool);
        }
        // 生成命令只保留输出格式选项：启动值一律来自数据库配置，不接受入参覆盖
        Map<String, Object> buildProperties = properties(schemaFor("autowonder.build_executor_launch_command"));
        assertEquals(Set.of("workspaceId", "id", "os", "debug", "shell"), buildProperties.keySet());
        assertEquals(List.of("posix", "windows"),
                enumValues("autowonder.build_executor_launch_command", "os"));
        assertEquals(List.of("bash", "powershell"),
                enumValues("autowonder.build_executor_launch_command", "shell"));
    }

    @Test
    void executorModelDescriptionPublishesTheIdsRedisCurrentlyHolds() {
        when(providerModelCatalogService.read("qoder")).thenReturn(new ProviderModelCatalogVO("qoder",
                List.of(new ProviderModelCatalogItemVO("auto", "Auto (default)"),
                        new ProviderModelCatalogItemVO("ultimate", "Ultimate")), new Date()));
        when(providerModelCatalogService.read("qodercn")).thenReturn(new ProviderModelCatalogVO("qodercn",
                List.of(new ProviderModelCatalogItemVO("qmodel_latest", "Qwen3.7-Max")), new Date()));

        for (String tool : List.of("autowonder.create_executor", "autowonder.update_executor_launch_config")) {
            String description = modelDescription(tool, principal);
            assertTrue(description.contains("Current ids — qoder: auto, ultimate; qodercn: qmodel_latest."),
                    tool + " must publish the live ids: " + description);
            assertTrue(description.contains("assembled server-side"), tool);
            assertFalse(description.contains("launch dialog pre-fills"), tool);
        }
    }

    @Test
    void executorModelDescriptionListsOnlyTheProvidersRedisActuallyHolds() {
        when(providerModelCatalogService.read("qoder")).thenReturn(new ProviderModelCatalogVO("qoder",
                List.of(new ProviderModelCatalogItemVO("auto", "Auto (default)")), new Date()));
        when(providerModelCatalogService.read("qodercn")).thenReturn(new ProviderModelCatalogVO("qodercn",
                List.of(), null));

        String description = modelDescription("autowonder.create_executor", principal);

        assertTrue(description.contains("Current ids — qoder: auto."), description);
        assertFalse(description.contains("qodercn"), description);
    }

    @Test
    void executorModelDescriptionFallsBackToTheStaticWordingWithoutALiveCatalog() {
        when(providerModelCatalogService.read("qoder")).thenReturn(new ProviderModelCatalogVO("qoder",
                List.of(), null));

        String description = modelDescription("autowonder.create_executor", principal);

        assertTrue(description.contains("assembled server-side"), description);
        assertTrue(description.contains("never hardcode one"), description);
        assertTrue(description.contains("autowonder.get_executor_launch_options"), description);
        assertFalse(description.contains("Current ids"), description);
        assertFalse(description.contains("launch dialog pre-fills"), description);
    }

    @Test
    void executorModelDescriptionSurvivesAMissingOptionsBeanAndAnUnavailableCatalogStore() {
        String withoutBean;
        ReflectionTestUtils.setField(service, "executorLaunchOptionsService", null);
        try {
            withoutBean = modelDescription("autowonder.update_executor_launch_config", principal);
        } finally {
            ReflectionTestUtils.setField(service, "executorLaunchOptionsService",
                    new ExecutorLaunchOptionsService(providerModelCatalogService));
        }
        assertTrue(withoutBean.contains("assembled server-side"), withoutBean);
        assertFalse(withoutBean.contains("Current ids"), withoutBean);

        when(providerModelCatalogService.read(anyString())).thenThrow(new IllegalStateException("redis down"));

        String withFailingStore = modelDescription("autowonder.update_executor_launch_config", principal);
        assertTrue(withFailingStore.contains("assembled server-side"), withFailingStore);
        assertFalse(withFailingStore.contains("Current ids"), withFailingStore);
    }

    @Test
    void thePrincipalLessCatalogNeverReadsRedisForTheModelDescription() {
        when(providerModelCatalogService.read("qoder")).thenReturn(new ProviderModelCatalogVO("qoder",
                List.of(new ProviderModelCatalogItemVO("auto", "Auto (default)")), new Date()));

        List<McpToolVO> tools = service.listTools();

        for (McpToolVO tool : tools) {
            Map<String, Object> props = properties(tool.getInputSchema());
            if (props != null && props.containsKey("model")) {
                assertFalse(String.valueOf(((Map<?, ?>) props.get("model")).get("description"))
                        .contains("Current ids"), tool.getName() + " must not read Redis");
            }
        }
        verify(providerModelCatalogService, never()).read(anyString());
    }

    private String modelDescription(String tool, McpAccessTokenService.Principal caller) {
        McpToolVO found = service.listTools(caller).stream()
                .filter(candidate -> tool.equals(candidate.getName()))
                .findFirst().orElseThrow();
        return (String) property(found.getInputSchema(), "model").get("description");
    }

    @Test
    void executorOutputSchemasUseObjectEnvelopesAndDeclareNullableFields() {
        assertListOutputSchema(outputSchemaFor("autowonder.list_executors"),
                "id", "agentId", "name", "clientKind", "status", "lastHeartbeat", "gmtCreate");
        Map<String, Object> executorItem = properties(itemSchema(
                property(outputSchemaFor("autowonder.list_executors"), "items")));
        assertNullableField(executorItem, "agentName", "string");
        // 历史执行器的 clientKind 可为 null，Schema 必须允许 null，否则严格校验客户端拒绝整个列表/详情响应
        assertNullableField(executorItem, "clientKind", "string");
        assertNullableField(executorItem, "lastConnectIp", "string");
        assertNullableField(executorItem, "lastHeartbeat", "string");
        assertNullableField(executorItem, "gmtCreate", "string");
        assertNullableField(properties(outputSchemaFor("autowonder.get_executor")), "clientKind", "string");

        assertListOutputSchema(outputSchemaFor("autowonder.list_executor_client_kinds"), "value", "label");

        Map<String, Object> optionsSchema = outputSchemaFor("autowonder.get_executor_launch_options");
        Map<String, Object> options = properties(optionsSchema);
        assertTrue(options.keySet().containsAll(List.of("clientKind", "provider", "models", "reasoningEfforts",
                "contextWindows", "memoryModes", "defaultModel", "defaultCreateModel", "defaultReasoningEffort",
                "defaultContextWindow", "defaultMemoryMode")));
        assertNullableField(options, "modelCatalogLastSuccessfulAt", "string");
        Map<String, Object> models = property(optionsSchema, "models");
        assertEquals("array", models.get("type"));
        assertTrue(properties(itemSchema(models)).keySet().containsAll(List.of("value", "label")));

        assertTrue(properties(outputSchemaFor("autowonder.create_executor")).keySet().containsAll(
                List.of("id", "agentId", "name", "token", "clientKind", "memoryMode", "model",
                        "reasoningEffort", "contextWindow")));
        assertTrue(properties(outputSchemaFor("autowonder.get_executor_token")).keySet()
                .containsAll(List.of("id", "token")));
        assertTrue(properties(outputSchemaFor("autowonder.delete_executor")).containsKey("deleted"));

        // 启动配置的读与写共用一个输出契约：启动值可为 null（从未配置的执行器如实报告），version 是必填的乐观锁
        for (String tool : List.of("autowonder.get_executor_launch_config",
                "autowonder.update_executor_launch_config")) {
            Map<String, Object> schema = outputSchemaFor(tool);
            Map<String, Object> config = properties(schema);
            assertNullableField(config, "model", "string");
            assertNullableField(config, "reasoningEffort", "string");
            assertNullableField(config, "contextWindow", "string");
            assertNullableField(config, "memoryMode", "string");
            assertEquals("integer", property(schema, "version").get("type"), tool);
            assertEquals(List.of("version"), schema.get("required"), tool);
        }

        Map<String, Object> command = properties(outputSchemaFor("autowonder.build_executor_launch_command"));
        assertTrue(command.keySet().containsAll(List.of("executorId", "clientKind", "provider", "memoryMode",
                "wsUrl", "runtimeVersion", "os", "debug", "command")));
        assertNullableField(command, "model", "string");
        assertNullableField(command, "reasoningEffort", "string");
        assertNullableField(command, "contextWindow", "string");
        assertNullableField(command, "shell", "string");
        assertNullableField(command, "logFileName", "string");
    }

    @Test
    void listExecutorsDelegatesWithAndWithoutTheAgentFilter() {
        ExecutorVO every = executor(9L, 5L, "QODER_CLI");
        ExecutorVO scoped = executor(10L, 5L, "QODER_CN_CLI");
        when(executorService.listAll(WORKSPACE_ID, null)).thenReturn(List.of(every));
        when(executorService.listByAgent(5L, WORKSPACE_ID)).thenReturn(List.of(scoped));

        assertEquals(List.of(every), call(principal, "autowonder.list_executors", Map.of()));
        assertEquals(List.of(scoped),
                call(principal, "autowonder.list_executors", Map.of("agentId", 5L)));

        verify(executorService).listAll(WORKSPACE_ID, null);
        verify(executorService).listByAgent(5L, WORKSPACE_ID);
    }

    @Test
    void getExecutorTokenAndDeleteDelegateToExecutorService() {
        McpAccessTokenService.Principal admin = principal(WorkspaceAccessLevel.ADMIN);
        ExecutorVO executor = executor(9L, 5L, "QODER_CLI");
        when(executorService.getDetail(9L, WORKSPACE_ID)).thenReturn(executor);
        when(executorService.getToken(9L, WORKSPACE_ID)).thenReturn("awexec_plain");

        assertSame(executor, call(admin, "autowonder.get_executor", Map.of("id", 9L)));
        assertEquals(Map.of("id", 9L, "token", "awexec_plain"),
                call(admin, "autowonder.get_executor_token", Map.of("id", 9L)));
        assertEquals(Map.of("deleted", true),
                call(admin, "autowonder.delete_executor", Map.of("id", 9L)));

        verify(executorService).delete(9L, WORKSPACE_ID, USER_ID);
    }

    @Test
    void getExecutorTokenSurfacesAnUnreadableCredential() {
        McpAccessTokenService.Principal admin = principal(WorkspaceAccessLevel.ADMIN);
        when(executorService.getToken(9L, WORKSPACE_ID))
                .thenThrow(new BizException(ErrorCode.EXECUTOR_TOKEN_NOT_RETRIEVABLE));

        BizException exception = assertThrows(BizException.class,
                () -> call(admin, "autowonder.get_executor_token", Map.of("id", 9L)));

        assertEquals("17004", exception.getCode());
    }

    @Test
    @SuppressWarnings("unchecked")
    void listExecutorClientKindsOffersOnlyTheCreatableQoderFamily() {
        List<SelectOptionVO> kinds =
                (List<SelectOptionVO>) call(principal, "autowonder.list_executor_client_kinds", Map.of());

        assertEquals(List.of("QODER_CLI", "QODER_CN_CLI"),
                kinds.stream().map(SelectOptionVO::getValue).toList());
        assertEquals(List.of("Qoder CLI", "Qoder CLI CN"),
                kinds.stream().map(SelectOptionVO::getLabel).toList());
        verifyNoInteractions(executorService);
    }

    @Test
    void getExecutorLaunchOptionsAssemblesValuesServerSide() {
        Date catalogAt = new Date();
        when(providerModelCatalogService.read("qodercn")).thenReturn(new ProviderModelCatalogVO("qodercn",
                List.of(new ProviderModelCatalogItemVO("qmodel_latest", "Qwen3.7-Max"),
                        new ProviderModelCatalogItemVO("auto", "Auto"),
                        new ProviderModelCatalogItemVO("lite", null)),
                catalogAt));

        ExecutorLaunchOptionsVO options = (ExecutorLaunchOptionsVO) call(principal,
                "autowonder.get_executor_launch_options", Map.of("clientKind", "qoder_cn_cli"));

        assertEquals("QODER_CN_CLI", options.getClientKind());
        assertEquals("qodercn", options.getProvider());
        assertEquals(List.of("qmodel_latest", "auto", "lite"),
                options.getModels().stream().map(SelectOptionVO::getValue).toList());
        assertEquals(List.of("Qwen3.7-Max", "Auto", "lite"),
                options.getModels().stream().map(SelectOptionVO::getLabel).toList());
        assertEquals("qmodel_latest", options.getDefaultModel());
        assertEquals("auto", options.getDefaultCreateModel());
        assertEquals("medium", options.getDefaultReasoningEffort());
        assertEquals("260000", options.getDefaultContextWindow());
        assertEquals("platform", options.getDefaultMemoryMode());
        assertEquals(catalogAt, options.getModelCatalogLastSuccessfulAt());
    }

    @Test
    void getExecutorLaunchOptionsRejectsClientKindsThePageHides() {
        BizException exception = assertThrows(BizException.class, () -> call(principal,
                "autowonder.get_executor_launch_options", Map.of("clientKind", "CLAUDE_CODE")));

        assertEquals("27003", exception.getCode());
        assertTrue(exception.getMessage().contains("clientKind 仅支持 QODER_CLI/QODER_CN_CLI"));
        verifyNoInteractions(providerModelCatalogService);
    }

    @Test
    void createExecutorForwardsEveryLaunchValueToThePersistingService() {
        McpAccessTokenService.Principal admin = principal(WorkspaceAccessLevel.ADMIN);
        when(executorService.create(eq(5L), any(CreateExecutorRequest.class), eq(WORKSPACE_ID), eq(USER_ID)))
                .thenReturn(issued(9L, 5L, "dev-machine-01", "awexec_plain", "QODER_CN_CLI",
                        "none", "lite", "low", "400000"));

        CreatedExecutorVO created = (CreatedExecutorVO) call(admin, "autowonder.create_executor",
                Map.of("agentId", 5L, "name", "dev-machine-01", "clientKind", "qoder_cn_cli",
                        "memoryMode", "none", "model", "lite", "reasoningEffort", "low",
                        "contextWindow", "400000"));

        assertEquals(9L, created.getId());
        assertEquals(5L, created.getAgentId());
        assertEquals("awexec_plain", created.getToken());
        assertEquals("QODER_CN_CLI", created.getClientKind());
        // 响应回显数据库里已保存的启动值，而不是本次调用内部临时算出来的值
        assertEquals("none", created.getMemoryMode());
        assertEquals("lite", created.getModel());
        assertEquals("low", created.getReasoningEffort());
        assertEquals("400000", created.getContextWindow());

        ArgumentCaptor<CreateExecutorRequest> captor = ArgumentCaptor.forClass(CreateExecutorRequest.class);
        verify(executorService).create(eq(5L), captor.capture(), eq(WORKSPACE_ID), eq(USER_ID));
        assertEquals("dev-machine-01", captor.getValue().getName());
        assertEquals("QODER_CN_CLI", captor.getValue().getClientKind());
        assertEquals("none", captor.getValue().getMemoryMode());
        assertEquals("lite", captor.getValue().getModel());
        assertEquals("low", captor.getValue().getReasoningEffort());
        assertEquals("400000", captor.getValue().getContextWindow());
    }

    @Test
    void createExecutorLeavesOmittedLaunchValuesForTheServiceToResolve() {
        McpAccessTokenService.Principal admin = principal(WorkspaceAccessLevel.ADMIN);
        when(executorService.create(eq(5L), any(CreateExecutorRequest.class), eq(WORKSPACE_ID), eq(USER_ID)))
                .thenReturn(issued(9L, 5L, "dev-machine-01", "awexec_plain"));

        CreatedExecutorVO created = (CreatedExecutorVO) call(admin, "autowonder.create_executor",
                Map.of("agentId", 5L, "name", "dev-machine-01", "clientKind", "QODER_CLI"));

        ArgumentCaptor<CreateExecutorRequest> captor = ArgumentCaptor.forClass(CreateExecutorRequest.class);
        verify(executorService).create(eq(5L), captor.capture(), eq(WORKSPACE_ID), eq(USER_ID));
        // MCP 不自己填默认值：缺省的启动值由服务端解析后与执行器一并落库，避免两个入口的默认值分叉
        assertNull(captor.getValue().getMemoryMode());
        assertNull(captor.getValue().getModel());
        assertNull(captor.getValue().getReasoningEffort());
        assertNull(captor.getValue().getContextWindow());

        assertEquals("platform", created.getMemoryMode());
        assertEquals("auto", created.getModel());
        assertEquals("medium", created.getReasoningEffort());
        assertEquals("260000", created.getContextWindow());
        verifyNoInteractions(providerModelCatalogService);
    }

    @Test
    void createExecutorRejectsNonCreatableClientKinds() {
        McpAccessTokenService.Principal admin = principal(WorkspaceAccessLevel.ADMIN);
        List<Map<String, Object>> cases = List.of(
                Map.of("agentId", 5L, "name", "n", "clientKind", "CLAUDE_CODE"),
                Map.of("agentId", 5L, "name", "n", "clientKind", "CODEX_CLI"),
                Map.of("agentId", 5L, "name", "n", "clientKind", "CURSOR_CLI"));

        for (Map<String, Object> args : cases) {
            BizException exception = assertThrows(BizException.class,
                    () -> call(admin, "autowonder.create_executor", args));
            assertEquals("27003", exception.getCode(), String.valueOf(args));
            assertTrue(exception.getMessage().contains("clientKind 仅支持"), String.valueOf(args));
        }

        // 客户端类型不合法时不能留下一个已创建的执行器和一个已签发的 Token
        verify(executorService, never()).create(anyLong(), any(), anyLong(), anyLong());
        verifyNoInteractions(providerModelCatalogService);
    }

    @Test
    void createExecutorSurfacesTheServicesRefusalOfAnUnavailableModel() {
        McpAccessTokenService.Principal admin = principal(WorkspaceAccessLevel.ADMIN);
        when(executorService.create(eq(5L), any(CreateExecutorRequest.class), eq(WORKSPACE_ID), eq(USER_ID)))
                .thenThrow(new BizException(ErrorCode.EXECUTOR_LAUNCH_CONFIG_MODEL_INVALID,
                        "模型 removed-model 已不可用，请重新选择"));

        BizException exception = assertThrows(BizException.class, () -> call(admin, "autowonder.create_executor",
                Map.of("agentId", 5L, "name", "dev-machine-01", "clientKind", "QODER_CLI",
                        "model", "removed-model")));

        // 启动值的校验与落库在服务端同一处完成，MCP 不再读 Redis 目录把过期模型悄悄换成别的
        assertEquals("17006", exception.getCode());
        assertTrue(exception.getMessage().contains("removed-model"), exception.getMessage());
        verifyNoInteractions(providerModelCatalogService);
    }

    @Test
    void createExecutorRejectsMissingRequiredArguments() {
        McpAccessTokenService.Principal admin = principal(WorkspaceAccessLevel.ADMIN);
        List<Map<String, Object>> cases = List.of(
                Map.of("name", "n", "clientKind", "QODER_CLI"),
                Map.of("agentId", 5L, "clientKind", "QODER_CLI"),
                Map.of("agentId", 5L, "name", "n"));

        for (Map<String, Object> args : cases) {
            BizException exception = assertThrows(BizException.class,
                    () -> call(admin, "autowonder.create_executor", args));
            assertEquals("27003", exception.getCode(), String.valueOf(args));
        }

        verify(executorService, never()).create(anyLong(), any(), anyLong(), anyLong());
    }

    @Test
    void buildExecutorLaunchCommandGeneratesThePageIdenticalCommandFromTheStoredConfig() {
        McpAccessTokenService.Principal admin = principal(WorkspaceAccessLevel.ADMIN);
        when(executorService.getDetail(9L, WORKSPACE_ID)).thenReturn(executor(9L, 5L, "QODER_CLI"));
        when(executorService.getToken(9L, WORKSPACE_ID)).thenReturn("awexec_plain");
        stubPersistedLaunchConfig("qmodel_latest", "platform", "medium", "260000");

        ExecutorLaunchCommandVO command = (ExecutorLaunchCommandVO) call(admin,
                "autowonder.build_executor_launch_command", Map.of("id", 9L));

        assertEquals("npx -y autowonder@0.2.152 connect "
                + "--ws-url wss://auto-wonder.example.com/ws/executor "
                + "--token awexec_plain --executor-id 9 --provider qoder --memory-mode platform --max-tasks 5 "
                + "--model qmodel_latest --reasoning-effort medium --context-window 260000 "
                + "--token-aware-enable", command.getCommand());
        assertEquals(9L, command.getExecutorId());
        assertEquals("qoder", command.getProvider());
        assertEquals("platform", command.getMemoryMode());
        assertEquals("qmodel_latest", command.getModel());
        assertEquals("medium", command.getReasoningEffort());
        assertEquals("260000", command.getContextWindow());
        assertEquals("wss://auto-wonder.example.com/ws/executor", command.getWsUrl());
        assertEquals("0.2.152", command.getRuntimeVersion());
        assertEquals("posix", command.getOs());
        assertFalse(command.isDebug());
        assertNull(command.getShell());
        assertNull(command.getLogFileName());
        // 生成命令只读数据库：既不回写配置，也不读 Redis 模型目录
        verify(executorLaunchConfigService, never()).updateConfig(anyLong(), anyLong(), any(), anyLong());
        verifyNoInteractions(providerModelCatalogService);
    }

    @Test
    void buildExecutorLaunchCommandRejectsEveryLaunchOverride() {
        McpAccessTokenService.Principal admin = principal(WorkspaceAccessLevel.ADMIN);
        Map<String, Object> overrides = Map.of("memoryMode", "none", "model", "qmodel_latest",
                "reasoningEffort", "high", "contextWindow", "1000000");

        for (Map.Entry<String, Object> override : overrides.entrySet()) {
            BizException exception = assertThrows(BizException.class, () -> call(admin,
                    "autowonder.build_executor_launch_command",
                    Map.of("id", 9L, override.getKey(), override.getValue())), override.getKey());
            assertEquals("17008", exception.getCode(), override.getKey());
            // 老脚本要被指到正确的修改入口，而不是拿到一条与数据库不符的命令
            assertTrue(exception.getMessage().contains(override.getKey()), exception.getMessage());
            assertTrue(exception.getMessage().contains("autowonder.update_executor_launch_config"),
                    exception.getMessage());
        }

        verifyNoInteractions(executorService);
        verifyNoInteractions(executorLaunchConfigService);

        when(executorService.getDetail(9L, WORKSPACE_ID)).thenReturn(executor(9L, 5L, "QODER_CLI"));
        when(executorService.getToken(9L, WORKSPACE_ID)).thenReturn("awexec_plain");
        stubPersistedLaunchConfig("qmodel_latest", "platform", "medium", "260000");

        // 空串等同于未传：老脚本里留下的空占位不会误报成覆盖
        ExecutorLaunchCommandVO blank = (ExecutorLaunchCommandVO) call(admin,
                "autowonder.build_executor_launch_command", Map.of("id", 9L, "model", "  "));
        assertEquals("qmodel_latest", blank.getModel());
    }

    @Test
    void buildExecutorLaunchCommandAppliesOutputOptionsWithoutTouchingTheStoredConfig() {
        McpAccessTokenService.Principal admin = principal(WorkspaceAccessLevel.ADMIN);
        when(executorService.getDetail(9L, WORKSPACE_ID)).thenReturn(executor(9L, 5L, "QODER_CLI"));
        when(executorService.getToken(9L, WORKSPACE_ID)).thenReturn("awexec_plain");
        stubPersistedLaunchConfig("qmodel_latest", "platform", "medium", "260000");

        ExecutorLaunchCommandVO command = (ExecutorLaunchCommandVO) call(admin,
                "autowonder.build_executor_launch_command",
                Map.of("id", 9L, "os", "windows", "debug", true));

        assertEquals("windows", command.getOs());
        assertTrue(command.isDebug());
        assertEquals("powershell", command.getShell());
        assertTrue(command.getCommand().startsWith("powershell -NoProfile -EncodedCommand "));
        assertTrue(command.getLogFileName().matches("aw-qoder-9-\\d{6}-\\d{2}-\\d{2}-\\d{2}\\.log"),
                command.getLogFileName());
        // os/debug/shell 只决定输出格式，绝不能被写回启动配置
        verify(executorLaunchConfigService, never()).updateConfig(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void buildExecutorLaunchCommandOmitsQoderFlagsForLegacyExecutors() {
        McpAccessTokenService.Principal admin = principal(WorkspaceAccessLevel.ADMIN);
        when(executorService.getDetail(9L, WORKSPACE_ID)).thenReturn(executor(9L, 5L, "CLAUDE_CODE"));
        when(executorService.getToken(9L, WORKSPACE_ID)).thenReturn("awexec_plain");
        stubPersistedLaunchConfig(null, "provider-local", null, null);

        ExecutorLaunchCommandVO command = (ExecutorLaunchCommandVO) call(admin,
                "autowonder.build_executor_launch_command", Map.of("id", 9L));

        assertEquals("claude", command.getProvider());
        assertNull(command.getModel());
        assertNull(command.getReasoningEffort());
        assertNull(command.getContextWindow());
        assertTrue(command.getCommand().contains("--memory-mode provider-local"), command.getCommand());
        assertFalse(command.getCommand().contains("--token-aware-enable"));
        assertFalse(command.getCommand().contains("--model"));
        verifyNoInteractions(providerModelCatalogService);
    }

    @Test
    void buildExecutorLaunchCommandSurfacesAnUnusableStoredConfig() {
        McpAccessTokenService.Principal admin = principal(WorkspaceAccessLevel.ADMIN);
        when(executorService.getDetail(9L, WORKSPACE_ID)).thenReturn(executor(9L, 5L, "QODER_CLI"));
        when(executorService.getToken(9L, WORKSPACE_ID)).thenReturn("awexec_plain");

        // 从未保存过启动配置的执行器只能如实报错，不能用系统默认值拼出一条看着能跑的命令
        // doThrow 形式：requireCompleteConfig 已被打桩成抛异常，when(...) 再调一次会把异常抛到断言之外
        doThrow(new BizException(ErrorCode.EXECUTOR_LAUNCH_CONFIG_INCOMPLETE))
                .when(executorLaunchConfigService).requireCompleteConfig(9L, WORKSPACE_ID);
        BizException incomplete = assertThrows(BizException.class, () -> call(admin,
                "autowonder.build_executor_launch_command", Map.of("id", 9L)));
        assertEquals("17007", incomplete.getCode());

        // 已保存的模型被目录下架时点名该模型，操作者才知道要重新保存什么
        doThrow(new BizException(ErrorCode.EXECUTOR_LAUNCH_CONFIG_MODEL_INVALID,
                "已保存的模型 removed-model 已不可用，请重新选择并保存启动配置"))
                .when(executorLaunchConfigService).requireCompleteConfig(9L, WORKSPACE_ID);
        BizException rotated = assertThrows(BizException.class, () -> call(admin,
                "autowonder.build_executor_launch_command", Map.of("id", 9L)));
        assertEquals("17006", rotated.getCode());
        assertTrue(rotated.getMessage().contains("removed-model"), rotated.getMessage());

        verify(executorLaunchConfigService, never()).updateConfig(anyLong(), anyLong(), any(), anyLong());
        verifyNoInteractions(providerModelCatalogService);
    }

    @Test
    void getExecutorLaunchConfigReturnsTheStoredRowVerbatim() {
        McpAccessTokenService.Principal admin = principal(WorkspaceAccessLevel.ADMIN);
        ExecutorLaunchConfigVO neverConfigured = storedConfig(1, null, null, null, null);
        when(executorLaunchConfigService.readStoredConfig(9L, WORKSPACE_ID)).thenReturn(neverConfigured);

        assertSame(neverConfigured, call(admin, "autowonder.get_executor_launch_config", Map.of("id", 9L)));

        // 只读工具不补默认值、不修复被下架的模型、不回写这一行
        verify(executorLaunchConfigService, never()).updateConfig(anyLong(), anyLong(), any(), anyLong());
        verifyNoInteractions(providerModelCatalogService);
        verifyNoInteractions(executorService);
    }

    @Test
    void getExecutorLaunchConfigSurfacesAMissingExecutor() {
        McpAccessTokenService.Principal admin = principal(WorkspaceAccessLevel.ADMIN);
        when(executorLaunchConfigService.readStoredConfig(9L, WORKSPACE_ID))
                .thenThrow(new BizException(ErrorCode.EXECUTOR_NOT_FOUND));

        BizException exception = assertThrows(BizException.class,
                () -> call(admin, "autowonder.get_executor_launch_config", Map.of("id", 9L)));

        assertEquals("17001", exception.getCode());
    }

    @Test
    void updateExecutorLaunchConfigReturnsWhatTheDatabaseNowHolds() {
        McpAccessTokenService.Principal admin = principal(WorkspaceAccessLevel.ADMIN);
        ExecutorLaunchConfigVO saved = storedConfig(4, "lite", "provider-local", "low", "400000");
        when(executorLaunchConfigService.updateConfig(eq(9L), eq(WORKSPACE_ID),
                any(UpdateExecutorLaunchConfigRequest.class), eq(USER_ID))).thenReturn(saved);

        Object result = call(admin, "autowonder.update_executor_launch_config",
                Map.of("id", 9L, "version", 3, "memoryMode", "provider-local", "model", "lite",
                        "reasoningEffort", "low", "contextWindow", "400000", "maxConcurrentDispatches", 5));

        assertSame(saved, result);

        ArgumentCaptor<UpdateExecutorLaunchConfigRequest> captor =
                ArgumentCaptor.forClass(UpdateExecutorLaunchConfigRequest.class);
        verify(executorLaunchConfigService).updateConfig(eq(9L), eq(WORKSPACE_ID), captor.capture(), eq(USER_ID));
        assertEquals(3, captor.getValue().getVersion());
        assertEquals(5, captor.getValue().getMaxConcurrentDispatches());
        assertEquals("provider-local", captor.getValue().getMemoryMode());
        assertEquals("lite", captor.getValue().getModel());
        assertEquals("low", captor.getValue().getReasoningEffort());
        assertEquals("400000", captor.getValue().getContextWindow());
        verifyNoInteractions(providerModelCatalogService);
    }

    @Test
    void updateExecutorLaunchConfigNamesTheToolToReadTheVersionFrom() {
        McpAccessTokenService.Principal admin = principal(WorkspaceAccessLevel.ADMIN);

        BizException exception = assertThrows(BizException.class, () -> call(admin,
                "autowonder.update_executor_launch_config", Map.of("id", 9L, "model", "auto")));

        assertEquals("27003", exception.getCode());
        assertTrue(exception.getMessage().contains("autowonder.get_executor_launch_config"),
                exception.getMessage());
        // 缺版本号就是一次盲写，连启动配置服务都不该被触达
        verifyNoInteractions(executorLaunchConfigService);
    }

    @Test
    void updateExecutorLaunchConfigSurfacesAStaleVersionAndAnInvalidValue() {
        McpAccessTokenService.Principal admin = principal(WorkspaceAccessLevel.ADMIN);
        when(executorLaunchConfigService.updateConfig(eq(9L), eq(WORKSPACE_ID),
                any(UpdateExecutorLaunchConfigRequest.class), eq(USER_ID)))
                .thenThrow(new BizException(ErrorCode.EXECUTOR_LAUNCH_CONFIG_VERSION_CONFLICT))
                .thenThrow(new BizException(ErrorCode.EXECUTOR_LAUNCH_CONFIG_MODEL_INVALID,
                        "模型 removed-model 已不可用，请重新选择"));

        // 并发保存冲突与非法取值都要显式报错，不能悄悄换成别的值后返回成功
        assertEquals("17005", assertThrows(BizException.class, () -> call(admin,
                "autowonder.update_executor_launch_config", Map.of("id", 9L, "version", 3))).getCode());

        BizException invalid = assertThrows(BizException.class, () -> call(admin,
                "autowonder.update_executor_launch_config",
                Map.of("id", 9L, "version", 3, "model", "removed-model")));
        assertEquals("17006", invalid.getCode());
        assertTrue(invalid.getMessage().contains("removed-model"), invalid.getMessage());
        verifyNoInteractions(providerModelCatalogService);
    }

    private ExecutorLaunchConfigVO storedConfig(int version, String model, String memoryMode,
            String reasoningEffort, String contextWindow) {
        ExecutorLaunchConfigVO vo = new ExecutorLaunchConfigVO();
        vo.setModel(model);
        vo.setMemoryMode(memoryMode);
        vo.setReasoningEffort(reasoningEffort);
        vo.setContextWindow(contextWindow);
        vo.setVersion(version);
        return vo;
    }

    /** Stubs the persisted row the command generator reads, so no launch value can come from the caller. */
    private void stubPersistedLaunchConfig(String model, String memoryMode, String reasoningEffort,
            String contextWindow) {
        ExecutorLaunchConfigService.LaunchConfig config = new ExecutorLaunchConfigService.LaunchConfig();
        config.model = model;
        config.memoryMode = memoryMode;
        config.reasoningEffort = reasoningEffort;
        config.contextWindow = contextWindow;
        when(executorLaunchConfigService.requireCompleteConfig(9L, WORKSPACE_ID)).thenReturn(config);
    }

    @Test
    void dispatchCredentialCannotManageExecutorsEvenWithAdminWorkspaceAccess() {
        when(dispatchDao.findById(321L)).thenReturn(dispatch(321L, WORKSPACE_ID, 99L, 40014L));
        McpAccessTokenService.Principal dispatchAdmin = new McpAccessTokenService.Principal(
                WORKSPACE_ID, USER_ID, -321L, WorkspaceAccessLevel.ADMIN,
                McpAccessTokenService.CredentialType.DISPATCH);
        Map<String, Object> args =
                Map.of("id", 9L, "agentId", 5L, "name", "n", "clientKind", "QODER_CLI", "version", 1);

        // 启动配置是页面维护的操作者状态，派发凭证只能看到执行器列表
        for (String tool : List.of("autowonder.create_executor", "autowonder.get_executor_token",
                "autowonder.delete_executor", "autowonder.build_executor_launch_command",
                "autowonder.get_executor_launch_config", "autowonder.update_executor_launch_config")) {
            BizException exception = assertThrows(BizException.class, () -> call(dispatchAdmin, tool, args));
            assertEquals("10403", exception.getCode(), tool);
        }

        verifyNoInteractions(executorService);
        verifyNoInteractions(executorLaunchConfigService);
    }

    @Test
    @SuppressWarnings("unchecked")
    void dispatchCredentialKeepsReadOnlyExecutorVisibility() {
        when(executorService.listAll(WORKSPACE_ID, null)).thenReturn(List.of(executor(9L, 5L, "QODER_CLI")));

        List<ExecutorVO> executors =
                (List<ExecutorVO>) call(dispatchPrincipal(), "autowonder.list_executors", Map.of());

        assertEquals(1, executors.size());
        assertEquals(9L, executors.get(0).getId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void executorToolsFailClosedWhenDependenciesAreUnavailable() {
        ReflectionTestUtils.setField(service, "executorService", null);
        ReflectionTestUtils.setField(service, "executorLaunchOptionsService", null);
        ReflectionTestUtils.setField(service, "executorLaunchCommandService", null);
        ReflectionTestUtils.setField(service, "executorLaunchConfigService", null);
        McpAccessTokenService.Principal admin = principal(WorkspaceAccessLevel.ADMIN);
        Map<String, Object> args =
                Map.of("id", 9L, "agentId", 5L, "name", "n", "clientKind", "QODER_CLI", "version", 1);

        for (String tool : List.of("autowonder.list_executors", "autowonder.get_executor",
                "autowonder.get_executor_launch_options", "autowonder.create_executor",
                "autowonder.get_executor_token", "autowonder.delete_executor",
                "autowonder.build_executor_launch_command", "autowonder.get_executor_launch_config",
                "autowonder.update_executor_launch_config")) {
            BizException exception = assertThrows(BizException.class, () -> call(admin, tool, args));
            assertEquals("10000", exception.getCode(), tool);
        }

        List<SelectOptionVO> kinds =
                (List<SelectOptionVO>) call(admin, "autowonder.list_executor_client_kinds", Map.of());
        assertEquals(List.of("QODER_CLI", "QODER_CN_CLI"),
                kinds.stream().map(SelectOptionVO::getValue).toList());
    }

    @SuppressWarnings("unchecked")
    private List<String> enumValues(String tool, String propertyName) {
        return (List<String>) property(schemaFor(tool), propertyName).get("enum");
    }

    private ExecutorVO executor(long id, long agentId, String clientKind) {
        ExecutorVO vo = new ExecutorVO();
        vo.setId(id);
        vo.setAgentId(agentId);
        vo.setAgentName("Alpha");
        vo.setName("executor-" + id);
        vo.setClientKind(clientKind);
        vo.setStatus("ONLINE");
        return vo;
    }

    private IssuedExecutorVO issued(long id, long agentId, String name, String token) {
        return issued(id, agentId, name, token, "QODER_CLI", "platform", "auto", "medium", "260000");
    }

    /** The create response echoes the persisted row, so the stub has to carry the launch values it saved. */
    private IssuedExecutorVO issued(long id, long agentId, String name, String token, String clientKind,
            String memoryMode, String model, String reasoningEffort, String contextWindow) {
        IssuedExecutorVO vo = new IssuedExecutorVO();
        vo.setId(id);
        vo.setAgentId(agentId);
        vo.setName(name);
        vo.setToken(token);
        vo.setClientKind(clientKind);
        vo.setMemoryMode(memoryMode);
        vo.setModel(model);
        vo.setReasoningEffort(reasoningEffort);
        vo.setContextWindow(contextWindow);
        vo.setConfigVersion(1);
        return vo;
    }

    private McpToolVO toolByName(String name) {
        return service.listTools().stream()
                .filter(tool -> name.equals(tool.getName()))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> outputProperties(McpToolVO tool) {
        return (Map<String, Object>) tool.getOutputSchema().get("properties");
    }

    /** Also stubs the membership lookup, since a personal token resolves its level per call. */
    private McpAccessTokenService.Principal principal(WorkspaceAccessLevel accessLevel) {
        when(workspaceService.activeAccessLevel(WORKSPACE_ID, USER_ID)).thenReturn(accessLevel);
        return McpAccessTokenService.Principal.personal(USER_ID, 1L);
    }

    private McpAccessTokenService.Principal dispatchPrincipal(long tokenId) {
        when(dispatchDao.findById(-tokenId)).thenReturn(dispatch(-tokenId, WORKSPACE_ID, 99L, 40014L));
        return new McpAccessTokenService.Principal(
                WORKSPACE_ID, USER_ID, tokenId, WorkspaceAccessLevel.READ_WRITE,
                McpAccessTokenService.CredentialType.DISPATCH);
    }

    /**
     * Also stubs the membership lookup: a conversation token acts as the conversation
     * Owner, so its effective level is the minimum of the token ceiling and the Owner's
     * live membership, resolved on every call.
     */
    private McpAccessTokenService.Principal scopedPrincipal(WorkspaceAccessLevel accessLevel) {
        return conversationPrincipal(accessLevel, accessLevel);
    }

    private McpAccessTokenService.Principal conversationPrincipal(
            WorkspaceAccessLevel tokenLevel, WorkspaceAccessLevel liveLevel) {
        when(workspaceService.activeAccessLevel(WORKSPACE_ID, USER_ID)).thenReturn(liveLevel);
        return new McpAccessTokenService.Principal(
                WORKSPACE_ID, USER_ID, 1L, tokenLevel,
                McpAccessTokenService.CredentialType.CONVERSATION);
    }

    private Object call(McpAccessTokenService.Principal caller, String tool,
                        Map<String, Object> args) {
        return service.call(caller, tool, withWorkspaceId(args));
    }

    private Map<String, Object> withWorkspaceId(Map<String, Object> args) {
        if (args.containsKey("workspaceId")) {
            return args;
        }
        Map<String, Object> merged = new java.util.LinkedHashMap<>(args);
        merged.put("workspaceId", WORKSPACE_ID);
        return merged;
    }

    private WorkitemCliUploadTokenService realTokenService(String baseUrl, String runtimeVersion) {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkspaceMemberDao memberDao = mock(WorkspaceMemberDao.class);
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(50063L);
        workitem.setTenantId(WORKSPACE_ID);
        when(workitemDao.findById(50063L)).thenReturn(workitem);
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setTenantId(WORKSPACE_ID);
        member.setUserId(USER_ID);
        member.setAccessLevel("READ_WRITE");
        member.setStatus(0);
        member.setIsDeleted(0);
        when(memberDao.findByWorkspaceAndUser(WORKSPACE_ID, USER_ID)).thenReturn(member);
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties props = new JwtProperties(env);
        props.setSecret("test-secret-key-that-is-long-enough-32bytes!");
        props.setAccessTtlSeconds(3600);
        props.setRefreshTtlSeconds(7200);
        PlatformBrandingService branding = new PlatformBrandingService(
                mock(PlatformBrandingDao.class), new InMemoryObjectStorage(), new OssProperties(),
                baseUrl, runtimeVersion, "x.x.x", false);
        return new WorkitemCliUploadTokenService(new JwtService(props), workitemDao, memberDao, branding);
    }

    private WorkitemCliDownloadTokenService realDownloadTokenService(String baseUrl, String runtimeVersion) {
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        WorkspaceMemberDao memberDao = mock(WorkspaceMemberDao.class);
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(50063L);
        workitem.setTenantId(WORKSPACE_ID);
        when(workitemDao.findById(50063L)).thenReturn(workitem);
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setTenantId(WORKSPACE_ID);
        member.setUserId(USER_ID);
        member.setAccessLevel("READ_ONLY");
        member.setStatus(0);
        member.setIsDeleted(0);
        when(memberDao.findByWorkspaceAndUser(WORKSPACE_ID, USER_ID)).thenReturn(member);
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties props = new JwtProperties(env);
        props.setSecret("test-secret-key-that-is-long-enough-32bytes!");
        props.setAccessTtlSeconds(3600);
        props.setRefreshTtlSeconds(7200);
        PlatformBrandingService branding = new PlatformBrandingService(
                mock(PlatformBrandingDao.class), new InMemoryObjectStorage(), new OssProperties(),
                baseUrl, runtimeVersion, "x.x.x", false);
        return new WorkitemCliDownloadTokenService(new JwtService(props), workitemDao, memberDao, branding);
    }
}
