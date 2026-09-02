package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.branding.PlatformBrandingDao;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.mcp.dto.WorkitemCliUploadTokenVO;
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
import com.aliyun.autowonder.artifact.dto.ArtifactVO;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.dispatch.DispatchPauseService;
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
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpToolServiceTest {
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
    MemoryService memoryService;
    RepoService repoService;
    SquadService squadService;
    DispatchPauseService dispatchPauseService;
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
        memoryService = mock(MemoryService.class);
        repoService = mock(RepoService.class);
        squadService = mock(SquadService.class);
        dispatchPauseService = mock(DispatchPauseService.class);
        capabilityGuard = mock(ScheduledTaskCapabilityGuard.class);
        service = new McpToolService(workspaceService, workitemService, guidanceService, skillService,
                skillPackageService, sdlcService, agentService, statusTemplateService,
                new PlatformSkillCatalog(), dispatchDao, requirementDocumentService,
                workitemCliUploadTokenService, memoryService, repoService,
                squadService, dispatchPauseService);
        ReflectionTestUtils.setField(service, "capabilityGuard", capabilityGuard);
        principal = principal(WorkspaceAccessLevel.READ_WRITE);
    }

    @Test
    void readOnlyCatalogContainsEveryAndOnlyQueryTool() {
        Set<String> expectedReadOnly = Set.of(
                "autowonder.list_projects",
                "autowonder.list_workitems",
                "autowonder.get_workitem",
                "autowonder.list_workitem_comments",
                "autowonder.list_workitem_documents",
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
                "autowonder.search_memories",
                "autowonder.get_memory",
                "autowonder.list_repos",
                "autowonder.get_repo",
                "autowonder.list_repo_relations",
                "autowonder.list_squads",
                "autowonder.get_squad",
                "autowonder.list_scheduled_tasks",
                "autowonder.get_scheduled_task",
                "autowonder.get_scheduled_task_run");
        Set<String> fullCatalog = service.listTools().stream()
                .map(McpToolVO::getName)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> readOnlyCatalog =
                service.listTools(scopedPrincipal(WorkspaceAccessLevel.READ_ONLY)).stream()
                        .map(McpToolVO::getName)
                        .collect(java.util.stream.Collectors.toSet());

        assertEquals(expectedReadOnly, readOnlyCatalog);
        assertEquals(fullCatalog,
                service.listTools(scopedPrincipal(WorkspaceAccessLevel.READ_WRITE)).stream()
                        .map(McpToolVO::getName)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(fullCatalog,
                service.listTools(scopedPrincipal(WorkspaceAccessLevel.ADMIN)).stream()
                        .map(McpToolVO::getName)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(84, fullCatalog.size());
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
    void privateDeploymentDescriptionsAndResultsNeverExposeDefaults() {
        WorkitemCliUploadTokenService realTokenService = realTokenService(
                "http://autowonder.internal.example.com:8080", "0.9.9-rc.1");
        McpToolService privateService = new McpToolService(workspaceService, workitemService, guidanceService,
                skillService, skillPackageService, sdlcService, agentService, statusTemplateService,
                new PlatformSkillCatalog(), dispatchDao, requirementDocumentService,
                realTokenService, memoryService, repoService, squadService, dispatchPauseService);

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
        assertEquals("array", steps.get("type"));
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

        Map<String, Object> comment = properties(itemSchema(
                property(outputSchemaFor("autowonder.list_workitem_comments"), "items")));
        assertNullableField(comment, "gmtCreate", "string");

        Map<String, Object> document = properties(itemSchema(
                property(outputSchemaFor("autowonder.list_workitem_documents"), "items")));
        assertNullableField(document, "gmtCreate", "string");
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
    void listToolOutputSchemasUseMcpCompatibleObjectEnvelope() {
        assertListOutputSchema(outputSchemaFor("autowonder.list_projects"), "id", "name");
        assertListOutputSchema(outputSchemaFor("autowonder.list_workitems"), "id", "title", "statusName");
        assertListOutputSchema(outputSchemaFor("autowonder.list_workitem_comments"), "id", "contentMd");
        assertListOutputSchema(outputSchemaFor("autowonder.list_status_templates"), "id", "name");
        assertListOutputSchema(outputSchemaFor("autowonder.list_sdlcs"), "id", "name", "status");
        assertListOutputSchema(outputSchemaFor("autowonder.list_agents"), "id", "name", "status");
        assertListOutputSchema(outputSchemaFor("autowonder.list_skills"), "id", "type", "name");
        assertListOutputSchema(outputSchemaFor("autowonder.list_platform_skills"), "id", "name");

        assertTrue(service.listTools().stream()
                .allMatch(tool -> "object".equals(tool.getOutputSchema().get("type"))));
    }

    @Test
    void listToolCallsReturnPlainLists() {
        WorkitemVO workitem = new WorkitemVO();
        workitem.setId(1L);
        when(workitemService.list(null, null, null, null, null, false, null, 100L, 7L, null, null, 1, 20))
                .thenReturn(new PageResult<>(List.of(workitem), 1, 1, 20));

        SdlcVO sdlc = new SdlcVO();
        sdlc.setId(2L);
        when(sdlcService.list(null, null, 1, 20)).thenReturn(List.of(sdlc));

        AgentVO agent = new AgentVO();
        agent.setId(3L);
        when(agentService.list(100L, null, 1, 20)).thenReturn(List.of(agent));

        SkillVO skill = new SkillVO();
        skill.setId(4L);
        when(skillService.list(null, 1, 20)).thenReturn(List.of(skill));

        assertEquals(List.of(workitem), call(principal, "autowonder.list_workitems", Map.of()));
        assertEquals(List.of(sdlc), call(principal, "autowonder.list_sdlcs", Map.of()));
        assertEquals(List.of(agent), call(principal, "autowonder.list_agents", Map.of()));
        assertEquals(List.of(skill), call(principal, "autowonder.list_skills", Map.of()));
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
        when(agentService.get(40014L)).thenReturn(agent);
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

        assertNotNull(toolByName("autowonder.get_scheduled_task").getOutputSchema());
        assertTrue(outputProperties(toolByName("autowonder.list_scheduled_tasks")).containsKey("list"));
        assertTrue(outputProperties(toolByName("autowonder.list_scheduled_tasks")).containsKey("total"));
        assertNotNull(toolByName("autowonder.transition_scheduled_task").getOutputSchema().get("anyOf"));
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
                "autowonder.update_scheduled_task", "autowonder.transition_scheduled_task")) {
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
        assertEquals(List.of("workspaceId", "fileName", "contentBase64"), uploadSchema.get("required"));
        assertTrue(properties(uploadSchema).keySet().containsAll(List.of(
                "fileName", "contentBase64", "type", "expectedMd5")));

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
    void searchMemoriesDefaultsToAdoptedAndPushesVisibilityDownToSql() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal();
        when(dispatchDao.findById(321L)).thenReturn(dispatch(321L, 100L, 99L, 40014L));
        MemoryVO own = memory(500L, "AGENT", 40014L);
        when(memoryService.list(100L, "AGENT", 99999L, null, "ADOPTED", "MyBatis", 40014L, 1, 20))
                .thenReturn(List.of(own));

        Object result = service.call(dispatchPrincipal, "autowonder.search_memories",
                Map.of("keyword", "MyBatis", "scope", "agent", "ownerRef", 99999L));

        assertEquals(List.of(own), result);
        verify(memoryService).list(100L, "AGENT", 99999L, null, "ADOPTED", "MyBatis", 40014L, 1, 20);
    }

    @Test
    void searchMemoriesAlwaysPushesVisibleAgentRefRegardlessOfRequestedScope() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal();
        when(dispatchDao.findById(321L)).thenReturn(dispatch(321L, 100L, 99L, 40014L));

        service.call(dispatchPrincipal, "autowonder.search_memories", Map.of());
        service.call(dispatchPrincipal, "autowonder.search_memories", Map.of("scope", "AGENT"));
        service.call(dispatchPrincipal, "autowonder.search_memories", Map.of("scope", "SQUAD"));
        service.call(dispatchPrincipal, "autowonder.search_memories", Map.of("scope", "ORG"));

        verify(memoryService).list(100L, null, null, null, "ADOPTED", null, 40014L, 1, 20);
        verify(memoryService).list(100L, "AGENT", null, null, "ADOPTED", null, 40014L, 1, 20);
        verify(memoryService).list(100L, "SQUAD", null, null, "ADOPTED", null, 40014L, 1, 20);
        verify(memoryService).list(100L, "ORG", null, null, "ADOPTED", null, 40014L, 1, 20);
        verify(memoryService, never()).list(anyLong(), any(), any(), any(), any(), any(), isNull(), anyInt(), anyInt());
    }

    @Test
    void searchMemoriesWithLongLivedTokenPassesNoVisibilityConstraint() {
        call(principal, "autowonder.search_memories", Map.of("keyword", "MyBatis"));

        verify(memoryService).list(100L, null, null, null, "ADOPTED", "MyBatis", null, 1, 20);
        verifyNoInteractions(dispatchDao);
    }

    @Test
    void searchMemoriesHidesAgentScopedMemoriesOwnedByAnotherAgent() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal();
        when(dispatchDao.findById(321L)).thenReturn(dispatch(321L, 100L, 99L, 40014L));
        MemoryVO own = memory(500L, "AGENT", 40014L);
        MemoryVO shared = memory(501L, "ORG", null);
        MemoryVO foreign = memory(502L, "AGENT", 40015L);
        when(memoryService.list(100L, null, null, null, "ADOPTED", null, 40014L, 1, 20))
                .thenReturn(List.of(own, shared, foreign));

        Object result = service.call(dispatchPrincipal, "autowonder.search_memories", Map.of());

        assertEquals(List.of(own, shared), result);
    }

    @Test
    void getMemoryRejectsAgentScopedMemoryOwnedByAnotherAgent() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal();
        when(dispatchDao.findById(321L)).thenReturn(dispatch(321L, 100L, 99L, 40014L));
        when(memoryService.getScoped(502L, 100L)).thenReturn(memory(502L, "AGENT", 40015L));

        BizException ex = assertThrows(BizException.class, () -> service.call(dispatchPrincipal,
                "autowonder.get_memory", Map.of("id", 502L)));

        assertEquals("10403", ex.getCode());
    }

    @Test
    void getMemoryReturnsSharedScopeMemory() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal();
        when(dispatchDao.findById(321L)).thenReturn(dispatch(321L, 100L, 99L, 40014L));
        MemoryVO shared = memory(501L, "ORG", null);
        when(memoryService.getScoped(501L, 100L)).thenReturn(shared);

        assertSame(shared, service.call(dispatchPrincipal, "autowonder.get_memory", Map.of("id", 501L)));
    }

    @Test
    void memoryMutationsRejectMemoriesNotOwnedByCallingAgent() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal();
        when(dispatchDao.findById(321L)).thenReturn(dispatch(321L, 100L, 99L, 40014L));
        when(memoryService.getScoped(502L, 100L)).thenReturn(memory(502L, "AGENT", 40015L));
        when(memoryService.getScoped(501L, 100L)).thenReturn(memory(501L, "ORG", null));

        for (String tool : List.of("autowonder.update_memory", "autowonder.deprecate_memory",
                "autowonder.delete_memory")) {
            assertEquals("10403", assertThrows(BizException.class, () -> service.call(
                    dispatchPrincipal, tool, Map.of("id", 502L))).getCode(), tool);
            assertEquals("10403", assertThrows(BizException.class, () -> service.call(
                    dispatchPrincipal, tool, Map.of("id", 501L))).getCode(), tool);
        }

        verify(memoryService, never()).update(anyLong(), any(), anyLong(), anyLong());
        verify(memoryService, never()).deprecateFromMcp(anyLong(), any(), anyLong(), anyLong());
        verify(memoryService, never()).delete(anyLong(), anyLong(), anyLong());
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
        when(agentService.get(10L)).thenReturn(agent);
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
        when(skillService.list("CODEX_SKILL", 1, 100)).thenReturn(java.util.List.of(existing));

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
        when(agentService.get(10L)).thenReturn(agent);
        when(agentService.listVersions(10L)).thenReturn(List.of(v1, v2));

        Object result = call(principal, "autowonder.get_agent_version_status",
                Map.of("id", 10L));

        assertTrue(result instanceof Map<?, ?>);
        Map<?, ?> map = (Map<?, ?>) result;
        assertSame(agent, map.get("agent"));
        assertEquals(List.of(v1, v2), map.get("versions"));
        verify(agentService).get(10L);
        verify(agentService).listVersions(10L);
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
        verifyNoInteractions(workspaceService);
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

    private McpAccessTokenService.Principal scopedPrincipal(WorkspaceAccessLevel accessLevel) {
        return new McpAccessTokenService.Principal(
                WORKSPACE_ID, USER_ID, 1L, accessLevel,
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
}
