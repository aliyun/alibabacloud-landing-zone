package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.AgentConversationDao;
import com.aliyun.autowonder.conversation.PlatformConversationChannel;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.core.env.Environment;

import java.io.IOException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 会话 MCP 令牌是平台管家对话的唯一身份出口：它必须始终代表会话 Owner 本人，
 * 且任何篡改、过期、跨用户重放、跨租户查询都要以同一个未授权错误失败。
 */
class ConversationMcpTokenServiceTest {
    private static final long TENANT_ID = 10002L;
    private static final long OTHER_TENANT_ID = 20001L;
    private static final long CONVERSATION_ID = 22L;
    private static final long AGENT_ID = 40013L;
    private static final long AGENT_VERSION_ID = 88L;
    private static final long NEXT_AGENT_VERSION_ID = 99L;
    private static final long OWNER_ID = 7001L;
    private static final long INTRUDER_ID = 7002L;
    private static final String SECRET = "test-secret-test-secret-test-secret-test-secret";

    private final JwtService jwtService = jwtService(SECRET);
    private AgentConversationDao conversationDao;
    private ConversationMcpTokenService tokens;

    @BeforeEach
    void setUp() {
        conversationDao = mock(AgentConversationDao.class);
        tokens = new ConversationMcpTokenService(jwtService, conversationDao);
    }

    @Test
    void issueBindsOwnerTenantConversationAndAgentVersionIntoTheToken() {
        Map<String, Object> claims = claims(tokens.issue(platformConversation(OWNER_ID), OWNER_ID));

        assertEquals("conversation-mcp", claims.get("purpose"));
        assertEquals(OWNER_ID, number(claims, "uid"));
        assertEquals(TENANT_ID, number(claims, "workspace"));
        assertEquals(CONVERSATION_ID, number(claims, "subjectId"));
        assertEquals(AGENT_ID, number(claims, "agentId"));
        assertEquals(AGENT_VERSION_ID, number(claims, "agentVersionId"));
    }

    @Test
    void issuedTokenIsShortLivedSoAStolenTokenCannotLiveForever() {
        Map<String, Object> claims = claims(tokens.issue(platformConversation(OWNER_ID), OWNER_ID));

        assertEquals(24 * 60 * 60, number(claims, "exp") - number(claims, "iat"));
    }

    @Test
    void issueRejectsAnIncompleteConversationIdentity() {
        assertThrows(IllegalArgumentException.class, () -> tokens.issue(null, OWNER_ID));
        assertThrows(IllegalArgumentException.class,
                () -> tokens.issue(platformConversation(OWNER_ID), 0L));
        assertThrows(IllegalArgumentException.class,
                () -> tokens.issue(platformConversation(OWNER_ID), -1L));

        List<Consumer<AgentConversationDO>> breakers = List.of(
                row -> row.setId(null),
                row -> row.setTenantId(null),
                row -> row.setAgentId(null),
                row -> row.setAgentVersionId(null));
        for (Consumer<AgentConversationDO> breaker : breakers) {
            AgentConversationDO row = platformConversation(OWNER_ID);
            breaker.accept(row);
            assertThrows(IllegalArgumentException.class, () -> tokens.issue(row, OWNER_ID));
        }
    }

    @Test
    void issueRefusesToImpersonateTheOwner() {
        AgentConversationDO row = platformConversation(OWNER_ID);

        assertThrows(IllegalArgumentException.class, () -> tokens.issue(row, INTRUDER_ID));
    }

    @Test
    void issueRefusesAPlatformConversationWithoutARecordedOwner() {
        assertThrows(IllegalArgumentException.class,
                () -> tokens.issue(platformConversation(null), OWNER_ID));
    }

