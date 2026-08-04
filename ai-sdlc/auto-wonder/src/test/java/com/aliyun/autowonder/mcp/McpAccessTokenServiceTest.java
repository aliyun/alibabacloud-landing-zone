package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.AgentConversationDao;
import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchDao;
import com.aliyun.autowonder.mcp.dto.IssuedMcpTokenVO;
import com.aliyun.autowonder.mcp.dto.McpAccessTokenVO;
import com.aliyun.autowonder.org.OrgMemberDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Field;
import java.util.Date;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class McpAccessTokenServiceTest {
    private static final long TENANT_ID = 100L;
    private static final long USER_ID = 7L;
    private static final long OTHER_USER_ID = 8L;
    private static final String LONG_TOKEN = "awmcp_" + "a".repeat(43);

    private McpAccessTokenDao tokenDao;
    private DispatchMcpTokenService dispatchTokenService;
    private McpAccessTokenService service;

    @BeforeEach
    void setUp() {
        tokenDao = mock(McpAccessTokenDao.class);
        dispatchTokenService = mock(DispatchMcpTokenService.class);
        service = new McpAccessTokenService(tokenDao, dispatchTokenService);
    }

    private static Environment mockEnvironment() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        return env;
    }

    @Test
    void issuePersistsOnlyTheOwnerWithoutOrganizationOrLevel() {
        doAnswer(invocation -> {
            ((McpAccessTokenDO) invocation.getArgument(0)).setId(10L);
            return null;
        }).when(tokenDao).insert(any(McpAccessTokenDO.class));

        IssuedMcpTokenVO issued = service.issue("  local-codex  ", USER_ID);

        ArgumentCaptor<McpAccessTokenDO> captor =
                ArgumentCaptor.forClass(McpAccessTokenDO.class);
        verify(tokenDao).insert(captor.capture());
        McpAccessTokenDO row = captor.getValue();
        assertEquals(USER_ID, row.getUserId());
        assertEquals(USER_ID, row.getCreatorId());
        assertEquals("local-codex", row.getName());
        assertNull(row.getTenantId());
        assertNotNull(row.getTokenHash());

        assertEquals("local-codex", issued.getName());
        assertEquals(USER_ID, issued.getUserId());
        assertTrue(issued.getToken().startsWith("awmcp_"));
        assertTrue(issued.getToken().startsWith(issued.getTokenPrefix()));
        assertFalse(issued.getToken().equals(row.getTokenHash()));
    }

    @Test
    void issueNeedsNoOrganizationMembershipLookup() {
        service.issue("token", USER_ID);

        verify(tokenDao).insert(any(McpAccessTokenDO.class));
    }

    @Test
    void issueFallsBackToADefaultNameWhenBlank() {
        service.issue("   ", USER_ID);

        ArgumentCaptor<McpAccessTokenDO> captor =
                ArgumentCaptor.forClass(McpAccessTokenDO.class);
        verify(tokenDao).insert(captor.capture());
        assertEquals("MCP Token", captor.getValue().getName());
    }

    @Test
    void listReturnsEveryPersonalTokenOfTheOwnerRegardlessOfOrganization() {
        when(tokenDao.listByUser(USER_ID))
                .thenReturn(List.of(tokenRow(1L, "first"), tokenRow(2L, "second")));

        List<McpAccessTokenVO> listed = service.list(USER_ID);

        assertEquals(List.of("first", "second"),
                listed.stream().map(McpAccessTokenVO::getName).toList());
        verify(tokenDao).listByUser(USER_ID);
    }

    @Test
    void tokenViewObjectNoLongerExposesAnyAccessLevel() {
        Set<String> fields = Set.of(McpAccessTokenVO.class.getDeclaredFields())
                .stream()
                .map(Field::getName)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());

        assertFalse(fields.contains("accessLevel"));
        assertFalse(fields.contains("effectiveAccessLevel"));
        assertFalse(fields.contains("tenantId"));
        assertTrue(fields.containsAll(
                Set.of("id", "name", "tokenPrefix", "lastUsedAt", "revokedAt", "gmtCreate")));
    }

    @Test
    void revokeIsScopedToTheOwningUser() {
        McpAccessTokenDO row = tokenRow(9L, "agent");
        when(tokenDao.findById(9L, USER_ID)).thenReturn(row);
        when(tokenDao.revoke(eq(9L), eq(USER_ID), any(), eq(USER_ID))).thenReturn(1);

        service.revoke(9L, USER_ID);

        InOrder order = inOrder(tokenDao);
        order.verify(tokenDao).findById(9L, USER_ID);
        order.verify(tokenDao).revoke(eq(9L), eq(USER_ID), any(), eq(USER_ID));
    }

    @Test
    void revokeCannotTouchAnotherUsersToken() {
        when(tokenDao.findById(9L, USER_ID)).thenReturn(tokenRow(9L, "agent"));
        when(tokenDao.findById(9L, OTHER_USER_ID)).thenReturn(null);

        assertCode("27001", () -> service.revoke(9L, OTHER_USER_ID));

        verify(tokenDao).findById(9L, OTHER_USER_ID);
        verify(tokenDao, never()).revoke(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void revokeRejectsMissingOrAlreadyRevokedToken() {
        assertCode("27001", () -> service.revoke(9L, USER_ID));
        verify(tokenDao, never()).revoke(anyLong(), anyLong(), any(), anyLong());

        when(tokenDao.findById(9L, USER_ID)).thenReturn(tokenRow(9L, "agent"));
        when(tokenDao.revoke(eq(9L), eq(USER_ID), any(), eq(USER_ID))).thenReturn(0);
        assertCode("27001", () -> service.revoke(9L, USER_ID));
    }

    @Test
    void tokenMutationsStayTransactionalOnTheirNewSignatures() throws Exception {
        assertNotNull(McpAccessTokenService.class
                .getDeclaredMethod("issue", String.class, long.class)
                .getAnnotation(Transactional.class));
        assertNotNull(McpAccessTokenService.class
                .getDeclaredMethod("revoke", long.class, long.class)
                .getAnnotation(Transactional.class));
    }

    @Test
    void migratedLongLivedTokenAuthenticatesWithoutOrganizationOrLevel() {
        McpAccessTokenDO row = tokenRow(1L, "legacy");
        when(tokenDao.findByHash(McpAccessTokenService.hash(LONG_TOKEN))).thenReturn(row);
        when(tokenDao.touchLastUsed(eq(1L), any())).thenReturn(1);

        McpAccessTokenService.Principal principal =
                service.authenticateBearer("Bearer " + LONG_TOKEN);

        assertNull(principal.tenantId());
        assertNull(principal.accessLevel());
        assertFalse(principal.isOrgScoped());
        assertEquals(USER_ID, principal.userId());
        assertEquals(1L, principal.tokenId());
        assertEquals(McpAccessTokenService.CredentialType.LONG_LIVED,
                principal.credentialType());
    }

    @Test
    void authenticateAcceptsTheSameTokenThroughTheQueryParameter() {
        McpAccessTokenDO row = tokenRow(1L, "legacy");
        when(tokenDao.findByHash(McpAccessTokenService.hash(LONG_TOKEN))).thenReturn(row);
        when(tokenDao.touchLastUsed(eq(1L), any())).thenReturn(1);

        McpAccessTokenService.Principal principal =
                service.authenticate(null, " " + LONG_TOKEN + " ");

        assertEquals(USER_ID, principal.userId());
        verify(tokenDao).touchLastUsed(eq(1L), any());
    }

    @Test
    void authenticateRejectsMalformedMissingAndRevokedTokens() {
        assertUnauthorized(() -> service.authenticateBearer(null));
        assertUnauthorized(() -> service.authenticateBearer("Bearer not-mcp"));
        assertUnauthorized(() -> service.authenticateBearer("Bearer awmcp_short"));
        assertUnauthorized(() -> service.authenticateBearer("Bearer " + LONG_TOKEN));

        McpAccessTokenDO revoked = tokenRow(1L, "revoked");
        revoked.setRevokedAt(new Date());
        when(tokenDao.findByHash(anyString())).thenReturn(revoked);
        assertUnauthorized(() -> service.authenticateBearer("Bearer " + LONG_TOKEN));

        verify(tokenDao, never()).touchLastUsed(anyLong(), any());
    }

    @Test
    void authenticateRejectsTokenRevokedBetweenLookupAndTouch() {
        McpAccessTokenDO row = tokenRow(3L, "racy");
        when(tokenDao.findByHash(McpAccessTokenService.hash(LONG_TOKEN))).thenReturn(row);
        when(tokenDao.touchLastUsed(eq(3L), any())).thenReturn(0);

        assertUnauthorized(() -> service.authenticateBearer("Bearer " + LONG_TOKEN));

        verify(tokenDao).touchLastUsed(eq(3L), any());
    }

    @Test
    void conversationTokenStaysScopedToItsOrganization() {
        AgentConversationDao conversationDao = mock(AgentConversationDao.class);
        AgentConversationDO conversation = new AgentConversationDO();
        conversation.setId(22L);
        conversation.setTenantId(TENANT_ID);
        conversation.setStatus("ACTIVE");
        when(conversationDao.findById(TENANT_ID, 22L)).thenReturn(conversation);
        ConversationMcpTokenService tokens = new ConversationMcpTokenService(
                testJwtService(), conversationDao);
        String token = tokens.issue(conversation, USER_ID);

        McpAccessTokenService authService = new McpAccessTokenService(
                mock(McpAccessTokenDao.class), null, tokens);
        McpAccessTokenService.Principal principal =
                authService.authenticateBearer("Bearer " + token);

        assertEquals(TENANT_ID, principal.tenantId());
        assertTrue(principal.isOrgScoped());
        assertEquals(USER_ID, principal.userId());
        assertEquals(OrgAccessLevel.READ_WRITE, principal.accessLevel());
        assertEquals(McpAccessTokenService.CredentialType.CONVERSATION,
                principal.credentialType());

        conversation.setStatus("CLOSED");
        assertThrows(BizException.class,
                () -> authService.authenticateBearer("Bearer " + token));
    }

    @Test
    void dispatchPrincipalStaysOrganizationScopedReadWrite() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(99L);
        dispatch.setTenantId(TENANT_ID);
        dispatch.setCreatorId(USER_ID);
        dispatch.setStatus("RUNNING");
        when(dispatchDao.findById(99L)).thenReturn(dispatch);
        DispatchMcpTokenService dispatchTokens = new DispatchMcpTokenService(
                testJwtService(), dispatchDao, mock(WorkitemDao.class), mock(OrgMemberDao.class));
        String token = dispatchTokens.issue(dispatch);

        McpAccessTokenService authService =
                new McpAccessTokenService(tokenDao, dispatchTokens);
        McpAccessTokenService.Principal principal =
                authService.authenticateBearer("Bearer " + token);

        assertEquals(TENANT_ID, principal.tenantId());
        assertTrue(principal.isOrgScoped());
        assertEquals(OrgAccessLevel.READ_WRITE, principal.accessLevel());
        assertEquals(McpAccessTokenService.CredentialType.DISPATCH,
                principal.credentialType());

        dispatch.setStatus("SUCCEEDED");
        assertThrows(BizException.class,
                () -> authService.authenticateBearer("Bearer " + token));
    }

    @Test
    void systemCreatedDispatchUsesWorkitemOwnerAsMcpPrincipal() {
        DispatchDao dispatchDao = mock(DispatchDao.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        DispatchDO dispatch = new DispatchDO();
        dispatch.setId(100L);
        dispatch.setTenantId(200L);
        dispatch.setWorkitemId(300L);
        dispatch.setCreatorId(0L);
        dispatch.setStatus("RUNNING");
        WorkitemDO workitem = new WorkitemDO();
        workitem.setId(300L);
        workitem.setTenantId(200L);
        workitem.setCreatorId(8L);
        when(workitemDao.findById(300L)).thenReturn(workitem);
        when(dispatchDao.findById(100L)).thenReturn(dispatch);
        DispatchMcpTokenService tokens = new DispatchMcpTokenService(
                testJwtService(), dispatchDao, workitemDao, mock(OrgMemberDao.class));

        McpAccessTokenService.Principal principal =
                tokens.authenticate(tokens.issue(dispatch));

        assertEquals(8L, principal.userId());
        assertEquals(OrgAccessLevel.READ_WRITE, principal.accessLevel());
        assertEquals(McpAccessTokenService.CredentialType.DISPATCH,
                principal.credentialType());
    }

    @Test
    void dispatchAndConversationPrefixesRequireTheirOwnServices() {
        McpAccessTokenService bare =
                new McpAccessTokenService(mock(McpAccessTokenDao.class));

        assertUnauthorized(() -> bare.authenticateBearer(
                "Bearer " + DispatchMcpTokenService.PREFIX + "x"));
        assertUnauthorized(() -> bare.authenticateBearer(
                "Bearer " + ConversationMcpTokenService.PREFIX + "x"));
    }

    private static JwtService testJwtService() {
        JwtProperties properties = new JwtProperties(mockEnvironment());
        properties.setSecret("test-secret-test-secret-test-secret-test-secret");
        return new JwtService(properties);
    }

    private McpAccessTokenDO tokenRow(long id, String name) {
        McpAccessTokenDO row = new McpAccessTokenDO();
        row.setId(id);
        row.setUserId(USER_ID);
        row.setName(name);
        row.setTokenPrefix("awmcp_abcdef");
        return row;
    }

    private void assertUnauthorized(ThrowingRunnable runnable) {
        assertCode("10401", runnable);
    }

    private void assertCode(String code, ThrowingRunnable runnable) {
        BizException exception = assertThrows(BizException.class, runnable::run);
        assertEquals(code, exception.getCode());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
