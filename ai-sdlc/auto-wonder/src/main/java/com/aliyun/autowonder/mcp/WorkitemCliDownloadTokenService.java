package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.WorkspaceDomain;
import com.aliyun.autowonder.artifact.RequirementDocumentService;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.mcp.dto.WorkitemCliDownloadTokenVO;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Mints short-lived, user-level, read-only credentials for the
 * {@code autowonder workitem download} CLI command and builds the
 * deployment-aware command examples shared by the MCP tool descriptions.
 * Mirrors {@link WorkitemCliUploadTokenService} but only ever requires an
 * active read-capable membership, matching {@code list_workitem_documents} visibility.
 */
@Service
public class WorkitemCliDownloadTokenService {

    public static final String TOKEN_PREFIX = "awdownload_";
    public static final String TURN_PURPOSE = "conversation-turn-workitem-download";
    public static final String PURPOSE = "workitem-requirement-download";
    public static final String TOKEN_ENV_NAME = "AUTOWONDER_DOWNLOAD_TOKEN";
    public static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    public static final List<String> SUPPORTED_EXTENSIONS = RequirementDocumentService.SUPPORTED_EXTENSIONS;

    private final JwtService jwtService;
    private final WorkitemDao workitemDao;
    private final WorkspaceMemberDao workspaceMemberDao;
    private final PlatformBrandingService brandingService;
    private final ConversationMcpTokenService conversationTokens;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ConversationCredentialAuditService executionAudit;

    public void auditUse(McpAccessTokenService.Principal principal, long workitemId, String action, Throwable failure) {
        if (executionAudit != null) executionAudit.record(principal, action, workitemId, failure);
    }

