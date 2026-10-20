package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.mcp.dto.McpRpcResponse;
import com.aliyun.autowonder.mcp.dto.McpToolVO;
import com.aliyun.autowonder.memory.store.MemoryStoreApplicationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class McpControllerTest {

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void optionalToolOutputSchemaIsOmittedRatherThanSerializedAsNull() throws Exception {
        var tool = new com.aliyun.autowonder.mcp.dto.McpToolVO("autowonder.propose_platform_actions", "Propose", Map.of("type", "object"));
        var json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(Map.of("tools", List.of(tool))));
        org.junit.jupiter.api.Assertions.assertFalse(json.get("tools").get(0).has("outputSchema"));
        tool.setOutputSchema(Map.of("type", "object"));
        json = new ObjectMapper().valueToTree(tool);
        org.junit.jupiter.api.Assertions.assertEquals("object", json.get("outputSchema").get("type").asText());
    }

    @Test
    void rpcMappingSupportsRootMcpUrlForQueryTokenClients() throws Exception {
        Method method = McpController.class.getMethod("rpc",
                String.class, String.class, String.class, String.class, Map.class);
        PostMapping mapping = method.getAnnotation(PostMapping.class);

        assertNotNull(mapping);
        List<String> patterns = List.of(mapping.value());
        assertTrue(patterns.contains(""));
        assertTrue(patterns.contains("/rpc"));
        assertTrue(patterns.contains("/{pathToken}"));
        assertTrue(patterns.contains("/{pathToken}/"));
        assertTrue(patterns.contains("/{pathToken}/rpc"));
        assertTrue(patterns.contains("/{pathToken}/rpc/"));
    }

    @Test
    void streamableHttpGetAuthenticatesAndStartsEventStream() throws Exception {
        McpAccessTokenService tokens = mock(McpAccessTokenService.class);
        when(tokens.authenticate("Bearer test", null)).thenReturn(principal(WorkspaceAccessLevel.READ_ONLY));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new McpController(tokens, mock(McpToolService.class), new McpSseService(mock(org.springframework.scheduling.TaskScheduler.class)))).build();
        mvc.perform(get("/api/mcp")
                        .header("Authorization", "Bearer test")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM));
        verify(tokens).authenticate("Bearer test", null);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"zh-CN", "en-US"})
    void streamableHttpGetInvalidCredentialsReturnHttp401BeforeStreaming(String language) throws Exception {
        McpAccessTokenService tokens = mock(McpAccessTokenService.class);
        when(tokens.authenticate(null, "invalid")).thenThrow(new BizException(ErrorCode.UNAUTHORIZED));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new McpController(tokens, mock(McpToolService.class), new McpSseService(mock(org.springframework.scheduling.TaskScheduler.class)))).build();
        mvc.perform(get("/api/mcp").queryParam("token", "invalid").header("Accept-Language", language).accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("10401")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(language.equals("en-US")
                        ? "Not signed in or session expired" : "未登录或登录已失效")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncNotStarted());
    }

    @Test
    void streamableHttpGetRejectsForeignOrigin() throws Exception {
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new McpController(
                mock(McpAccessTokenService.class), mock(McpToolService.class), mock(McpSseService.class))).build();
        mvc.perform(get("/api/mcp").header("Origin", "https://foreign.example").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isForbidden());
    }

    @Test
    void streamableHttpGetSupportsAllPathsAndExistingCredentialPrecedence() throws Exception {
        McpAccessTokenService tokens = mock(McpAccessTokenService.class);
        when(tokens.authenticate("Bearer ignored", "path-token")).thenReturn(principal(WorkspaceAccessLevel.READ_ONLY));
        when(tokens.authenticate("Bearer ignored", "query-token")).thenReturn(principal(WorkspaceAccessLevel.READ_ONLY));
        McpSseService streams = new McpSseService(mock(org.springframework.scheduling.TaskScheduler.class));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new McpController(tokens, mock(McpToolService.class), streams)).build();
        try {
            for (String path : List.of("/api/mcp", "/api/mcp/rpc", "/api/mcp/path-token", "/api/mcp/path-token/",
                    "/api/mcp/path-token/rpc", "/api/mcp/path-token/rpc/")) {
                mvc.perform(get(path).queryParam("token", "query-token").header("Authorization", "Bearer ignored")
                                .header("Origin", "http://localhost").accept(MediaType.TEXT_EVENT_STREAM))
                        .andExpect(status().isOk())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted())
                        .andExpect(content().string(":connected\n\n"));
            }
            verify(tokens, times(2)).authenticate("Bearer ignored", "query-token");
            verify(tokens, times(4)).authenticate("Bearer ignored", "path-token");
        } finally {
            streams.shutdown();
        }
    }

    @Test
    void streamableHttpGetMissingCredentialsNeverStartsAsync() throws Exception {
        McpAccessTokenService tokens = mock(McpAccessTokenService.class);
        when(tokens.authenticate(null, null)).thenThrow(new BizException(ErrorCode.UNAUTHORIZED));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new McpController(tokens, mock(McpToolService.class), mock(McpSseService.class))).build();
        mvc.perform(get("/api/mcp").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncNotStarted());
    }

    @Test
    void rpcAuthenticatesWithQueryToken() {
        McpAccessTokenService tokenService = mock(McpAccessTokenService.class);
        McpToolService toolService = mock(McpToolService.class);
        McpAccessTokenService.Principal principal =
                principal(WorkspaceAccessLevel.READ_ONLY);
        when(tokenService.authenticate(null, "awmcp_query_token")).thenReturn(principal);
        when(toolService.listTools(principal)).thenReturn(List.of());

        McpRpcResponse response = new McpController(tokenService, toolService, mock(McpSseService.class))
                .rpc(null, "awmcp_query_token", Map.of("id", 1, "method", "tools/list"));

        assertNull(response.getError());
        assertEquals(1, response.getId());
        assertNotNull(response.getResult());
        verify(tokenService).authenticate(null, "awmcp_query_token");
        verify(toolService).listTools(principal);
    }

    @Test
    void rpcErrorSerializesWithoutResultMember() throws Exception {
        McpAccessTokenService tokenService = mock(McpAccessTokenService.class);
        McpToolService toolService = mock(McpToolService.class);
        when(tokenService.authenticate(null, "awmcp_query_token"))
                .thenThrow(new BizException(ErrorCode.SDLC_NOT_DRAFT));

        McpRpcResponse response = new McpController(tokenService, toolService, mock(McpSseService.class))
                .rpc(null, "awmcp_query_token", Map.of("id", 1, "method", "tools/list"));
        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(response));

        assertTrue(json.has("error"));
        assertEquals("流程非草稿状态,无法编辑结构", json.path("error").path("message").asText());
        assertTrue(!json.has("result"));
    }

    @Test
    void rpcReturnsStableRetryableErrorForMemoryMaintenanceLease() {
        McpAccessTokenService tokenService = mock(McpAccessTokenService.class);
        McpToolService toolService = mock(McpToolService.class);
        McpAccessTokenService.Principal principal = principal(WorkspaceAccessLevel.READ_WRITE);
        when(tokenService.authenticate(null, "awmcp_query_token")).thenReturn(principal);
        when(toolService.call(principal, "autowonder.create_memory", Map.of()))
                .thenThrow(new MemoryStoreApplicationService.MemoryMaintenanceLeaseConflictException());

        McpRpcResponse response = new McpController(tokenService, toolService, mock(McpSseService.class)).rpc(null, "awmcp_query_token", Map.of(
                "id", 1, "method", "tools/call",
                "params", Map.of("name", "autowonder.create_memory", "arguments", Map.of())));

        assertEquals(-32009, response.getError().getCode());
        assertEquals("MEMORY_MAINTENANCE_LOCKED: retry later", response.getError().getMessage());
    }

    @Test
    void rpcSuccessSerializesWithoutErrorMember() throws Exception {
        McpAccessTokenService tokenService = mock(McpAccessTokenService.class);
        McpToolService toolService = mock(McpToolService.class);
        McpAccessTokenService.Principal principal =
                principal(WorkspaceAccessLevel.READ_ONLY);
        when(tokenService.authenticate(null, "awmcp_query_token")).thenReturn(principal);
        when(toolService.listTools(principal)).thenReturn(List.of());

        McpRpcResponse response = new McpController(tokenService, toolService, mock(McpSseService.class))
                .rpc(null, "awmcp_query_token", Map.of("id", 1, "method", "tools/list"));
        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(response));

        assertTrue(json.has("result"));
        assertTrue(!json.has("error"));
    }

    @Test
    void rpcWrapsListToolResultsInStructuredContentObject() {
        McpAccessTokenService tokenService = mock(McpAccessTokenService.class);
        McpToolService toolService = mock(McpToolService.class);
        McpAccessTokenService.Principal principal =
                principal(WorkspaceAccessLevel.READ_ONLY);
        when(tokenService.authenticate(null, "awmcp_query_token")).thenReturn(principal);
        when(toolService.call(principal, "autowonder.list_projects", Map.of()))
                .thenReturn(List.of(Map.of("id", 100L)));

        McpRpcResponse response = new McpController(tokenService, toolService, mock(McpSseService.class)).rpc(null, "awmcp_query_token", Map.of(
                "id", 1,
                "method", "tools/call",
                "params", Map.of("name", "autowonder.list_projects", "arguments", Map.of())));

        @SuppressWarnings("unchecked")
        Map<String, Object> callResult = (Map<String, Object>) response.getResult();
        @SuppressWarnings("unchecked")
        Map<String, Object> structuredContent = (Map<String, Object>) callResult.get("structuredContent");
        assertEquals(List.of(Map.of("id", 100L)), structuredContent.get("items"));
    }

    @Test
    void rpcSupportsEventStreamOnlyAcceptHeaderUsedByClaudeClients() throws Exception {
        McpAccessTokenService tokenService = mock(McpAccessTokenService.class);
        McpToolService toolService = mock(McpToolService.class);
        McpAccessTokenService.Principal principal =
                principal(WorkspaceAccessLevel.READ_ONLY);
        when(tokenService.authenticate(null, "awmcp_query_token")).thenReturn(principal);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new McpController(tokenService, toolService, mock(McpSseService.class))).build();

        mvc.perform(post("/api/mcp")
                        .queryParam("token", "awmcp_query_token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith("data:")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"jsonrpc\":\"2.0\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"result\":")));
    }

    @Test
    void rpcAcknowledgesInitializedNotificationWithoutResponseBody() throws Exception {
        McpAccessTokenService tokenService = mock(McpAccessTokenService.class);
        McpToolService toolService = mock(McpToolService.class);
        McpAccessTokenService.Principal principal =
                principal(WorkspaceAccessLevel.READ_ONLY);
        when(tokenService.authenticate(null, "awmcp_query_token")).thenReturn(principal);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new McpController(tokenService, toolService, mock(McpSseService.class))).build();

        mvc.perform(post("/api/mcp")
                        .queryParam("token", "awmcp_query_token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
    }

    @Test
    void rpcAuthenticatesWithTokenInUrlPath() throws Exception {
        McpAccessTokenService tokenService = mock(McpAccessTokenService.class);
        McpToolService toolService = mock(McpToolService.class);
        McpAccessTokenService.Principal principal =
                principal(WorkspaceAccessLevel.READ_ONLY);
        when(tokenService.authenticate(null, "awmcp_path_token")).thenReturn(principal);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new McpController(tokenService, toolService, mock(McpSseService.class))).build();

        mvc.perform(post("/api/mcp/awmcp_path_token/")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"result\":")));

        verify(tokenService).authenticate(null, "awmcp_path_token");
    }

    @Test
    void rpcUrlPathTokenTakesPriorityOverLegacyQueryToken() throws Exception {
        McpAccessTokenService tokenService = mock(McpAccessTokenService.class);
        McpToolService toolService = mock(McpToolService.class);
        McpAccessTokenService.Principal principal =
                principal(WorkspaceAccessLevel.READ_ONLY);
        when(tokenService.authenticate(null, "awmcp_path_token")).thenReturn(principal);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new McpController(tokenService, toolService, mock(McpSseService.class))).build();

        mvc.perform(post("/api/mcp/awmcp_path_token/rpc")
                        .queryParam("token", "awmcp_query_token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}"))
                .andExpect(status().isOk());

        verify(tokenService).authenticate(null, "awmcp_path_token");
    }

    @Test
    void directAndRpcToolListsUseEffectiveLevelInsideContext() {
        McpAccessTokenService tokenService = mock(McpAccessTokenService.class);
        McpToolService toolService = mock(McpToolService.class);
        McpAccessTokenService.Principal principal =
                principal(WorkspaceAccessLevel.READ_ONLY);
        McpToolVO visible = new McpToolVO();
        visible.setName("autowonder.list_projects");
        when(tokenService.authenticate(null, "awmcp_query_token"))
                .thenReturn(principal);
        when(toolService.listTools(principal)).thenAnswer(invocation -> {
            assertEquals(100L, AutoWonderContext.get().getCurrentWorkspaceId());
            assertEquals(7L, AutoWonderContext.get().getUserId());
            assertEquals(WorkspaceAccessLevel.READ_ONLY,
                    AutoWonderContext.get().getWorkspaceAccessLevel());
            return List.of(visible);
        });
        McpController controller = new McpController(tokenService, toolService, mock(McpSseService.class));

        var direct = controller.listTools(null, "awmcp_query_token");
        McpRpcResponse rpc = controller.rpc(
                null, "awmcp_query_token",
                Map.of("id", 2, "method", "tools/list"));

        assertEquals(List.of(visible), direct.getData());
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) rpc.getResult();
        assertEquals(List.of(visible), result.get("tools"));
        verify(toolService, times(2)).listTools(principal);
    }

    private McpAccessTokenService.Principal principal(
            WorkspaceAccessLevel accessLevel) {
        return new McpAccessTokenService.Principal(
                100L, 7L, 1L, accessLevel,
                McpAccessTokenService.CredentialType.CONVERSATION);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"zh-CN", "en-US"})
    void localizedBusinessRpcErrorsPreserveRawIntentAndProtocol(String language) throws Exception {
        var locale = java.util.Locale.forLanguageTag(language);
        boolean english = language.equals("en-US");
        Throwable cause = new IllegalStateException("raw cause <秘密>");
        BizException[] errors = {
            new BizException(ErrorCode.ARTIFACT_NOT_FOUND, cause),
            BizException.keyed(ErrorCode.PARAM_INVALID, "executor.launch.version_required",
                    new Object[]{"<rawTool>"}, "version 原始诊断", cause),
            new BizException(ErrorCode.ARTIFACT_NOT_FOUND, "产物不存在"),
            BizException.keyed("opaque-code", "missing.owned.key", null, "<raw unknown>产物不存在", cause)
        };
        String[] expected = {english ? "Artifact not found" : "产物不存在",
            english ? "version is required. Read it from <rawTool>." : "version 必填，取自 <rawTool>",
            "产物不存在", "<raw unknown>产物不存在"};
        org.springframework.context.i18n.LocaleContextHolder.setLocale(locale);
        try {
            for (int i = 0; i < errors.length; i++) {
                var token = mock(McpAccessTokenService.class);
                when(token.authenticate(null, "raw-token")).thenThrow(errors[i]);
                var response = new McpController(token, mock(McpToolService.class), mock(McpSseService.class))
                        .rpc(null, "raw-token", Map.of("id", "raw-id", "method", "tools/list"));
                assertEquals(-32000, response.getError().getCode());
                assertEquals(expected[i], response.getError().getMessage());
                assertEquals("raw-id", response.getId());
                var json = new ObjectMapper().valueToTree(response);
                assertEquals("2.0", json.path("jsonrpc").asText());
                assertTrue(!json.has("result"));
                assertEquals(i == 1 ? "version 原始诊断" : i == 3 ? "<raw unknown>产物不存在" : "产物不存在", errors[i].getMessage());
                if (i != 2) org.junit.jupiter.api.Assertions.assertSame(cause, errors[i].getCause());
            }
        } finally { org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"zh-CN", "en-US"})
    void businessErrorsUseRequestLocaleInHttpJsonAndSseWhileProtocolErrorsStayRaw(String language) throws Exception {
        var token=mock(McpAccessTokenService.class);
        var tools=mock(McpToolService.class);
        var principal=principal(WorkspaceAccessLevel.READ_WRITE);
        when(token.authenticate(null,"raw-token")).thenReturn(principal);
        when(tools.call(principal,"owned",Map.of())).thenThrow(new BizException(ErrorCode.ARTIFACT_NOT_FOUND));
        var controller=new McpController(token,tools,mock(McpSseService.class));
        // Match Boot's installed String converter; standalone MVC otherwise uses ISO-8859-1 for SSE.
        var stringConverter=new org.springframework.http.converter.StringHttpMessageConverter(
                new org.springframework.boot.web.servlet.server.Encoding().getCharset());
        stringConverter.setWriteAcceptCharset(false);
        var mvc=MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(stringConverter, new org.springframework.http.converter.json.MappingJackson2HttpMessageConverter())
                .setLocaleResolver(new com.aliyun.autowonder.auth.filter.AuthFilterConfig().localeResolver()).build();
        String request="{\"jsonrpc\":\"2.0\",\"id\":\"raw-id\",\"method\":\"tools/call\",\"params\":{\"name\":\"owned\",\"arguments\":{}}}";
        for(var accept:List.of(MediaType.APPLICATION_JSON,MediaType.TEXT_EVENT_STREAM)) {
            String body=mvc.perform(post("/api/mcp").queryParam("token","raw-token")
                    .header("Accept-Language",language).contentType(MediaType.APPLICATION_JSON).accept(accept).content(request))
                    .andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith(accept))
                    .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            var json=new ObjectMapper().readTree(body.startsWith("data:") ? body.substring(5).trim() : body);
            assertEquals(language.equals("en-US") ? "Artifact not found" : "产物不存在",json.path("error").path("message").asText());
            assertEquals(-32000,json.path("error").path("code").asInt());assertEquals("raw-id",json.path("id").asText());
            assertTrue(!json.has("result"));
            assertNull(AutoWonderContext.get().getCurrentWorkspaceId());
            assertNull(AutoWonderContext.get().getUserId());
        }
        org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.forLanguageTag(language));
        try {
            var unknown=controller.rpc(null,"raw-token",Map.of("id",1,"method","unknown"));
            assertEquals(-32601,unknown.getError().getCode());assertEquals("Method not found",unknown.getError().getMessage());
            org.mockito.Mockito.doThrow(new IllegalStateException("<raw>产物不存在")).when(tools).call(principal,"owned",Map.of());
            var raw=controller.rpc(null,"raw-token",Map.of("id",1,"method","tools/call","params",Map.of("name","owned")));
            assertEquals(-32603,raw.getError().getCode());assertEquals("<raw>产物不存在",raw.getError().getMessage());
            var validation=org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                    () -> com.aliyun.autowonder.memory.store.MemoryPathValidator.requireSafe("../raw.md"));
            org.mockito.Mockito.doThrow(validation).when(tools).call(principal,"owned",Map.of());
            var memoryRaw=controller.rpc(null,"raw-token",Map.of("id",1,"method","tools/call","params",Map.of("name","owned")));
            assertEquals(-32603,memoryRaw.getError().getCode());
            assertEquals("unsafe memory path: empty or traversal segments are not allowed",memoryRaw.getError().getMessage());
            assertEquals(1,memoryRaw.getId());
            org.mockito.Mockito.doThrow(new MemoryStoreApplicationService.MemoryMaintenanceLeaseConflictException()).when(tools).call(principal,"owned",Map.of());
            var retry=controller.rpc(null,"raw-token",Map.of("id",1,"method","tools/call","params",Map.of("name","owned")));
            assertEquals(-32009,retry.getError().getCode());assertEquals("MEMORY_MAINTENANCE_LOCKED: retry later",retry.getError().getMessage());
        } finally {org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();}
    }


    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"zh","en"})
    void currentCategoryBatchHttpAndRpcKeepSuccessfulAggregateAndPerItemShape(String language) throws Exception {
        var skills=mock(com.aliyun.autowonder.skill.SkillDao.class);
        var refs=mock(com.aliyun.autowonder.category.SkillCategoryRefDao.class);
        var audit=mock(com.aliyun.autowonder.audit.AuditLogService.class);
        var categories=mock(com.aliyun.autowonder.category.CategoryDao.class);
        var actual=new com.aliyun.autowonder.category.CategoryService(categories,refs,skills,audit);
        var skill=new com.aliyun.autowonder.skill.SkillDO();skill.setId(11L);skill.setTenantId(100L);
        when(skills.findByIdForUpdate(11L)).thenReturn(skill);
        when(skills.findByIdForUpdate(13L)).thenThrow(new BizException(ErrorCode.SKILL_NOT_FOUND,"技能不存在<b>{0}</b>"));
        var row=new com.aliyun.autowonder.category.CategoryDO();row.setId(9L);row.setTenantId(100L);row.setName("已设置分类<b>{0}</b>");
        when(categories.findByIdAndTenantForUpdate(9L,100L)).thenReturn(row);
        when(categories.listByTenant(100L)).thenReturn(List.of(row));
        var token=mock(McpAccessTokenService.class);var tools=mock(McpToolService.class);var user=principal(WorkspaceAccessLevel.READ_WRITE);
        when(token.authenticate(null,"token")).thenReturn(user);
        when(tools.call(org.mockito.ArgumentMatchers.eq(user),org.mockito.ArgumentMatchers.eq("autowonder.batch_set_skill_category"),org.mockito.ArgumentMatchers.anyMap()))
            .thenAnswer(inv -> actual.batchSetSkillCategory(List.of(11L,12L,13L,11L),9L,100L,7L));
        var mapper=new ObjectMapper();
        org.springframework.context.i18n.LocaleContextHolder.setLocale(language.equals("en")?java.util.Locale.US:java.util.Locale.CHINA);
        try {
            var rpc=new McpController(token,tools,mock(McpSseService.class)).rpc(null,"token",null,"application/json",Map.of("id","raw-id","method","tools/call",
                "params",Map.of("name","autowonder.batch_set_skill_category","arguments",Map.of("skillIds",List.of(11L,12L,13L),"categoryId",9L))));
            assertEquals(200,rpc.getStatusCodeValue());var rpcJson=mapper.readTree(mapper.writeValueAsString(rpc.getBody()));
            assertEquals("2.0",rpcJson.path("jsonrpc").asText());assertEquals("raw-id",rpcJson.path("id").asText());assertTrue(!rpcJson.has("error"));
            var result=rpcJson.path("result");assertTrue(!result.path("isError").asBoolean());
            var items=result.path("structuredContent").path("items");assertEquals(3,items.size());assertTrue(items.get(0).path("success").asBoolean());
            assertTrue(!items.get(1).path("success").asBoolean());assertEquals("技能不存在<b>{0}</b>",items.get(2).path("message").asText());
            assertEquals(language.equals("en")?"Category assigned":"已设置分类",items.get(0).path("message").asText());
            assertEquals(language.equals("en")?"Skill not found":"技能不存在",items.get(1).path("message").asText());
            assertEquals(result.path("structuredContent"),mapper.readTree(result.path("content").get(0).path("text").asText()));
            AutoWonderContext.get().setCurrentWorkspaceId(100L);AutoWonderContext.get().setUserId(7L);
            AutoWonderContext.get().setRequestId("raw-request");AutoWonderContext.get().setTraceId("raw-trace");
            var controller=new com.aliyun.autowonder.skill.SkillController(null,null,null,actual);
            var mvc=MockMvcBuilders.standaloneSetup(controller).build();
            var json=mapper.readTree(mvc.perform(post("/api/skills/category/batch").locale(language.equals("en")?java.util.Locale.US:java.util.Locale.CHINA).contentType(MediaType.APPLICATION_JSON)
                .content("{\"skillIds\":[11,12,13,11],\"categoryId\":9}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
            assertTrue(json.path("success").asBoolean());assertEquals("0",json.path("code").asText());assertEquals("",json.path("message").asText());
            assertEquals("raw-request",json.path("request_id").asText());assertEquals("raw-trace",json.path("traceId").asText());assertEquals(items,json.path("data"));
            var captor=org.mockito.ArgumentCaptor.forClass(com.aliyun.autowonder.audit.AuditLogRecord.class);
            verify(audit,times(2)).record(captor.capture());
            for(var record:captor.getAllValues()) {assertEquals("skill.set_category",record.getAction());assertEquals("API",record.getTriggerType());assertEquals("HUMAN",record.getActorType());assertEquals(9L,record.getDetail().get("categoryAfter"));assertNull(record.getDetail().get("categoryBefore"));}
            assertEquals("已设置分类<b>{0}</b>",row.getName());
        } finally {org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext();}
    }
}