    @Test
    void issueKeepsServingLegacyChannelsThatHaveNoOwnerColumn() {
        for (String channel : List.of("DINGTALK", "WORKITEM_CLARIFICATION")) {
            AgentConversationDO row = conversation(channel, null);
            String token = tokens.issue(row, INTRUDER_ID);

            assertTrue(token.startsWith(ConversationMcpTokenService.PREFIX), channel);
            assertEquals(INTRUDER_ID, number(claims(token), "uid"), channel);
        }
    }

    @Test
    void authenticateActsAsTheOwnerInsideTheOwnersOwnWorkspace() {
        AgentConversationDO row = platformConversation(OWNER_ID);
        stub(row);

        McpAccessTokenService.Principal principal =
                tokens.authenticate(tokens.issue(row, OWNER_ID));

        assertEquals(TENANT_ID, principal.workspaceId());
        assertEquals(OWNER_ID, principal.userId());
        assertEquals(CONVERSATION_ID, principal.tokenId());
        // 平台会话令牌只有只读上限，通用写工具走不通。
        assertEquals(WorkspaceAccessLevel.READ_ONLY, principal.accessLevel());
        assertEquals(McpAccessTokenService.CredentialType.CONVERSATION, principal.credentialType());
        assertTrue(principal.isWorkspaceScoped());
    }

    @Test
    void theReadOnlyCeilingCannotSatisfyAGenericWriteTool() {
        AgentConversationDO row = platformConversation(OWNER_ID);
        stub(row);

        WorkspaceAccessLevel ceiling =
                tokens.authenticate(tokens.issue(row, OWNER_ID)).accessLevel();

        // 闸门比对的是级别而不是令牌来源，所以只读上限代表谁本人都抬不动写权限。
        assertTrue(ceiling.allows(WorkspaceAccessLevel.READ_ONLY));
        assertFalse(ceiling.allows(WorkspaceAccessLevel.READ_WRITE));
        assertFalse(ceiling.allows(WorkspaceAccessLevel.ADMIN));
    }

    @Test
    void legacyChannelsKeepTheReadWriteCeilingTheyHadBefore() {
        for (String channel : List.of("DINGTALK", "WORKITEM_CLARIFICATION")) {
            AgentConversationDO row = conversation(channel, null);
            stub(row);

            McpAccessTokenService.Principal principal =
                    tokens.authenticate(tokens.issue(row, INTRUDER_ID));

            // 澄清会话要能上传确认稿并推进工单状态，对它降权会直接弄坏既有能力。
            assertEquals(WorkspaceAccessLevel.READ_WRITE, principal.accessLevel(), channel);
        }
    }

    @Test
    void authenticateRejectsTokensWithoutTheConversationPrefix() {
        AgentConversationDO row = platformConversation(OWNER_ID);
        stub(row);
        String token = tokens.issue(row, OWNER_ID);

        assertUnauthorized(() -> tokens.authenticate(null));
        assertUnauthorized(() -> tokens.authenticate(""));
        assertUnauthorized(() -> tokens.authenticate(token.substring(
                ConversationMcpTokenService.PREFIX.length())));
        assertUnauthorized(() -> tokens.authenticate(
                DispatchMcpTokenService.PREFIX + token.substring(
                        ConversationMcpTokenService.PREFIX.length())));
        assertUnauthorized(() -> tokens.authenticate(
                ConversationMcpTokenService.PREFIX + "not-a-jwt"));
    }

    @Test
    void authenticateRejectsATokenWhoseOwnerClaimWasRewritten() {
        AgentConversationDO row = platformConversation(OWNER_ID);
        stub(row);
        // 入侵者手里有一枚签名合法但 uid 是自己的令牌，试图把 uid 改成 Owner。
        String stolen = ConversationMcpTokenService.PREFIX + jwtService.signConversation(
                INTRUDER_ID, TENANT_ID, "conversation-mcp", CONVERSATION_ID,
                AGENT_ID, AGENT_VERSION_ID, 3600L);
        String forged = rewriteClaim(stolen, "uid", OWNER_ID);

        assertUnauthorized(() -> tokens.authenticate(forged));
    }

