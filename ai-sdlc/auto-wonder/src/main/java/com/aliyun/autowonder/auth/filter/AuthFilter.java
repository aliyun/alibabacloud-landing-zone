package com.aliyun.autowonder.auth.filter;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.jwt.TokenPayload;
import com.aliyun.autowonder.auth.session.SessionService;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.log.BizLog;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

public class AuthFilter extends OncePerRequestFilter {

    private static final List<String> WHITELIST_EXACTS = List.of("/api/mcp");
    private static final String DINGTALK_CALLBACK_PATH = "/api/integrations/dingtalk/callback";
    private static final String INTEGRATION_CAPABILITIES_PATH = "/api/integrations/capabilities";
    private static final String PLATFORM_BRANDING_PUBLIC_PATH = "/api/platform/branding/public";
    private static final String PLATFORM_BRANDING_LOGO_PATH = "/api/platform/branding/logo";
    private static final Pattern WORKSPACE_SWITCH_PATH =
            Pattern.compile("^/api/workspaces/[0-9]+/switch$");
    private static final Pattern WORKSPACE_LIFECYCLE_PATH =
            Pattern.compile("^/api/workspaces/[0-9]+$");
    private static final Pattern WORKSPACE_RESTORE_PATH =
            Pattern.compile("^/api/workspaces/[0-9]+/restore$");
    private static final String WORKSPACE_RECYCLE_BIN_PATH = "/api/workspaces/recycle-bin";
    private static final Pattern CLI_WORKITEM_UPLOAD_PATH =
            Pattern.compile("^/api/cli/workitems/[0-9]+/requirement-documents$");
    private static final Pattern CLI_SCHEDULED_TASK_UPLOAD_PATH =
            Pattern.compile("^/api/cli/scheduled-tasks/[0-9]+/documents$");
    private static final Pattern CLI_WORKITEM_DOWNLOAD_INDEX_PATH =
            Pattern.compile("^/api/cli/workitems/[0-9]+/requirement-documents/index$");
    private static final Pattern CLI_WORKITEM_DOWNLOAD_CONTENT_PATH =
            Pattern.compile("^/api/cli/workitems/[0-9]+/requirement-documents/[0-9]+/content$");
    // path 模式 MCP 入口：/api/mcp/<个人令牌>[/(rpc)[/]]，放行后由 McpController 做令牌鉴权
    private static final Pattern PERSONAL_MCP_PATH_TOKEN_PATH =
            Pattern.compile("^/api/mcp/awmcp_[A-Za-z0-9_-]{43}(?:/(?:rpc/?)?)?$");
    private static final String PERSONAL_MCP_TOKEN_PREFIX = "/api/mcp/tokens";
    private static final String PERSONAL_USER_API_PREFIX = "/api/users/me/";
    private static final List<String> WHITELIST_PREFIXES = List.of(
            "/api/auth/", "/api/hello", "/api/daemon/", "/api/mcp/rpc", "/api/mcp/tools");

    private final JwtService jwtService;
    private final SessionService sessionService;
    private final WorkspaceMemberDao workspaceMemberDao;
    private final WorkspaceDao workspaceDao;
    private final UserDao userDao;

