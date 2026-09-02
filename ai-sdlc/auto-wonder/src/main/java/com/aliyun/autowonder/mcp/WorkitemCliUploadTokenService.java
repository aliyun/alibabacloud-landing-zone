package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.mcp.dto.WorkitemCliUploadTokenVO;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mints short-lived, user-level, upload-only credentials for the
 * {@code autowonder workitem upload} CLI command and builds the
 * deployment-aware command examples shared by the MCP tool descriptions.
 */
@Service
public class WorkitemCliUploadTokenService {

    public static final String TOKEN_PREFIX = "awupload_";
    public static final String PURPOSE = "workitem-requirement-upload";
    public static final String TOKEN_ENV_NAME = "AUTOWONDER_UPLOAD_TOKEN";
    public static final Duration TOKEN_TTL = Duration.ofMinutes(30);
    public static final List<String> SUPPORTED_EXTENSIONS =
            List.of(".md", ".markdown", ".txt", ".html", ".pdf", ".png", ".jpg", ".jpeg", ".webp");
    public static final int MAX_FILES = 10;
    public static final long MAX_FILE_SIZE_BYTES = 5L * 1024L * 1024L;
    public static final long MAX_TOTAL_SIZE_BYTES = 20L * 1024L * 1024L;

    private static final Set<WorkspaceAccessLevel> WRITE_LEVELS =
            Set.of(WorkspaceAccessLevel.READ_WRITE, WorkspaceAccessLevel.ADMIN);

    private final JwtService jwtService;
    private final WorkitemDao workitemDao;
    private final WorkspaceMemberDao workspaceMemberDao;
    private final PlatformBrandingService brandingService;

    public WorkitemCliUploadTokenService(JwtService jwtService,
                                         WorkitemDao workitemDao,
                                         WorkspaceMemberDao workspaceMemberDao,
                                         PlatformBrandingService brandingService) {
        this.jwtService = jwtService;
        this.workitemDao = workitemDao;
        this.workspaceMemberDao = workspaceMemberDao;
        this.brandingService = brandingService;
    }

    public WorkitemCliUploadTokenVO mint(McpAccessTokenService.CredentialType credentialType,
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
        requireWriteMembership(workitem.getTenantId(), userId);

        String token = TOKEN_PREFIX
                + jwtService.signUserPurpose(userId, PURPOSE, TOKEN_TTL.getSeconds());
        long now = Instant.now().getEpochSecond();

        WorkitemCliUploadTokenVO vo = new WorkitemCliUploadTokenVO();
        vo.setToken(token);
        vo.setTokenType("Bearer");
        vo.setExpiresInSeconds(TOKEN_TTL.getSeconds());
        vo.setExpiresAt(Instant.ofEpochSecond(now + TOKEN_TTL.getSeconds()).toString());
        vo.setServerUrl(brandingService.trustedPublicBaseUrl());
        vo.setRuntimeVersion(brandingService.recommendedRuntimeVersion());
        vo.setTokenEnvName(TOKEN_ENV_NAME);
        vo.setCommand(posixCommand(token, workitemId));
        vo.setPowershellCommand(powershellCommand(token, workitemId));
        vo.setSupportedExtensions(SUPPORTED_EXTENSIONS);
        vo.setMaxFiles(MAX_FILES);
        vo.setMaxFileSizeBytes(MAX_FILE_SIZE_BYTES);
        vo.setMaxTotalSizeBytes(MAX_TOTAL_SIZE_BYTES);
        return vo;
    }

    /**
     * Verifies an upload token presented to the dedicated CLI upload endpoint and
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
                + " workitem upload --server-url " + brandingService.trustedPublicBaseUrl()
                + " --workitem-id <workitem-id>"
                + " --file <filepath-1> --file <filepath-2> --file <images-1> --json";
    }

    /** One-line scheduled-task upload command template used in MCP tool descriptions; carries no token. */
    public String scheduledTaskCommandTemplate() {
        return "npx -y autowonder@" + brandingService.recommendedRuntimeVersion()
                + " scheduled-task upload --server-url " + brandingService.trustedPublicBaseUrl()
                + " --scheduled-task-id <scheduled-task-id>"
                + " --file <filepath-1> --file <filepath-2> --file <images-1> --json";
    }

    public String tokenEnvHint() {
        return "export " + TOKEN_ENV_NAME + "='<token returned by autowonder.workitem_cli_upload_token>'";
    }

    private String posixCommand(String token, long workitemId) {
        return "export " + TOKEN_ENV_NAME + "=" + posixQuote(token) + "\n\n"
                + "npx -y autowonder@" + brandingService.recommendedRuntimeVersion() + " workitem upload \\\n"
                + "  --server-url " + posixQuote(brandingService.trustedPublicBaseUrl()) + " \\\n"
                + "  --workitem-id " + workitemId + " \\\n"
                + "  --file <filepath-1> \\\n"
                + "  --file <filepath-2> \\\n"
                + "  --file <images-1> \\\n"
                + "  --json";
    }

    private String powershellCommand(String token, long workitemId) {
        return "$env:" + TOKEN_ENV_NAME + "=" + powershellQuote(token) + "\n\n"
                + "npx -y autowonder@" + brandingService.recommendedRuntimeVersion() + " workitem upload `\n"
                + "  --server-url " + powershellQuote(brandingService.trustedPublicBaseUrl()) + " `\n"
                + "  --workitem-id " + workitemId + " `\n"
                + "  --file <filepath-1> `\n"
                + "  --file <filepath-2> `\n"
                + "  --file <images-1> `\n"
                + "  --json";
    }

    /** Live authorization reused by the CLI upload endpoint: active, write-capable membership. */
    public void requireWriteMembership(long tenantId, long userId) {
        WorkspaceMemberDO member = workspaceMemberDao.findByWorkspaceAndUser(tenantId, userId);
        if (member == null
                || !Integer.valueOf(0).equals(member.getStatus())
                || !Integer.valueOf(0).equals(member.getIsDeleted())) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        WorkspaceAccessLevel level;
        try {
            level = WorkspaceAccessLevel.valueOf(member.getAccessLevel());
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BizException(ErrorCode.NO_PERMISSION);
        }
        if (!WRITE_LEVELS.contains(level)) {
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
