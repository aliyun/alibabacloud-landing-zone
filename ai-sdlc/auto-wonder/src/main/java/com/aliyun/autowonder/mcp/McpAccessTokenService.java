package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.mcp.dto.IssuedMcpTokenVO;
import com.aliyun.autowonder.mcp.dto.McpAccessTokenVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

@Service
public class McpAccessTokenService {
    private static final String TOKEN_PREFIX = "awmcp_";
    private static final Pattern TOKEN_PATTERN =
            Pattern.compile("^awmcp_[A-Za-z0-9_-]{43}$");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final McpAccessTokenDao tokenDao;
    private final DispatchMcpTokenService dispatchTokenService;
    private final ConversationMcpTokenService conversationTokenService;

    public McpAccessTokenService(McpAccessTokenDao tokenDao) {
        this(tokenDao, null, null);
    }

    public McpAccessTokenService(McpAccessTokenDao tokenDao,
                                 DispatchMcpTokenService dispatchTokenService) {
        this(tokenDao, dispatchTokenService, null);
    }

    @Autowired
    public McpAccessTokenService(McpAccessTokenDao tokenDao,
                                 DispatchMcpTokenService dispatchTokenService,
                                 ConversationMcpTokenService conversationTokenService) {
        this.tokenDao = tokenDao;
        this.dispatchTokenService = dispatchTokenService;
        this.conversationTokenService = conversationTokenService;
    }

    @Transactional
    public IssuedMcpTokenVO issue(String name, long userId) {
        String token = generateToken();
        McpAccessTokenDO row = new McpAccessTokenDO();
        row.setUserId(userId);
        row.setName(normalizeName(name));
        row.setTokenHash(hash(token));
        row.setTokenPrefix(displayPrefix(token));
        row.setCreatorId(userId);
        row.setVersion(0);
        tokenDao.insert(row);

        IssuedMcpTokenVO vo = new IssuedMcpTokenVO();
        copy(row, vo);
        vo.setToken(token);
        return vo;
    }

    public List<McpAccessTokenVO> list(long userId) {
        List<McpAccessTokenVO> result = new ArrayList<>();
        for (McpAccessTokenDO row : tokenDao.listByUser(userId)) {
            McpAccessTokenVO vo = new McpAccessTokenVO();
            copy(row, vo);
            result.add(vo);
        }
        return result;
    }

    @Transactional
    public void revoke(long id, long userId) {
        McpAccessTokenDO row = tokenDao.findById(id, userId);
        if (row == null) {
            throw new BizException(ErrorCode.MCP_TOKEN_NOT_FOUND);
        }
        if (tokenDao.revoke(id, userId, new Date(), userId) != 1) {
            throw new BizException(ErrorCode.MCP_TOKEN_NOT_FOUND);
        }
    }

    public Principal authenticate(String authorizationHeader, String queryToken) {
        String token = normalizeQueryToken(queryToken);
        if (token != null) {
            return authenticatePlainToken(token);
        }
        return authenticateBearer(authorizationHeader);
    }

    public Principal authenticateBearer(String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.startsWith("Bearer ")) {
            throw unauthorized();
        }
        String token = authorizationHeader.substring("Bearer ".length()).trim();
        return authenticatePlainToken(token);
    }

    private Principal authenticatePlainToken(String token) {
        if (token != null && token.startsWith(DispatchMcpTokenService.PREFIX)) {
            if (dispatchTokenService == null) {
                throw unauthorized();
            }
            return dispatchTokenService.authenticate(token);
        }
        if (token != null && token.startsWith(ConversationMcpTokenService.PREFIX)) {
            if (conversationTokenService == null) {
                throw unauthorized();
            }
            return conversationTokenService.authenticate(token);
        }
        if (token == null || !TOKEN_PATTERN.matcher(token).matches()) {
            throw unauthorized();
        }

        McpAccessTokenDO row = tokenDao.findByHash(hash(token));
        if (row == null || row.getRevokedAt() != null) {
            throw unauthorized();
        }
        if (tokenDao.touchLastUsed(row.getId(), new Date()) != 1) {
            throw unauthorized();
        }
        return Principal.personal(row.getUserId(), row.getId());
    }

    private String normalizeQueryToken(String token) {
        if (token == null) {
            return null;
        }
        String trimmed = token.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String generateToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return TOKEN_PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String normalizeName(String name) {
        String normalized = name == null ? "" : name.trim();
        return normalized.isEmpty() ? "MCP Token" : normalized;
    }

    private String displayPrefix(String token) {
        return token.substring(0, Math.min(token.length(), 16));
    }

    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private void copy(McpAccessTokenDO row, McpAccessTokenVO vo) {
        vo.setId(row.getId());
        vo.setUserId(row.getUserId());
        vo.setName(row.getName());
        vo.setTokenPrefix(row.getTokenPrefix());
        vo.setLastUsedAt(row.getLastUsedAt());
        vo.setRevokedAt(row.getRevokedAt());
        vo.setGmtCreate(row.getGmtCreate());
    }

    private BizException unauthorized() {
        return new BizException(ErrorCode.UNAUTHORIZED);
    }

    public enum CredentialType {
        LONG_LIVED,
        DISPATCH,
        CONVERSATION
    }

    /**
     * A personal credential authenticates only its owner; the workspace and access level are
     * resolved per tool call from the caller's live membership in the requested workspace.
     * Task-scoped credentials stay pinned to the workspace they were issued for.
     */
    public record Principal(Long tenantId, long userId, long tokenId,
                            WorkspaceAccessLevel accessLevel,
                            CredentialType credentialType) {

        public Principal {
            boolean personal = credentialType == CredentialType.LONG_LIVED;
            if (personal != (tenantId == null) || personal != (accessLevel == null)) {
                throw new IllegalArgumentException(
                        "personal credentials carry no workspace, task-scoped ones must");
            }
        }

        public static Principal personal(long userId, long tokenId) {
            return new Principal(null, userId, tokenId, null,
                    CredentialType.LONG_LIVED);
        }

        public boolean isWorkspaceScoped() {
            return credentialType != CredentialType.LONG_LIVED;
        }
    }
}