    @Test
    void authenticateRejectsATokenSignedWithAnotherSecret() {
        AgentConversationDO row = platformConversation(OWNER_ID);
        stub(row);
        ConversationMcpTokenService foreign = new ConversationMcpTokenService(
                jwtService("another-secret-another-secret-another-secret-x"), conversationDao);

        assertUnauthorized(() -> tokens.authenticate(foreign.issue(row, OWNER_ID)));
    }

    @Test
    void authenticateRejectsATokenMintedForAnotherPurpose() {
        AgentConversationDO row = platformConversation(OWNER_ID);
        stub(row);
        String dispatchPurpose = ConversationMcpTokenService.PREFIX + jwtService.signConversation(
                OWNER_ID, TENANT_ID, "dispatch-mcp", CONVERSATION_ID, AGENT_ID, AGENT_VERSION_ID, 60L);

        assertUnauthorized(() -> tokens.authenticate(dispatchPurpose));
    }

    @Test
    void authenticateRejectsTheOlderScopedTokenThatCarriesNoAgentBinding() {
        AgentConversationDO row = platformConversation(OWNER_ID);
        stub(row);
        // 旧格式仍然能签出合法签名，但没有 agent 绑定，不能被当作会话令牌降级使用。
        String legacy = ConversationMcpTokenService.PREFIX + jwtService.signScoped(
                OWNER_ID, TENANT_ID, "conversation-mcp", CONVERSATION_ID, 24 * 60 * 60L);

        assertUnauthorized(() -> tokens.authenticate(legacy));
    }

    @Test
    void authenticateRejectsAMissingOrInactiveConversation() {
        AgentConversationDO row = platformConversation(OWNER_ID);
        String token = tokens.issue(row, OWNER_ID);

        assertUnauthorized(() -> tokens.authenticate(token));

        row.setStatus("CLOSED");
        stub(row);
        assertUnauthorized(() -> tokens.authenticate(token));
    }

    @Test
    void authenticateLooksTheConversationUpInItsOwnTenantOnly() {
        AgentConversationDO row = platformConversation(OWNER_ID);
        String token = tokens.issue(row, OWNER_ID);

        assertUnauthorized(() -> tokens.authenticate(token));

        // 会话必须按令牌里的租户查找，另一个租户下的同号会话永远查不到。
        verify(conversationDao).findById(TENANT_ID, CONVERSATION_ID);
        verifyNoMoreInteractions(conversationDao);
    }

    @Test
    void authenticateRejectsATokenAfterTheConversationMovedToAnotherAgentVersion() {
        AgentConversationDO row = platformConversation(OWNER_ID);
        String token = tokens.issue(row, OWNER_ID);

        row.setAgentVersionId(NEXT_AGENT_VERSION_ID);
        stub(row);
        assertUnauthorized(() -> tokens.authenticate(token));

        row.setAgentVersionId(AGENT_VERSION_ID);
        row.setAgentId(AGENT_ID + 1);
        assertUnauthorized(() -> tokens.authenticate(token));
    }

    @Test
    void authenticateRejectsCrossUserReplayOfAnOwnersToken() {
        // 入侵者先在自己的遗留渠道会话上拿到一枚合法令牌，再拿它去访问 Owner 的平台会话。
        AgentConversationDO row = conversation("DINGTALK", null);
        String stolen = tokens.issue(row, INTRUDER_ID);
        row.setChannel(PlatformConversationChannel.CHANNEL);
        row.setOwnerUserId(OWNER_ID);
        stub(row);

        assertUnauthorized(() -> tokens.authenticate(stolen));
    }

    @Test
    void authenticateRejectsATokenThatNoLongerMatchesTheRecordedOwner() {
        AgentConversationDO row = platformConversation(OWNER_ID);
        stub(row);
        String token = tokens.issue(row, OWNER_ID);

        row.setOwnerUserId(INTRUDER_ID);

        assertUnauthorized(() -> tokens.authenticate(token));
    }