    public WorkitemCliDownloadTokenService(JwtService jwtService,
                                           WorkitemDao workitemDao,
                                           WorkspaceMemberDao workspaceMemberDao,
                                           PlatformBrandingService brandingService) {
        this(jwtService, workitemDao, workspaceMemberDao, brandingService, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public WorkitemCliDownloadTokenService(JwtService jwtService, WorkitemDao workitemDao,
            WorkspaceMemberDao workspaceMemberDao, PlatformBrandingService brandingService,
            ConversationMcpTokenService conversationTokens) {
        this.conversationTokens = conversationTokens;
        this.jwtService = jwtService;
        this.workitemDao = workitemDao;
        this.workspaceMemberDao = workspaceMemberDao;
        this.brandingService = brandingService;
    }

    public WorkitemCliDownloadTokenVO mint(McpAccessTokenService.Principal principal, long workitemId) {
        if (principal.executionScope() == null) return mint(principal.credentialType(), principal.userId(), workitemId);
        if (conversationTokens == null) throw new BizException(ErrorCode.UNAUTHORIZED);
        var verified = conversationTokens.validateExecution(principal.userId(), principal.workspaceId(), principal.executionScope());
        if (!verified.accessLevel().allows(WorkspaceAccessLevel.READ_ONLY)) throw new BizException(ErrorCode.NO_PERMISSION);
        return mintInternal(principal.credentialType(), principal.userId(), workitemId, verified);
    }

    public WorkitemCliDownloadTokenVO mint(McpAccessTokenService.CredentialType credentialType,
                                           long userId, long workitemId) {
        return mintInternal(credentialType, userId, workitemId, null);
    }

    private WorkitemCliDownloadTokenVO mintInternal(McpAccessTokenService.CredentialType credentialType,
            long userId, long workitemId, McpAccessTokenService.Principal principal) {
        if (credentialType != McpAccessTokenService.CredentialType.LONG_LIVED
                && credentialType != McpAccessTokenService.CredentialType.DISPATCH
                && credentialType != McpAccessTokenService.CredentialType.CONVERSATION) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        WorkitemDO workitem = workitemDao.findById(workitemId);
        if (workitem == null || workitem.getTenantId() == null) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
        if (principal != null && !workitem.getTenantId().equals(principal.workspaceId())) throw new BizException(ErrorCode.NO_PERMISSION);
        requireReadMembership(workitem.getTenantId(), userId, WorkspaceDomain.ARTIFACT);

        String token = TOKEN_PREFIX
                + (principal == null ? jwtService.signUserPurpose(userId, PURPOSE, TOKEN_TTL.getSeconds())
                : jwtService.signConversationTurn(userId, principal.workspaceId(), TURN_PURPOSE,
                        principal.executionScope(), workitemId, TOKEN_TTL.getSeconds()));
        long now = Instant.now().getEpochSecond();

        WorkitemCliDownloadTokenVO vo = new WorkitemCliDownloadTokenVO();
        vo.setToken(token);
        vo.setTokenType("Bearer");
        vo.setExpiresInSeconds(TOKEN_TTL.getSeconds());
        vo.setExpiresAt(Instant.ofEpochSecond(now + TOKEN_TTL.getSeconds()).toString());
        vo.setServerUrl(brandingService.effectivePublicBaseUrl());
        vo.setRuntimeVersion(brandingService.recommendedRuntimeVersion());
        vo.setTokenEnvName(TOKEN_ENV_NAME);
        vo.setCommand(posixCommand(token, workitemId));
        vo.setPowershellCommand(powershellCommand(token, workitemId));
        vo.setSupportedExtensions(SUPPORTED_EXTENSIONS);
        return vo;
    }

    /**
     * Verifies a download token presented to the dedicated CLI download endpoints and
     * returns the owning user id. Never echoes the token in the error.
     */
    public long authenticate(String token) {
        try {
            if (token == null || !token.startsWith(TOKEN_PREFIX)) {
                throw new IllegalArgumentException("invalid prefix");
            }
            Map<String, Object> claims = jwtService.parseUserPurpose(token.substring(TOKEN_PREFIX.length()));
            if (!PURPOSE.equals(claims.get("purpose"))) {
                throw new IllegalArgumentException("invalid purpose");
            }
            return ((Number) claims.get("uid")).longValue();
        } catch (Exception e) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }

    public McpAccessTokenService.Principal authenticateForWorkitem(String token, long workitemId) {
        McpAccessTokenService.Principal signedIdentity = null;
        try {
            if (token == null || !token.startsWith(TOKEN_PREFIX)) throw new BizException(ErrorCode.UNAUTHORIZED);
            String jwt = token.substring(TOKEN_PREFIX.length());
            var common = jwtService.parseUserPurpose(jwt);
            if (PURPOSE.equals(common.get("purpose"))) return McpAccessTokenService.Principal.personal(authenticate(token), 0L);
            if (!TURN_PURPOSE.equals(common.get("purpose")) || conversationTokens == null) throw new BizException(ErrorCode.UNAUTHORIZED);
            var claims = jwtService.parseConversationTurn(jwt);
            signedIdentity = new McpAccessTokenService.Principal(claims.workspaceId(), claims.userId(),
                    claims.scope().conversationId(), WorkspaceAccessLevel.READ_ONLY,
                    McpAccessTokenService.CredentialType.CONVERSATION, claims.scope());
            if (!Long.valueOf(workitemId).equals(claims.workitemId())) throw new BizException(ErrorCode.UNAUTHORIZED);
            var principal = conversationTokens.validateExecution(claims.userId(), claims.workspaceId(), claims.scope());
            WorkitemDO workitem = workitemDao.findById(workitemId);
            if (workitem == null || !Long.valueOf(claims.workspaceId()).equals(workitem.getTenantId())) throw new BizException(ErrorCode.UNAUTHORIZED);
            if (!principal.accessLevel().allows(WorkspaceAccessLevel.READ_ONLY)) throw new BizException(ErrorCode.NO_PERMISSION);
            requireReadMembership(claims.workspaceId(), claims.userId(), WorkspaceDomain.ARTIFACT);
            return principal;
        } catch (BizException e) {
            auditUse(signedIdentity, workitemId, "CLI_DOWNLOAD_AUTHENTICATE", e);
            throw e;
        } catch (Exception e) {
            auditUse(signedIdentity, workitemId, "CLI_DOWNLOAD_AUTHENTICATE", e);
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
    }

    /** One-line command template used in MCP tool descriptions; carries no token. */
    public String commandTemplate() {
        return "npx -y autowonder@" + brandingService.recommendedRuntimeVersion()
                + " workitem download --server-url " + brandingService.effectivePublicBaseUrl()
                + " --workitem-id <workitem-id>"
                + " --file <name-or-id> --output-dir <dir> --json";
    }

    public String tokenEnvHint() {
        return "export " + TOKEN_ENV_NAME + "='<token returned by autowonder.workitem_cli_download_token>'";
    }

    private String posixCommand(String token, long workitemId) {
        return "export " + TOKEN_ENV_NAME + "=" + posixQuote(token) + "\n\n"
                + "npx -y autowonder@" + brandingService.recommendedRuntimeVersion() + " workitem download \\\n"
                + "  --server-url " + posixQuote(brandingService.effectivePublicBaseUrl()) + " \\\n"
                + "  --workitem-id " + workitemId + " \\\n"
                + "  --file <name-or-id> \\\n"
                + "  --output-dir <dir> \\\n"
                + "  --json";
    }

    private String powershellCommand(String token, long workitemId) {
        return "$env:" + TOKEN_ENV_NAME + "=" + powershellQuote(token) + "\n\n"
                + "npx -y autowonder@" + brandingService.recommendedRuntimeVersion() + " workitem download `\n"
                + "  --server-url " + powershellQuote(brandingService.effectivePublicBaseUrl()) + " `\n"
                + "  --workitem-id " + workitemId + " `\n"
                + "  --file <name-or-id> `\n"
                + "  --output-dir <dir> `\n"
                + "  --json";
    }

    /**
     * Live authorization reused by the CLI download endpoints: an active membership at any
     * access level. Read-only members may download, matching list_workitem_documents visibility.
     */
    public void requireReadMembership(long tenantId, long userId, WorkspaceDomain domain) {
        WorkspaceMemberDO member = workspaceMemberDao.findByWorkspaceAndUser(tenantId, userId);
        if (member == null
                || !Integer.valueOf(0).equals(member.getStatus())
                || !Integer.valueOf(0).equals(member.getIsDeleted())) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        try {
            WorkspaceAccessLevel.valueOf(member.getAccessLevel());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }

    }

    private static String posixQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static String powershellQuote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
