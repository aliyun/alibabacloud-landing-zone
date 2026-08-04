package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.mcp.dto.McpRpcResponse;
import com.aliyun.autowonder.mcp.dto.McpToolVO;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class McpControllerTest {

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void rpcMappingSupportsRootMcpUrlForQueryTokenClients() throws Exception {
        Method method = McpController.class.getMethod("rpc", String.class, String.class, String.class, Map.class);
        PostMapping mapping = method.getAnnotation(PostMapping.class);

        assertNotNull(mapping);
        assertTrue(List.of(mapping.value()).contains(""));
        assertTrue(List.of(mapping.value()).contains("/rpc"));
    }

    @Test
    void rpcAuthenticatesWithQueryToken() {
        McpAccessTokenService tokenService = mock(McpAccessTokenService.class);
        McpToolService toolService = mock(McpToolService.class);
        McpAccessTokenService.Principal principal =
                principal(OrgAccessLevel.READ_ONLY);
        when(tokenService.authenticate(null, "awmcp_query_token")).thenReturn(principal);
        when(toolService.listTools(principal)).thenReturn(List.of());

        McpRpcResponse response = new McpController(tokenService, toolService)
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

        McpRpcResponse response = new McpController(tokenService, toolService)
                .rpc(null, "awmcp_query_token", Map.of("id", 1, "method", "tools/list"));
        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(response));

        assertTrue(json.has("error"));
        assertEquals("流程非草稿状态,无法编辑结构", json.path("error").path("message").asText());
        assertTrue(!json.has("result"));
    }

    @Test
    void rpcSuccessSerializesWithoutErrorMember() throws Exception {
        McpAccessTokenService tokenService = mock(McpAccessTokenService.class);
        McpToolService toolService = mock(McpToolService.class);
        McpAccessTokenService.Principal principal =
                principal(OrgAccessLevel.READ_ONLY);
        when(tokenService.authenticate(null, "awmcp_query_token")).thenReturn(principal);
        when(toolService.listTools(principal)).thenReturn(List.of());

        McpRpcResponse response = new McpController(tokenService, toolService)
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
                principal(OrgAccessLevel.READ_ONLY);
        when(tokenService.authenticate(null, "awmcp_query_token")).thenReturn(principal);
        when(toolService.call(principal, "autowonder.list_projects", Map.of()))
                .thenReturn(List.of(Map.of("id", 100L)));

        McpRpcResponse response = new McpController(tokenService, toolService).rpc(null, "awmcp_query_token", Map.of(
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
                principal(OrgAccessLevel.READ_ONLY);
        when(tokenService.authenticate(null, "awmcp_query_token")).thenReturn(principal);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new McpController(tokenService, toolService)).build();

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
                principal(OrgAccessLevel.READ_ONLY);
        when(tokenService.authenticate(null, "awmcp_query_token")).thenReturn(principal);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new McpController(tokenService, toolService)).build();

        mvc.perform(post("/api/mcp")
                        .queryParam("token", "awmcp_query_token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
                        .content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(""));
    }

    @Test
    void directAndRpcToolListsUseEffectiveLevelInsideContext() {
        McpAccessTokenService tokenService = mock(McpAccessTokenService.class);
        McpToolService toolService = mock(McpToolService.class);
        McpAccessTokenService.Principal principal =
                principal(OrgAccessLevel.READ_ONLY);
        McpToolVO visible = new McpToolVO();
        visible.setName("autowonder.list_projects");
        when(tokenService.authenticate(null, "awmcp_query_token"))
                .thenReturn(principal);
        when(toolService.listTools(principal)).thenAnswer(invocation -> {
            assertEquals(100L, AutoWonderContext.get().getCurrentOrgId());
            assertEquals(7L, AutoWonderContext.get().getUserId());
            assertEquals(OrgAccessLevel.READ_ONLY,
                    AutoWonderContext.get().getOrgAccessLevel());
            return List.of(visible);
        });
        McpController controller = new McpController(tokenService, toolService);

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
            OrgAccessLevel accessLevel) {
        return new McpAccessTokenService.Principal(
                100L, 7L, 1L, accessLevel,
                McpAccessTokenService.CredentialType.CONVERSATION);
    }
}
