package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.agent.AgentService;
import com.aliyun.autowonder.agent.dto.AgentVO;
import com.aliyun.autowonder.agent.dto.AgentVersionSummaryVO;
import com.aliyun.autowonder.artifact.RequirementDocumentService;
import com.aliyun.autowonder.artifact.dto.ArtifactVO;
import com.aliyun.autowonder.guidance.GuidanceService;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.mcp.dto.McpToolVO;
import com.aliyun.autowonder.memory.MemoryService;
import com.aliyun.autowonder.memory.dto.CreateMemoryRequest;
import com.aliyun.autowonder.memory.dto.MemoryVO;
import com.aliyun.autowonder.memory.dto.UpdateMemoryRequest;
import com.aliyun.autowonder.org.OrgService;
import com.aliyun.autowonder.org.dto.OrgVO;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.Date;
import java.util.Map;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class McpToolServiceTest {
    private static final long ORG_ID = 100L;
    private static final long USER_ID = 7L;

    OrgService orgService;
    WorkitemService workitemService;
    GuidanceService guidanceService;
    SkillService skillService;
    SkillPackageService skillPackageService;
    SdlcService sdlcService;
    AgentService agentService;
    StatusTemplateService statusTemplateService;
    DispatchDao dispatchDao;
    RequirementDocumentService requirementDocumentService;
    MemoryService memoryService;
    RepoService repoService;
    SquadService squadService;
    McpToolService service;
    McpAccessTokenService.Principal principal;

    @BeforeEach
    void setUp() {
        orgService = mock(OrgService.class);
        workitemService = mock(WorkitemService.class);
        guidanceService = mock(GuidanceService.class);
        skillService = mock(SkillService.class);
        skillPackageService = mock(SkillPackageService.class);
        sdlcService = mock(SdlcService.class);
        agentService = mock(AgentService.class);
        statusTemplateService = mock(StatusTemplateService.class);
        dispatchDao = mock(DispatchDao.class);
        requirementDocumentService = mock(RequirementDocumentService.class);
        memoryService = mock(MemoryService.class);
        repoService = mock(RepoService.class);
        squadService = mock(SquadService.class);
        service = new McpToolService(orgService, workitemService, guidanceService, skillService,
                skillPackageService, sdlcService, agentService, statusTemplateService,
                new PlatformSkillCatalog(), dispatchDao, requirementDocumentService, memoryService, repoService,
                squadService);
        principal = principal(OrgAccessLevel.READ_WRITE);
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
                "autowonder.get_squad");
        Set<String> fullCatalog = service.listTools().stream()
                .map(McpToolVO::getName)
                .collect(java.util.stream.Collectors.toSet());
        Set<String> readOnlyCatalog =
                service.listTools(scopedPrincipal(OrgAccessLevel.READ_ONLY)).stream()
                        .map(McpToolVO::getName)
                        .collect(java.util.stream.Collectors.toSet());

        assertEquals(expectedReadOnly, readOnlyCatalog);
        assertEquals(fullCatalog,
                service.listTools(scopedPrincipal(OrgAccessLevel.READ_WRITE)).stream()
                        .map(McpToolVO::getName)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(fullCatalog,
                service.listTools(scopedPrincipal(OrgAccessLevel.ADMIN)).stream()
                        .map(McpToolVO::getName)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(68, fullCatalog.size());
    }

    @Test
    void agentBindingToolsDeduplicateIdsAndDelegateToAgentService() {
        assertEquals(Map.of("repoIds", List.of(11L, 12L)),
                service.call(principal, "autowonder.bind_agent_repos",
                        Map.of("orgId", ORG_ID, "agentId", 5L,
                                "repoIds", List.of(11L, 11L, 12L), "permLevel", "WRITE")));
        assertEquals(Map.of("skillIds", List.of(21L, 22L)),
                service.call(principal, "autowonder.bind_agent_skills",
                        Map.of("orgId", ORG_ID, "agentId", 5L,
                                "skillIds", List.of(21L, 21L, 22L))));
        assertEquals(Map.of("memoryIds", List.of(31L, 32L)),
                service.call(principal, "autowonder.bind_agent_memories",
                        Map.of("orgId", ORG_ID, "agentId", 5L,
                                "memoryIds", List.of(31L, 31L, 32L), "source", "ORG")));

        verify(agentService, times(2)).addRepoPerm(eq(5L), any(), eq(ORG_ID), eq(USER_ID));
        verify(agentService, times(2)).addSkill(eq(5L), any(), eq(ORG_ID), eq(USER_ID));
        verify(agentService, times(2)).addMemoryRef(eq(5L), any(), eq(ORG_ID), eq(USER_ID));

        assertEquals("array", ((Map<?, ?>) outputProperties(toolByName("autowonder.bind_agent_repos"))
                .get("repoIds")).get("type"));
        assertEquals("array", ((Map<?, ?>) outputProperties(toolByName("autowonder.bind_agent_skills"))
                .get("skillIds")).get("type"));
        assertEquals("array", ((Map<?, ?>) outputProperties(toolByName("autowonder.bind_agent_memories"))
                .get("memoryIds")).get("type"));
    }

    @Test
    void squadToolsDelegateToSquadServiceAndExposePrimitiveMemberIds() {
        SquadVO squad = new SquadVO();
        when(squadService.get(42L)).thenReturn(squad);

        assertEquals(squad, service.call(principal, "autowonder.get_squad", Map.of("orgId", ORG_ID, "id", 42L)));
        assertEquals(Map.of("added", true), service.call(principal, "autowonder.add_agent_to_squad",
                Map.of("orgId", ORG_ID, "squadId", 42L, "agentId", 5L)));
        verify(squadService).addMembers(42L, List.of(5L), ORG_ID);

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
        when(repoService.list(ORG_ID, 1, 100)).thenReturn(List.of(repo));
        RepoRelationVO relation = new RepoRelationVO();
        relation.setId(91L);
        relation.setFromRepoId(10L);
        relation.setToRepoId(11L);
        relation.setRelationType("DEPENDS_ON");
        when(repoService.listRelationsByRepoId(ORG_ID, 10L)).thenReturn(List.of(relation));
        when(repoService.createRelation(any(CreateRelationRequest.class), eq(ORG_ID), eq(USER_ID)))
                .thenReturn(relation);

        assertEquals(List.of(repo), service.call(principal, "autowonder.list_repos",
                Map.of("orgId", ORG_ID)));
        assertEquals(List.of(relation), service.call(principal, "autowonder.list_repo_relations",
                Map.of("orgId", ORG_ID, "repoId", 10L)));
        assertEquals(relation, service.call(principal, "autowonder.create_repo_relation",
                Map.of("orgId", ORG_ID, "fromRepoId", 10L, "toRepoId", 11L,
                        "relationType", "DEPENDS_ON")));
        service.call(principal, "autowonder.delete_repo_relation",
                Map.of("orgId", ORG_ID, "id", 91L));

        verify(repoService).get(10L, ORG_ID);
        verify(repoService).deleteRelation(91L, ORG_ID);
    }

    @Test
    void createRepoDelegatesToRepoService() {
        RepoVO created = new RepoVO();
        created.setId(20L);
        created.setName("new-repo");
        when(repoService.create(any(CreateRepoRequest.class), eq(ORG_ID), eq(USER_ID)))
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
                eq(ORG_ID), eq(USER_ID));
    }

    @Test
    void createRepoSchemaRequiresNameAndUrl() {
        Map<String, Object> schema = schemaFor("autowonder.create_repo");
        assertEquals(List.of("orgId", "name", "url"), schema.get("required"));
        assertTrue(properties(schema).keySet().containsAll(
                List.of("name", "url", "defaultBranch", "description")));
    }

    @Test
    void updateRepoDelegatesToRepoService() {
        RepoVO updated = new RepoVO();
        updated.setId(20L);
        updated.setName("renamed-repo");
        when(repoService.update(eq(20L), any(UpdateRepoRequest.class), eq(ORG_ID), eq(USER_ID)))
                .thenReturn(updated);

        Object result = call(principal, "autowonder.update_repo",
                Map.of("id", 20L, "name", "renamed-repo", "description", "Updated description"));

        assertSame(updated, result);
        verify(repoService).update(eq(20L), argThat(req ->
                "renamed-repo".equals(req.getName())
                        && "Updated description".equals(req.getDescription())),
                eq(ORG_ID), eq(USER_ID));
    }

    @Test
    void updateRepoSchemaRequiresIdAndMakesOtherFieldsOptional() {
        Map<String, Object> schema = schemaFor("autowonder.update_repo");
        assertEquals(List.of("orgId", "id"), schema.get("required"));
        assertTrue(properties(schema).keySet().containsAll(
                List.of("id", "name", "url", "defaultBranch", "description")));
    }

    @Test
    void deleteRepoDelegatesToRepoService() {
        Object result = call(principal, "autowonder.delete_repo",
                Map.of("id", 20L));

        assertEquals(Map.of("deleted", true), result);
        verify(repoService).delete(20L, ORG_ID, USER_ID);
    }

    @Test
    void deleteRepoSchemaRequiresOnlyId() {
        Map<String, Object> schema = schemaFor("autowonder.delete_repo");
        assertEquals(List.of("orgId", "id"), schema.get("required"));
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
                service.listTools(scopedPrincipal(OrgAccessLevel.READ_ONLY)).stream()
                        .map(McpToolVO::getName)
                        .collect(java.util.stream.Collectors.toSet());

        for (McpToolVO tool : service.listTools()) {
            if (!readOnlyNames.contains(tool.getName())) {
                BizException exception = assertThrows(BizException.class, () ->
                        call(principal(OrgAccessLevel.READ_ONLY),
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
        when(workitemService.list(null, null, null, null, false, null, 100L, 7L, null, 1, 20))
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
        assertEquals(List.of("orgId", "sdlcId"), schema.get("required"));
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
        assertEquals(List.of("orgId", "sdlcId", "stepId"), schema.get("required"));
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
    void listProjectsOutputSchemaExposesTheAccessLevel() {
        Map<String, Object> itemSchema = itemsSchema(
                outputSchemaFor("autowonder.list_projects"));

        assertTrue(properties(itemSchema).containsKey("accessLevel"));
        assertTrue(properties(itemSchema).containsKey("id"));
    }

    @Test
    void listProjectsDiscoversEveryAccessibleOrganizationWithItsAccessLevel() {
        OrgVO first = new OrgVO();
        first.setId(100L);
        first.setName("token-org");
        first.setAccessLevel(OrgAccessLevel.ADMIN);
        OrgVO second = new OrgVO();
        second.setId(200L);
        second.setName("other-org");
        second.setAccessLevel(OrgAccessLevel.READ_ONLY);
        when(orgService.listByUserWithAccess(USER_ID)).thenReturn(List.of(first, second));

        Object result = service.call(
                McpAccessTokenService.Principal.personal(USER_ID, 1L),
                "autowonder.list_projects", Map.of());

        assertEquals(List.of(first, second), result);
        verify(orgService).listByUserWithAccess(USER_ID);
        verify(orgService, never()).getCurrent(anyLong());
    }

    @Test
    void taskScopedTokenListsOnlyItsOwnOrganization() {
        OrgVO pinned = new OrgVO();
        pinned.setId(ORG_ID);
        when(orgService.scopedOrg(ORG_ID, OrgAccessLevel.READ_WRITE)).thenReturn(pinned);

        Object result = service.call(dispatchPrincipal(-321L),
                "autowonder.list_projects", Map.of());

        assertEquals(List.of(pinned), result);
        verify(orgService, never()).listByUserWithAccess(anyLong());
    }

    @Test
    void listProjectsNeedsNoOrgIdAndNoMembershipResolution() {
        when(orgService.listByUserWithAccess(USER_ID)).thenReturn(List.of());

        service.call(McpAccessTokenService.Principal.personal(USER_ID, 1L),
                "autowonder.list_projects", Map.of());

        verify(orgService, never()).activeAccessLevel(anyLong(), anyLong());
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
                "priority", "assigneeType", "assigneeRef", "sdlcId", "squadId")));
        // Assignee fields are optional so existing callers keep working; only
        // workType and title remain required.
        assertEquals(List.of("orgId", "workType", "title"), schema.get("required"));
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
        when(workitemService.assign(99L, "AGENT", 12L, 8L, 4L, 100L, 7L)).thenReturn(assigned);

        Object result = call(principal, "autowonder.assign_workitem",
                Map.of("id", 99L, "assigneeType", "AGENT", "assigneeRef", 12L, "sdlcId", 8L, "squadId", 4L));

        assertSame(assigned, result);
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
        when(workitemService.assignAs(99L, "HUMAN", 77L, null, 4L, 100L, 7L,
                AssignmentActor.agent(40014L, "AW开发数字人"))).thenReturn(assigned);

        Object result = call(dispatchPrincipal, "autowonder.assign_workitem",
                Map.of("id", 99L, "assigneeType", "HUMAN", "assigneeRef", 77L, "squadId", 4L));

        assertSame(assigned, result);
        verify(workitemService).assignAs(99L, "HUMAN", 77L, null, 4L, 100L, 7L,
                AssignmentActor.agent(40014L, "AW开发数字人"));
        verify(workitemService, never()).assign(anyLong(), anyString(), any(), any(), any(), anyLong(), anyLong());
    }

    @Test
    void dispatchTokenCannotAssignAnotherWorkitem() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal(-321L);
        when(dispatchDao.findById(321L)).thenReturn(dispatch(321L, 100L, 98L, 40014L));

        assertThrows(BizException.class, () -> call(dispatchPrincipal,
                "autowonder.assign_workitem",
                Map.of("id", 99L, "assigneeType", "HUMAN", "assigneeRef", 77L)));

        verify(workitemService, never()).assignAs(anyLong(), anyString(), any(), any(), any(), anyLong(), anyLong(), any());
        verify(workitemService, never()).assign(anyLong(), anyString(), any(), any(), any(), anyLong(), anyLong());
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
    void dispatchTokenDoesNotImposeUniversalWorkitemScopeOnOtherTools() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal(-321L);
        WorkitemVO workitem = new WorkitemVO();
        workitem.setId(123L);
        when(workitemService.get(123L)).thenReturn(workitem);

        Object result = call(
                dispatchPrincipal, "autowonder.get_workitem", Map.of("id", 123L));

        assertSame(workitem, result);
        verify(workitemService).get(123L);
        verifyNoInteractions(dispatchDao);
    }

    @Test
    void listCommentsRequiresReadPermission() {

        call(principal, "autowonder.list_workitem_comments", Map.of("id", 99L));

        verify(workitemService).listComments(99L);
    }

    @Test
    void workitemDocumentSchemasExposeExpectedFields() {
        Map<String, Object> uploadSchema = schemaFor("autowonder.upload_workitem_document");
        assertEquals(List.of("orgId", "id", "filename"), uploadSchema.get("required"));
        assertTrue(properties(uploadSchema).keySet().containsAll(List.of(
                "id", "filename", "contentMd", "contentBase64", "sourcePath")));

        // upload_workitem_document ordering: upload before assign
        McpToolVO upload = toolFor("autowonder.upload_workitem_document");
        assertTrue(upload.getDescription().contains("assign_workitem"));
        assertTrue(upload.getDescription().contains("first dispatch"));

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
    void clarificationConversationCanUploadConfirmedSpecOrPlanDocuments() {
        ArtifactVO artifact = new ArtifactVO();
        artifact.setId(77L);
        when(requirementDocumentService.uploadMcp(eq(99L), eq("draft.md"), any(byte[].class),
                eq(ORG_ID), eq(USER_ID), isNull())).thenReturn(artifact);
        McpAccessTokenService.Principal clarificationPrincipal = new McpAccessTokenService.Principal(
                ORG_ID, USER_ID, 88L, OrgAccessLevel.READ_WRITE,
                McpAccessTokenService.CredentialType.CONVERSATION);

        assertSame(artifact, call(clarificationPrincipal,
                "autowonder.upload_workitem_document", Map.of("id", 99L, "filename", "draft.md",
                        "contentMd", "# Confirmed spec")));
        verify(requirementDocumentService).uploadMcp(eq(99L), eq("draft.md"),
                argThat(bytes -> "# Confirmed spec".equals(new String(bytes))), eq(ORG_ID), eq(USER_ID), isNull());
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
        assertEquals(List.of("orgId", "fileName", "contentBase64"), uploadSchema.get("required"));
        assertTrue(properties(uploadSchema).keySet().containsAll(List.of(
                "fileName", "contentBase64", "type", "expectedMd5")));

        Map<String, Object> createSchema = schemaFor("autowonder.create_skill_from_package");
        assertEquals(List.of("orgId", "packageOssRef"), createSchema.get("required"));
        assertTrue(properties(createSchema).keySet().containsAll(List.of(
                "packageOssRef", "idempotencyKey", "expectedMd5")));

        Map<String, Object> updateSchema = schemaFor("autowonder.update_skill_package");
        assertEquals(List.of("orgId", "id", "packageOssRef"), updateSchema.get("required"));
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
    void dispatchCredentialCannotCreateSquadOrOrgScopedMemory() {
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
        assertEquals(List.of("orgId", "title"), createSchema.get("required"));
        assertTrue(properties(createSchema).keySet().containsAll(List.of(
                "orgId", "title", "contentMd", "type", "scope", "ownerRef", "idempotencyKey")));

        Map<String, Object> searchSchema = schemaFor("autowonder.search_memories");
        assertEquals(List.of("orgId"), searchSchema.get("required"));
        assertTrue(properties(searchSchema).keySet().containsAll(List.of(
                "orgId", "keyword", "scope", "ownerRef", "type", "status", "page", "size")));

        assertEquals(List.of("orgId", "id"), schemaFor("autowonder.update_memory").get("required"));
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

        assertTrue(toolFor("autowonder.search_memories").getDescription().contains("ADOPTED"));
        assertTrue(toolFor("autowonder.deprecate_memory").getDescription().contains("REJECTED"));
    }

    private McpAccessTokenService.Principal dispatchPrincipal() {
        return new McpAccessTokenService.Principal(
                100L, 7L, -321L, OrgAccessLevel.READ_WRITE,
                McpAccessTokenService.CredentialType.DISPATCH);
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
                principal(OrgAccessLevel.READ_ONLY);

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
        assertEquals(List.of("orgId", "id"), schema.get("required"));
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
                principal(OrgAccessLevel.READ_ONLY);

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
        assertEquals(List.of("orgId", "id"), schema.get("required"));
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
        assertEquals(List.of("orgId", "id"), schema.get("required"));
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
        assertTrue(names.contains("autowonder.get_agent_version_status"));
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
                eq(ORG_ID), eq(USER_ID));
    }

    @Test
    void updateAgentMapsSoulAndAgentMarkdownToStableRequestFields() {
        call(principal, "autowonder.update_agent", Map.of(
                "id", 12L, "soulMd", "new soul", "agentMd", "new agent"));

        verify(agentService).updateAgent(argThat(request ->
                        request.getId() == 12L
                                && "new soul".equals(request.getBusinessBackground())
                                && "new agent".equals(request.getResponsibilities())),
                eq(ORG_ID), eq(USER_ID));
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
                eq(ORG_ID), eq(USER_ID));
        verify(agentService).updateAgent(argThat(request ->
                        "legacy soul".equals(request.getBusinessBackground())
                                && "legacy agent".equals(request.getResponsibilities())),
                eq(ORG_ID), eq(USER_ID));
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
                eq(ORG_ID), eq(USER_ID));
        verify(agentService).updateAgent(argThat(request ->
                        "new soul".equals(request.getBusinessBackground())
                                && "new agent".equals(request.getResponsibilities())),
                eq(ORG_ID), eq(USER_ID));
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
                eq(ORG_ID), eq(USER_ID));
        verify(agentService).updateAgent(argThat(request ->
                        "new soul".equals(request.getBusinessBackground())
                                && request.getResponsibilities() == null),
                eq(ORG_ID), eq(USER_ID));
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
        assertTrue(create.getDescription().contains("\"sdlcId\":40014"));
        // create_workitem ordering: create -> upload -> assign
        assertTrue(create.getDescription().contains("upload_workitem_document"));
        assertTrue(create.getDescription().contains("assign_workitem"));
        assertTrue(create.getDescription().contains("correct order"));

        // assign_workitem: agentId semantics, no status change, and the "reassign an
        // existing workitem to a digital worker" example.
        McpToolVO assign = toolFor("autowonder.assign_workitem");
        assertTrue(assign.getDescription().contains("agentId"));
        assertTrue(assign.getDescription().contains("does NOT change the workitem status node"));
        assertTrue(assign.getDescription().contains("\"id\":10042"));
        assertTrue(assign.getDescription().contains("\"assigneeRef\":40013"));
        assertFalse(assign.getDescription().contains("\"sdlcId\""));
        assertFalse(assign.getDescription().contains("optionally sdlcId"));
        // assign_workitem ordering: documents must be uploaded before assign
        assertTrue(assign.getDescription().contains("upload_workitem_document"));
        assertTrue(assign.getDescription().contains("dispatch scheduling"));

        // list_workitems: business query tool, not for enum discovery, page/size defaults.
        McpToolVO list = toolFor("autowonder.list_workitems");
        assertTrue(list.getDescription().contains("business query"));
        assertTrue(list.getDescription().contains("page=1"));
        assertTrue(list.getDescription().contains("size=20"));

        // list_status_templates: returns templates not SDLC, points to list_sdlcs for sdlcId.
        McpToolVO templates = toolFor("autowonder.list_status_templates");
        assertTrue(templates.getDescription().contains("NOT SDLC"));
        assertTrue(templates.getDescription().contains("list_sdlcs"));
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

        // assign_workitem assigneeRef spells out agentId/userId.
        Map<String, Object> assignAssigneeRef = property(schemaFor("autowonder.assign_workitem"), "assigneeRef");
        assertNotNull(assignAssigneeRef.get("description"));
        assertTrue(((String) assignAssigneeRef.get("description")).contains("agentId"));
        assertFalse(properties(schemaFor("autowonder.assign_workitem")).containsKey("sdlcId"));
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
    void personalTokenReachesEveryOrganizationTheOwnerBelongsTo() {
        WorkitemVO first = new WorkitemVO();
        WorkitemVO second = new WorkitemVO();
        when(orgService.activeAccessLevel(ORG_ID, USER_ID))
                .thenReturn(OrgAccessLevel.READ_ONLY);
        when(orgService.activeAccessLevel(200L, USER_ID))
                .thenReturn(OrgAccessLevel.READ_WRITE);
        when(workitemService.get(11L)).thenReturn(first);
        when(workitemService.get(22L)).thenReturn(second);
        McpAccessTokenService.Principal personal =
                McpAccessTokenService.Principal.personal(USER_ID, 1L);

        assertSame(first, service.call(personal, "autowonder.get_workitem",
                Map.of("orgId", ORG_ID, "id", 11L)));
        assertSame(second, service.call(personal, "autowonder.get_workitem",
                Map.of("orgId", 200L, "id", 22L)));
    }

    @Test
    void personalTokenMustPassOrgIdForOrganizationScopedTools() {
        BizException thrown = assertThrows(BizException.class,
                () -> service.call(McpAccessTokenService.Principal.personal(USER_ID, 1L),
                        "autowonder.get_workitem", Map.of("id", 11L)));

        assertEquals("10001", thrown.getCode());
        verifyNoInteractions(workitemService);
    }

    @Test
    void personalTokenRejectsNonPositiveOrgId() {
        McpAccessTokenService.Principal personal =
                McpAccessTokenService.Principal.personal(USER_ID, 1L);

        for (Object invalid : List.of(0L, -1L)) {
            BizException thrown = assertThrows(BizException.class,
                    () -> service.call(personal, "autowonder.get_workitem",
                            Map.of("orgId", invalid, "id", 11L)));
            assertEquals("10001", thrown.getCode());
        }
        verifyNoInteractions(workitemService);
    }

    @Test
    void personalTokenCannotReachAnOrganizationTheOwnerLeft() {
        when(orgService.activeAccessLevel(200L, USER_ID))
                .thenThrow(new BizException(ErrorCode.ORG_NOT_MEMBER));

        BizException thrown = assertThrows(BizException.class,
                () -> service.call(McpAccessTokenService.Principal.personal(USER_ID, 1L),
                        "autowonder.get_workitem", Map.of("orgId", 200L, "id", 11L)));

        assertEquals("11001", thrown.getCode());
        verifyNoInteractions(workitemService);
    }

    @Test
    void readOnlyMembershipReadsButCannotWriteInTheTargetOrganization() {
        WorkitemVO workitem = new WorkitemVO();
        when(orgService.activeAccessLevel(ORG_ID, USER_ID))
                .thenReturn(OrgAccessLevel.READ_ONLY);
        when(workitemService.get(11L)).thenReturn(workitem);
        McpAccessTokenService.Principal personal =
                McpAccessTokenService.Principal.personal(USER_ID, 1L);

        assertSame(workitem, service.call(personal, "autowonder.get_workitem",
                Map.of("orgId", ORG_ID, "id", 11L)));

        BizException thrown = assertThrows(BizException.class,
                () -> service.call(personal, "autowonder.delete_workitem",
                        Map.of("orgId", ORG_ID, "id", 11L)));
        assertEquals("10403", thrown.getCode());
        verify(workitemService, never()).delete(anyLong(), anyLong(), anyLong());
    }

    @Test
    void membershipIsResolvedOnEveryCallSoDowngradesTakeEffectImmediately() {
        when(orgService.activeAccessLevel(ORG_ID, USER_ID))
                .thenReturn(OrgAccessLevel.READ_WRITE, OrgAccessLevel.READ_ONLY);
        McpAccessTokenService.Principal personal =
                McpAccessTokenService.Principal.personal(USER_ID, 1L);

        service.call(personal, "autowonder.delete_workitem", Map.of("orgId", ORG_ID, "id", 11L));
        assertThrows(BizException.class,
                () -> service.call(personal, "autowonder.delete_workitem",
                        Map.of("orgId", ORG_ID, "id", 11L)));

        verify(orgService, times(2)).activeAccessLevel(ORG_ID, USER_ID);
    }

    @Test
    void taskScopedTokenAcceptsOmittedOrMatchingOrgIdButRejectsAnother() {
        WorkitemVO workitem = new WorkitemVO();
        when(workitemService.get(11L)).thenReturn(workitem);
        McpAccessTokenService.Principal scoped = scopedPrincipal(OrgAccessLevel.READ_WRITE);

        assertSame(workitem, service.call(scoped, "autowonder.get_workitem", Map.of("id", 11L)));
        assertSame(workitem, service.call(scoped, "autowonder.get_workitem",
                Map.of("orgId", ORG_ID, "id", 11L)));

        BizException thrown = assertThrows(BizException.class,
                () -> service.call(scoped, "autowonder.get_workitem",
                        Map.of("orgId", 200L, "id", 11L)));
        assertEquals("10403", thrown.getCode());
        verifyNoInteractions(orgService);
    }

    @Test
    void dispatchTokenCannotCrossIntoAnotherOrganization() {
        McpAccessTokenService.Principal dispatchPrincipal = dispatchPrincipal(-321L);

        BizException thrown = assertThrows(BizException.class,
                () -> service.call(dispatchPrincipal, "autowonder.get_workitem",
                        Map.of("orgId", 200L, "id", 11L)));

        assertEquals("10403", thrown.getCode());
        verifyNoInteractions(orgService);
        verifyNoInteractions(workitemService);
    }

    @Test
    void ambientOrganizationContextIsRestoredAfterEveryCall() {
        AutoWonderContext ambient = AutoWonderContext.get();
        ambient.setCurrentOrgId(900L);
        ambient.setOrgAccessLevel(OrgAccessLevel.READ_ONLY);
        when(orgService.activeAccessLevel(ORG_ID, USER_ID)).thenReturn(OrgAccessLevel.ADMIN);
        try {
            service.call(McpAccessTokenService.Principal.personal(USER_ID, 1L),
                    "autowonder.delete_workitem", Map.of("orgId", ORG_ID, "id", 11L));

            assertEquals(900L, ambient.getCurrentOrgId());
            assertEquals(OrgAccessLevel.READ_ONLY, ambient.getOrgAccessLevel());
        } finally {
            AutoWonderContext.destroy();
        }
    }

    @Test
    void organizationScopedToolsAllRequireOrgIdWhileGlobalToolsDoNot() {
        Set<String> globalTools = Set.of(
                "autowonder.list_projects",
                "autowonder.inspect_skill_package",
                "autowonder.list_platform_skills");

        for (McpToolVO tool : service.listTools()) {
            Object required = tool.getInputSchema().get("required");
            boolean requiresOrgId = required instanceof List<?> names
                    && names.contains("orgId");
            if (globalTools.contains(tool.getName())) {
                assertFalse(requiresOrgId, tool.getName() + " must not require orgId");
                assertFalse(properties(tool.getInputSchema()).containsKey("orgId"),
                        tool.getName() + " must not declare orgId");
            } else {
                assertTrue(requiresOrgId, tool.getName() + " must require orgId");
                assertEquals("integer",
                        ((Map<?, ?>) properties(tool.getInputSchema()).get("orgId")).get("type"),
                        tool.getName() + " orgId must be an integer");
            }
        }
    }

    @Test
    void personalTokenQueriesOrgsOnceAndGeneratesDifferentDescriptions() {
        OrgVO orgA = new OrgVO();
        orgA.setId(10002L);
        orgA.setName("AutoWonder自迭代");
        orgA.setAccessLevel(OrgAccessLevel.READ_ONLY);

        OrgVO orgB = new OrgVO();
        orgB.setId(10003L);
        orgB.setName("AutoWonder产研项目组");
        orgB.setAccessLevel(OrgAccessLevel.READ_WRITE);

        when(orgService.listByUserWithAccess(USER_ID)).thenReturn(List.of(orgA, orgB));

        McpAccessTokenService.Principal personal = McpAccessTokenService.Principal.personal(USER_ID, 1L);
        List<McpToolVO> tools = service.listTools(personal);

        verify(orgService, times(1)).listByUserWithAccess(USER_ID);

        String expectedRead = "Org: 10002=AutoWonder自迭代;10003=AutoWonder产研项目组";
        String expectedWrite = "Org: 10003=AutoWonder产研项目组";

        for (McpToolVO tool : tools) {
            Map<String, Object> props = properties(tool.getInputSchema());
            if (props != null && props.containsKey("orgId")) {
                Map<String, Object> orgIdProp = (Map<String, Object>) props.get("orgId");
                String desc = (String) orgIdProp.get("description");
                String toolName = tool.getName();
                McpToolService toolServiceSpy = service;
                // determine if this is a read-only or write tool
                // read-only tools get the full (read) description, write tools get the write description
                assertNotNull(desc, toolName + " must have an orgId description");
                assertTrue(desc.startsWith("Org:"), toolName + " description must start with 'Org:'");
                assertTrue(desc.equals(expectedRead) || desc.equals(expectedWrite),
                        toolName + " has unexpected description: " + desc);
            }
        }

        // verify list_projects (global) has no orgId
        McpToolVO listProjects = tools.stream()
                .filter(t -> "autowonder.list_projects".equals(t.getName()))
                .findFirst().orElseThrow();
        assertFalse(properties(listProjects.getInputSchema()).containsKey("orgId"),
                "list_projects must not have orgId");
    }

    @Test
    void taskScopedTokenShowsOnlyBoundOrg() {
        OrgVO scoped = new OrgVO();
        scoped.setId(ORG_ID);
        scoped.setName("TestOrg");
        when(orgService.getCurrent(ORG_ID)).thenReturn(scoped);

        McpAccessTokenService.Principal conversation = scopedPrincipal(OrgAccessLevel.READ_WRITE);
        List<McpToolVO> tools = service.listTools(conversation);

        verify(orgService, never()).listByUserWithAccess(anyLong());

        String expected = "Org: " + ORG_ID + "=TestOrg";
        for (McpToolVO tool : tools) {
            Map<String, Object> props = properties(tool.getInputSchema());
            if (props != null && props.containsKey("orgId")) {
                Map<String, Object> orgIdProp = (Map<String, Object>) props.get("orgId");
                assertEquals(expected, orgIdProp.get("description"),
                        tool.getName() + " must show only the bound org");
            }
        }
    }

    @Test
    void globalToolsUnchangedAfterDescriptionInjection() {
        when(orgService.listByUserWithAccess(USER_ID)).thenReturn(List.of());

        McpAccessTokenService.Principal personal = McpAccessTokenService.Principal.personal(USER_ID, 1L);
        List<McpToolVO> tools = service.listTools(personal);

        Set<String> globalToolNames = Set.of(
                "autowonder.list_projects",
                "autowonder.inspect_skill_package",
                "autowonder.list_platform_skills");

        for (McpToolVO tool : tools) {
            if (globalToolNames.contains(tool.getName())) {
                Map<String, Object> props = properties(tool.getInputSchema());
                assertFalse(props.containsKey("orgId"),
                        tool.getName() + " must not gain orgId after injection");
            }
        }
    }

    @Test
    void orgIdRemainsIntegerAndRequiredOrderUnchanged() {
        OrgVO org = new OrgVO();
        org.setId(100L);
        org.setName("Org");
        org.setAccessLevel(OrgAccessLevel.READ_WRITE);
        when(orgService.listByUserWithAccess(USER_ID)).thenReturn(List.of(org));

        McpAccessTokenService.Principal personal = McpAccessTokenService.Principal.personal(USER_ID, 1L);
        List<McpToolVO> tools = service.listTools(personal);

        for (McpToolVO tool : tools) {
            Map<String, Object> props = properties(tool.getInputSchema());
            if (props != null && props.containsKey("orgId")) {
                Map<String, Object> orgIdProp = (Map<String, Object>) props.get("orgId");
                assertEquals("integer", orgIdProp.get("type"),
                        tool.getName() + " orgId must remain integer type");

                @SuppressWarnings("unchecked")
                List<String> required = (List<String>) tool.getInputSchema().get("required");
                assertEquals("orgId", required.get(0),
                        tool.getName() + " orgId must be first in required list");
            }
        }
    }

    @Test
    void emptyOrgsProducesNoDescriptionChangeForPersonalToken() {
        when(orgService.listByUserWithAccess(USER_ID)).thenReturn(List.of());

        McpAccessTokenService.Principal personal = McpAccessTokenService.Principal.personal(USER_ID, 1L);
        List<McpToolVO> tools = service.listTools(personal);

        // When there are no orgs, the original static description should remain
        for (McpToolVO tool : tools) {
            Map<String, Object> props = properties(tool.getInputSchema());
            if (props != null && props.containsKey("orgId")) {
                Map<String, Object> orgIdProp = (Map<String, Object>) props.get("orgId");
                String desc = (String) orgIdProp.get("description");
                // Should still have the original static description
                assertTrue(desc.contains("autowonder.list_projects"),
                        tool.getName() + " should retain original description when no orgs");
            }
        }
    }

    @Test
    void compactDescriptionSortsByOrgIdAscending() {
        OrgVO org3 = new OrgVO();
        org3.setId(300L);
        org3.setName("Zeta");
        org3.setAccessLevel(OrgAccessLevel.READ_WRITE);

        OrgVO org1 = new OrgVO();
        org1.setId(100L);
        org1.setName("Alpha");
        org1.setAccessLevel(OrgAccessLevel.READ_WRITE);

        OrgVO org2 = new OrgVO();
        org2.setId(200L);
        org2.setName("中文组织");
        org2.setAccessLevel(OrgAccessLevel.READ_ONLY);

        when(orgService.listByUserWithAccess(USER_ID)).thenReturn(List.of(org3, org1, org2));

        McpAccessTokenService.Principal personal = McpAccessTokenService.Principal.personal(USER_ID, 1L);
        List<McpToolVO> tools = service.listTools(personal);

        // Find a read-only tool to check the full description
        McpToolVO readTool = tools.stream()
                .filter(t -> "autowonder.list_workitems".equals(t.getName()))
                .findFirst().orElseThrow();
        Map<String, Object> props = properties(readTool.getInputSchema());
        Map<String, Object> orgIdProp = (Map<String, Object>) props.get("orgId");
        String desc = (String) orgIdProp.get("description");

        // Should be sorted by orgId ascending: 100, 200, 300
        assertEquals("Org: 100=Alpha;200=中文组织;300=Zeta", desc);

        // Find a write tool to check the write-only description
        McpToolVO writeTool = tools.stream()
                .filter(t -> "autowonder.create_workitem".equals(t.getName()))
                .findFirst().orElseThrow();
        props = properties(writeTool.getInputSchema());
        orgIdProp = (Map<String, Object>) props.get("orgId");
        desc = (String) orgIdProp.get("description");

        // Only READ_WRITE and ADMIN orgs: 100(READ_WRITE) and 300(READ_WRITE)
        assertEquals("Org: 100=Alpha;300=Zeta", desc);
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
    private McpAccessTokenService.Principal principal(OrgAccessLevel accessLevel) {
        when(orgService.activeAccessLevel(ORG_ID, USER_ID)).thenReturn(accessLevel);
        return McpAccessTokenService.Principal.personal(USER_ID, 1L);
    }

    private McpAccessTokenService.Principal dispatchPrincipal(long tokenId) {
        return new McpAccessTokenService.Principal(
                ORG_ID, USER_ID, tokenId, OrgAccessLevel.READ_WRITE,
                McpAccessTokenService.CredentialType.DISPATCH);
    }

    private McpAccessTokenService.Principal scopedPrincipal(OrgAccessLevel accessLevel) {
        return new McpAccessTokenService.Principal(
                ORG_ID, USER_ID, 1L, accessLevel,
                McpAccessTokenService.CredentialType.CONVERSATION);
    }

    private Object call(McpAccessTokenService.Principal caller, String tool,
                        Map<String, Object> args) {
        return service.call(caller, tool, withOrgId(args));
    }

    private Map<String, Object> withOrgId(Map<String, Object> args) {
        if (args.containsKey("orgId")) {
            return args;
        }
        Map<String, Object> merged = new java.util.LinkedHashMap<>(args);
        merged.put("orgId", ORG_ID);
        return merged;
    }
}
