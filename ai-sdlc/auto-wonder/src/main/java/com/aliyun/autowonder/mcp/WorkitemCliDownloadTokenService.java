package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
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
    public static final String PURPOSE = "workitem-requirement-download";
    public static final String TOKEN_ENV_NAME = "AUTOWONDER_DOWNLOAD_TOKEN";
    public static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    public static final List<String> SUPPORTED_EXTENSIONS = RequirementDocumentService.SUPPORTED_EXTENSIONS;

    private final JwtService jwtService;
    private final WorkitemDao workitemDao;
    private final WorkspaceMemberDao workspaceMemberDao;
    private final PlatformBrandingService brandingService;

    public WorkitemCliDownloadTokenService(JwtService jwtService,
                                           WorkitemDao workitemDao,
                                           WorkspaceMemberDao workspaceMemberDao,
                                           PlatformBrandingService brandingService) {
        this.jwtService = jwtService;
        this.workitemDao = workitemDao;
        this.workspaceMemberDao = workspaceMemberDao;
        this.brandingService = brandingService;
    }

    public WorkitemCliDownloadTokenVO mint(McpAccessTokenService.CredentialType credentialType,
                                           long userId, long workitemId) {
        if (credentialType != McpAccessTokenService.CredentialType.LONG_LIVED
                && credentialType != McpAccessTokenService.CredentialType.DISPATCH
                && credentialType != McpAccessTokenService.CredentialType.CONVERSATION) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        WorkitemDO workitem = workitemDao.findById(workitemId);
        if (workitem == null || workitem.getTenantId() == null) {
            throw new BizException(ErrorCode.WORKITEM_NOT_FOUND);
        }
        requireReadMembership(workitem.getTenantId(), userId);

        String token = TOKEN_PREFIX
                + jwtService.signUserPurpose(userId, PURPOSE, TOKEN_TTL.getSeconds());
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
    public void requireReadMembership(long tenantId, long userId) {
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
