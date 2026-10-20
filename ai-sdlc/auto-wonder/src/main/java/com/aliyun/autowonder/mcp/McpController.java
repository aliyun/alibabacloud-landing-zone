package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.i18n.SystemMessages;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.mcp.dto.McpRpcResponse;
import com.aliyun.autowonder.mcp.dto.McpToolCallRequest;
import com.aliyun.autowonder.mcp.dto.McpToolVO;
import com.aliyun.autowonder.memory.store.MemoryStoreApplicationService;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.cors.CorsUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/mcp")
public class McpController {
    private final McpAccessTokenService tokenService;
    private final McpToolService toolService;
    private final McpSseService sseService;

    public McpController(McpAccessTokenService tokenService, McpToolService toolService, McpSseService sseService) {
        this.tokenService = tokenService;
        this.toolService = toolService;
        this.sseService = sseService;
    }

    @GetMapping(value = {
            "", "/rpc",
            "/{pathToken}", "/{pathToken}/",
            "/{pathToken}/rpc", "/{pathToken}/rpc/"
    })
    public SseEmitter stream(@RequestHeader(value = "Authorization", required = false) String authorization,
                                     @PathVariable(value = "pathToken", required = false) String pathToken,
                                     @RequestParam(value = "token", required = false) String queryToken,
                                     HttpServletRequest request, HttpServletResponse response) throws IOException {
        try {
            if (CorsUtils.isCorsRequest(request)) {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                return null;
            }
        } catch (IllegalArgumentException malformedOrigin) {
            response.setStatus(HttpStatus.FORBIDDEN.value());
            return null;
        }
        try {
            tokenService.authenticate(authorization, resolveToken(pathToken, queryToken));
        } catch (BizException e) {
            // Force JSON before opening the stream, even for clients accepting only SSE.
            response.setStatus(HttpStatus.UNAUTHORIZED.value());
            response.setHeader("WWW-Authenticate", "Bearer");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding("UTF-8");
            response.getWriter().write(JSON.toJSONString(Result.fail(e)));
            return null;
        }
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("X-Accel-Buffering", "no");
        return sseService.open();
    }

    @GetMapping("/tools")
    public Result<List<McpToolVO>> listTools(@RequestHeader(value = "Authorization", required = false) String authorization,
                                             @RequestParam(value = "token", required = false) String token) {
        McpAccessTokenService.Principal principal = tokenService.authenticate(authorization, token);
        return withContext(principal, () ->
                Result.ok(toolService.listTools(principal)));
    }

    @PostMapping("/tools/call")
    public Result<Object> callTool(@RequestHeader(value = "Authorization", required = false) String authorization,
                                   @RequestParam(value = "token", required = false) String token,
                                   @RequestBody McpToolCallRequest request) {
        McpAccessTokenService.Principal principal = tokenService.authenticate(authorization, token);
        return withContext(principal, () -> Result.ok(toolService.call(principal, request.getName(), request.getArguments())));
    }

    @PostMapping(value = {
            "", "/rpc",
            "/{pathToken}", "/{pathToken}/",
            "/{pathToken}/rpc", "/{pathToken}/rpc/"
    }, produces = {
            MediaType.APPLICATION_JSON_VALUE,
            MediaType.TEXT_EVENT_STREAM_VALUE
    })
    public ResponseEntity<?> rpc(@RequestHeader(value = "Authorization", required = false) String authorization,
                                 @PathVariable(value = "pathToken", required = false) String pathToken,
                                 @RequestParam(value = "token", required = false) String queryToken,
                                 @RequestHeader(value = "Accept", required = false) String accept,
                                 @RequestBody Map<String, Object> request) {
        String token = resolveToken(pathToken, queryToken);
        if (!request.containsKey("id")) {
            acknowledgeNotification(authorization, token);
            return ResponseEntity.accepted().build();
        }

        McpRpcResponse response = rpc(authorization, token, request);
        if (acceptsEventStreamOnly(accept)) {
            return ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body("data:" + JSON.toJSONString(response) + "\n\n");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_JSON)
                .body(response);
    }

