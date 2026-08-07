package com.aliyun.autowonder.auth.filter;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.jwt.TokenPayload;
import com.aliyun.autowonder.auth.session.SessionService;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.log.BizLog;
import com.aliyun.autowonder.org.OrgMemberDO;
import com.aliyun.autowonder.org.OrgMemberDao;
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
    private static final Pattern ORG_SWITCH_PATH =
            Pattern.compile("^/api/orgs/[0-9]+/switch$");
    private static final String PERSONAL_MCP_TOKEN_PREFIX = "/api/mcp/tokens";
    private static final String PERSONAL_USER_API_PREFIX = "/api/users/me/";
    private static final List<String> WHITELIST_PREFIXES = List.of(
            "/api/auth/", "/api/hello", "/api/daemon/", "/api/mcp/rpc", "/api/mcp/tools");

    private final JwtService jwtService;
    private final SessionService sessionService;
    private final OrgMemberDao orgMemberDao;
    private final UserDao userDao;

    public AuthFilter(JwtService jwtService, SessionService sessionService,
                      OrgMemberDao orgMemberDao, UserDao userDao) {
        this.jwtService = jwtService;
        this.sessionService = sessionService;
        this.orgMemberDao = orgMemberDao;
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
            ctx.setCurrentOrgId(payload.getCurrentOrgId());
            BizLog bizLog = ctx.getBizLog();
            if (bizLog != null) {
                bizLog.setUserId(payload.getUserId());
                bizLog.setOrgId(payload.getCurrentOrgId());
            }
            ctx.setTraceId(UUID.randomUUID().toString());
            if (payload.getCurrentOrgId() != null
                    && !isLoginOnlyRequest(request)) {
                OrgMemberDO member = orgMemberDao.findByOrgAndUser(
                        payload.getCurrentOrgId(), payload.getUserId());
                if (member == null
                        || !Integer.valueOf(0).equals(member.getIsDeleted())
                        || !Integer.valueOf(0).equals(member.getStatus())) {
                    writeFailure(response, HttpServletResponse.SC_FORBIDDEN,
                            ErrorCode.ORG_NOT_MEMBER);
                    return;
                }
                try {
                    ctx.setOrgAccessLevel(OrgAccessLevel.valueOf(member.getAccessLevel()));
                    ctx.setOrgMember(member);
                } catch (IllegalArgumentException | NullPointerException e) {
                    writeFailure(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                            ErrorCode.ORG_ACCESS_LEVEL_INVALID);
                    return;
                }
            }
            chain.doFilter(request, response);
        } finally {
            AutoWonderContext.destroy();
        }
    }

    private boolean isWhitelisted(HttpServletRequest request) {
        String path = request.getRequestURI();
        if (WHITELIST_EXACTS.contains(path) || DINGTALK_CALLBACK_PATH.equals(path)) {
            return true;
        }
        if ("GET".equalsIgnoreCase(request.getMethod())
                && (PLATFORM_BRANDING_PUBLIC_PATH.equals(path)
                || PLATFORM_BRANDING_LOGO_PATH.equals(path)
                || INTEGRATION_CAPABILITIES_PATH.equals(path))) {
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
        return ("POST".equalsIgnoreCase(method) && "/api/orgs".equals(path))
                || ("GET".equalsIgnoreCase(method) && "/api/orgs/mine".equals(path))
                || ("POST".equalsIgnoreCase(method) && ORG_SWITCH_PATH.matcher(path).matches());
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