    public AuthFilter(JwtService jwtService, SessionService sessionService,
                      WorkspaceMemberDao workspaceMemberDao, WorkspaceDao workspaceDao,
                      UserDao userDao) {
        this.jwtService = jwtService;
        this.sessionService = sessionService;
        this.workspaceMemberDao = workspaceMemberDao;
        this.workspaceDao = workspaceDao;
        this.userDao = userDao;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (isWhitelisted(request)) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeUnauthorized(response);
            return;
        }
        String token = header.substring("Bearer ".length()).trim();
        TokenPayload payload;
        try {
            payload = jwtService.parse(token);
        } catch (Exception e) {
            writeUnauthorized(response);
            return;
        }
        if (payload.getJti() != null && sessionService.isBlacklisted(payload.getJti())) {
            writeUnauthorized(response);
            return;
        }
        if (!isDeactivationRevokeRequest(request)) {
            UserDO user = userDao.findById(payload.getUserId());
            if (user != null && Integer.valueOf(1).equals(user.getStatus())
                    && "DEACTIVATED".equals(user.getPasswordHash())) {
                writeFailure(response, HttpServletResponse.SC_UNAUTHORIZED,
                        ErrorCode.DEACTIVATION_ACCOUNT_DISABLED);
                return;
            }
        }
        try {
            AutoWonderContext ctx = AutoWonderContext.get();
            ctx.setUserId(payload.getUserId());
            ctx.setCurrentWorkspaceId(payload.getCurrentWorkspaceId());
            BizLog bizLog = ctx.getBizLog();
            if (bizLog != null) {
                bizLog.setUserId(payload.getUserId());
                bizLog.setWorkspaceId(payload.getCurrentWorkspaceId());
            }
            ctx.setTraceId(UUID.randomUUID().toString());
            if (payload.getCurrentWorkspaceId() != null
                    && !isLoginOnlyRequest(request)) {
                // Read from the database on every request instead of trusting the token: a workspace
                // deleted after the token was issued must stop working immediately, and there is no
                // revocation path that reaches already-issued access tokens.
                if (workspaceDao.countUsable(payload.getCurrentWorkspaceId()) == 0) {
                    writeFailure(response, HttpServletResponse.SC_FORBIDDEN,
                            ErrorCode.ORG_DELETED_OR_DISABLED);
                    return;
                }
                WorkspaceMemberDO member = workspaceMemberDao.findByWorkspaceAndUser(
                        payload.getCurrentWorkspaceId(), payload.getUserId());
                boolean activeMember = member != null
                        && Integer.valueOf(0).equals(member.getIsDeleted())
                        && Integer.valueOf(0).equals(member.getStatus());
                if (activeMember) {
                    try {
                        ctx.setWorkspaceAccessLevel(WorkspaceAccessLevel.valueOf(member.getAccessLevel()));
                        ctx.setWorkspaceMember(member);
                    } catch (IllegalArgumentException | NullPointerException e) {
                        writeFailure(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                                ErrorCode.WORKSPACE_ACCESS_LEVEL_INVALID);
                        return;
                    }
                } else if (isPlatformAdmin(payload.getUserId())) {
                    // A platform admin does not have to be a member of the workspace: ADMIN is the
                    // highest WorkspaceAccessLevel, so every @RequireWorkspaceAccess guard passes
                    // through this single branch. The flag is read from the database on every
                    // request, so a revoked admin loses cross-workspace access immediately.
                    ctx.setWorkspaceAccessLevel(WorkspaceAccessLevel.ADMIN);
                } else {
                    writeFailure(response, HttpServletResponse.SC_FORBIDDEN,
                            ErrorCode.WORKSPACE_NOT_MEMBER);
                    return;
                }
            }
            chain.doFilter(request, response);
        } finally {
            AutoWonderContext.destroy();
        }
    }

    private boolean isPlatformAdmin(Long userId) {
        UserDO user = userDao.findById(userId);
        return user != null && Integer.valueOf(1).equals(user.getIsAdmin());
    }

    private boolean isWhitelisted(HttpServletRequest request) {
        String path = request.getRequestURI();
        if ("POST".equalsIgnoreCase(request.getMethod())
                && "/api/integrations/feishu/callback".equals(path)) {
            return true;
        }
        if (WHITELIST_EXACTS.contains(path) || DINGTALK_CALLBACK_PATH.equals(path)) {
            return true;
        }
        if ("GET".equalsIgnoreCase(request.getMethod())
                && (PLATFORM_BRANDING_PUBLIC_PATH.equals(path)
                || PLATFORM_BRANDING_LOGO_PATH.equals(path)
                || INTEGRATION_CAPABILITIES_PATH.equals(path))) {
            return true;
        }
        if ("POST".equalsIgnoreCase(request.getMethod())
                && (CLI_WORKITEM_UPLOAD_PATH.matcher(path).matches()
                    || CLI_SCHEDULED_TASK_UPLOAD_PATH.matcher(path).matches())) {
            return true;
        }
        if ("GET".equalsIgnoreCase(request.getMethod())
                && (CLI_WORKITEM_DOWNLOAD_INDEX_PATH.matcher(path).matches()
                    || CLI_WORKITEM_DOWNLOAD_CONTENT_PATH.matcher(path).matches())) {
            return true;
        }
        if (PERSONAL_MCP_PATH_TOKEN_PATH.matcher(path).matches()) {
            return true;
        }
        for (String prefix : WHITELIST_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLoginOnlyRequest(HttpServletRequest request) {
        String method = request.getMethod();
        String path = normalizeTrailingSlash(request.getRequestURI());
        if (path.startsWith(PERSONAL_MCP_TOKEN_PREFIX)) {
            return true;
        }
        if (path.startsWith(PERSONAL_USER_API_PREFIX)) {
            return true;
        }
        if ("GET".equalsIgnoreCase(method) && WORKSPACE_RECYCLE_BIN_PATH.equals(path)) {
            return true;
        }
        if (("PUT".equalsIgnoreCase(method) || "DELETE".equalsIgnoreCase(method))
                && WORKSPACE_LIFECYCLE_PATH.matcher(path).matches()) {
            return true;
        }
        if ("POST".equalsIgnoreCase(method) && WORKSPACE_RESTORE_PATH.matcher(path).matches()) {
            return true;
        }
        return ("POST".equalsIgnoreCase(method) && "/api/workspaces".equals(path))
                || ("GET".equalsIgnoreCase(method) && "/api/workspaces/mine".equals(path))
                || ("POST".equalsIgnoreCase(method) && WORKSPACE_SWITCH_PATH.matcher(path).matches());
    }

    private boolean isDeactivationRevokeRequest(HttpServletRequest request) {
        String path = normalizeTrailingSlash(request.getRequestURI());
        return "POST".equalsIgnoreCase(request.getMethod())
                && path.equals("/api/users/me/deactivation/revoke");
    }

    private String normalizeTrailingSlash(String path) {
        return path.length() > 1 && path.endsWith("/")
                ? path.substring(0, path.length() - 1)
                : path;
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        writeFailure(response, HttpServletResponse.SC_UNAUTHORIZED, ErrorCode.UNAUTHORIZED);
    }

    private void writeFailure(HttpServletResponse response, int status, ErrorCode errorCode)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        Result<Void> body = Result.fail(errorCode);
        response.getWriter().write(JSON.toJSONString(body));
    }
}