    public McpRpcResponse rpc(@RequestHeader(value = "Authorization", required = false) String authorization,
                              @RequestParam(value = "token", required = false) String token,
                              @RequestBody Map<String, Object> request) {
        Object id = request.get("id");
        try {
            McpAccessTokenService.Principal principal = tokenService.authenticate(authorization, token);
            return withContext(principal, () -> handleRpc(principal, id, request));
        } catch (MemoryStoreApplicationService.MemoryMaintenanceLeaseConflictException e) {
            return McpRpcResponse.error(id, -32009, "MEMORY_MAINTENANCE_LOCKED: retry later");
        } catch (BizException e) {
            return McpRpcResponse.error(id, -32000,
                    SystemMessages.render(e.getMessageKey(), e.getMessageArgs(), e.getMessage()));
        } catch (Exception e) {
            return McpRpcResponse.error(id, -32603, e.getMessage());
        }
    }

    private String resolveToken(String pathToken, String queryToken) {
        if (pathToken != null && !pathToken.isBlank()) {
            return pathToken;
        }
        return queryToken;
    }

    private void acknowledgeNotification(String authorization, String token) {
        McpAccessTokenService.Principal principal = tokenService.authenticate(authorization, token);
        withContext(principal, () -> null);
    }

    private boolean acceptsEventStreamOnly(String accept) {
        if (accept == null || accept.isBlank()) {
            return false;
        }
        List<MediaType> accepted = MediaType.parseMediaTypes(accept);
        boolean acceptsJson = accepted.stream()
                .anyMatch(mediaType -> mediaType.isCompatibleWith(MediaType.APPLICATION_JSON));
        boolean acceptsEventStream = accepted.stream()
                .anyMatch(mediaType -> mediaType.isCompatibleWith(MediaType.TEXT_EVENT_STREAM));
        return acceptsEventStream && !acceptsJson;
    }

    private McpRpcResponse handleRpc(McpAccessTokenService.Principal principal, Object id, Map<String, Object> request) {
        String method = String.valueOf(request.get("method"));
        if ("initialize".equals(method)) {
            return McpRpcResponse.ok(id, Map.of(
                    "protocolVersion", "2025-06-18",
                    "serverInfo", Map.of("name", "autowonder", "version", "1.0.0"),
                    "capabilities", Map.of("tools", Map.of())));
        }
        if ("tools/list".equals(method)) {
            return McpRpcResponse.ok(
                    id, Map.of("tools", toolService.listTools(principal)));
        }
        if ("tools/call".equals(method)) {
            Map<String, Object> params = map(request.get("params"));
            Object result = toolService.call(principal, String.valueOf(params.get("name")), map(params.get("arguments")));
            Object structuredContent = result instanceof List<?> ? Map.of("items", result) : result;
            return McpRpcResponse.ok(id, Map.of(
                    "content", List.of(Map.of("type", "text", "text", JSON.toJSONString(structuredContent))),
                    "structuredContent", structuredContent,
                    "isError", false));
        }
        return McpRpcResponse.error(id, -32601, "Method not found");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        if (value == null) {
            return Map.of();
        }
        if (value instanceof Map<?, ?> raw) {
            return (Map<String, Object>) raw;
        }
        return JSON.parseObject(JSON.toJSONString(value));
    }

    private <T> T withContext(McpAccessTokenService.Principal principal, SupplierWithException<T> action) {
        try {
            AutoWonderContext ctx = AutoWonderContext.get();
            ctx.setUserId(principal.userId());
            ctx.setTraceId(UUID.randomUUID().toString());
            if (principal.isWorkspaceScoped()) {
                ctx.setCurrentWorkspaceId(principal.workspaceId());
                ctx.setWorkspaceAccessLevel(principal.accessLevel());
            }
            return action.get();
        } finally {
            AutoWonderContext.destroy();
        }
    }

    private interface SupplierWithException<T> {
        T get();
    }
}