    @Test
    void authenticateRejectsAPlatformConversationWhoseOwnerColumnIsMissing() {
        AgentConversationDO row = platformConversation(OWNER_ID);
        String token = tokens.issue(row, OWNER_ID);
        row.setOwnerUserId(null);
        stub(row);

        assertUnauthorized(() -> tokens.authenticate(token));
    }

    @Test
    void everyRejectionSurfacesAsTheSameUnauthorizedErrorWithoutLeakingTheReason() {
        AgentConversationDO row = platformConversation(OWNER_ID);
        stub(row);
        String token = tokens.issue(row, OWNER_ID);
        List<String> rejected = List.of(
                token.substring(ConversationMcpTokenService.PREFIX.length()),
                ConversationMcpTokenService.PREFIX + "tampered",
                rewriteClaim(token, "workspace", OTHER_TENANT_ID),
                rewriteClaim(token, "subjectId", CONVERSATION_ID + 1));

        for (String candidate : rejected) {
            BizException exception =
                    assertThrows(BizException.class, () -> tokens.authenticate(candidate));
            assertEquals(ErrorCode.UNAUTHORIZED.getCode(), exception.getCode());
            assertEquals(ErrorCode.UNAUTHORIZED.getMessage(), exception.getMessage());
            assertNull(exception.getCause(), "内部失败原因不能挂到异常链上");
        }
    }

    private void stub(AgentConversationDO row) {
        when(conversationDao.findById(row.getTenantId(), row.getId())).thenReturn(row);
    }

    private static AgentConversationDO platformConversation(Long ownerUserId) {
        return conversation(PlatformConversationChannel.CHANNEL, ownerUserId);
    }

    private static AgentConversationDO conversation(String channel, Long ownerUserId) {
        AgentConversationDO row = new AgentConversationDO();
        row.setId(CONVERSATION_ID);
        row.setTenantId(TENANT_ID);
        row.setChannel(channel);
        row.setOwnerUserId(ownerUserId);
        row.setAgentId(AGENT_ID);
        row.setAgentVersionId(AGENT_VERSION_ID);
        row.setStatus("ACTIVE");
        return row;
    }

    private static long number(Map<String, Object> claims, String name) {
        return ((Number) claims.get(name)).longValue();
    }

    private static Map<String, Object> claims(String token) {
        String[] parts = jwtSegments(token);
        try {
            return new ObjectMapper().readValue(Base64.getUrlDecoder().decode(parts[1]),
                    new TypeReference<Map<String, Object>>() { });
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 只改声明不重签名，用来验证令牌内容确实受签名保护。
     */
    private static String rewriteClaim(String token, String claim, long value) {
        String[] parts = jwtSegments(token);
        String json = new String(Base64.getUrlDecoder().decode(parts[1]), UTF_8);
        String patched = json.replaceAll("\"" + claim + "\":-?\\d+", "\"" + claim + "\":" + value);
        assertNotEquals(json, patched, "claim [" + claim + "] must actually be rewritten");
        parts[1] = Base64.getUrlEncoder().withoutPadding().encodeToString(patched.getBytes(UTF_8));
        return ConversationMcpTokenService.PREFIX + String.join(".", parts);
    }

    private static String[] jwtSegments(String token) {
        String[] parts = token.substring(ConversationMcpTokenService.PREFIX.length()).split("\\.");
        assertEquals(3, parts.length, "a JWT must keep its three segments");
        return parts;
    }

    private static JwtService jwtService(String secret) {
        Environment environment = mock(Environment.class);
        when(environment.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties properties = new JwtProperties(environment);
        properties.setSecret(secret);
        return new JwtService(properties);
    }

    private static void assertUnauthorized(Executable executable) {
        BizException exception = assertThrows(BizException.class, executable);
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), exception.getCode());
    }
}
