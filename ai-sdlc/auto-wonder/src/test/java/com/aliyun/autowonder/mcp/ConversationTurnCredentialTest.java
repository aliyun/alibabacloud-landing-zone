package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.auth.jwt.*;
import com.aliyun.autowonder.conversation.*;
import com.aliyun.autowonder.user.*;
import com.aliyun.autowonder.common.error.BizException;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationTurnCredentialTest {
    AgentConversationDao conversations = mock(AgentConversationDao.class);
    AgentConversationTurnDao turns = mock(AgentConversationTurnDao.class);
    UserDao users = mock(UserDao.class);
    AgentConversationDO conversation = new AgentConversationDO();
    AgentConversationTurnDO turn = new AgentConversationTurnDO();
    ConversationMcpTokenService service;
    JwtService jwt;
    @BeforeEach void setup() {
        JwtProperties properties = new JwtProperties(mock(org.springframework.core.env.Environment.class));
        properties.setSecret("test-secret-test-secret-test-secret-test-secret");
        jwt = new JwtService(properties);
        service = new ConversationMcpTokenService(jwt, conversations, turns, users);
        conversation.setId(10L); conversation.setTenantId(20L); conversation.setAgentId(30L);
        conversation.setAgentVersionId(40L); conversation.setExecutorId(50L);
        conversation.setChannel("WORKITEM_CLARIFICATION"); conversation.setStatus("ACTIVE");
        turn.setId(60L); turn.setTenantId(20L); turn.setConversationId(10L);
        turn.setDirection("IN"); turn.setStatus("PROCESSING"); turn.setDispatchAttempt(1); turn.setSubmittedUserId(70L);
        when(conversations.findById(20L,10L)).thenReturn(conversation);
        when(turns.findByConversationTurn(20L,10L,60L)).thenReturn(turn);
        when(turns.findProcessingInbound(20L,10L)).thenReturn(turn);
        UserDO user = new UserDO(); user.setId(70L); user.setStatus(0);
        when(users.findById(70L)).thenReturn(user);
        when(users.findById(71L)).thenReturn(user);
    }
    @Test void sequentialActorsAreBoundToTheirOwnTurn() {
        String first=service.issueForTurn(conversation,60L);
        var principal=service.authenticate(first);
        assertEquals(70L,principal.userId()); assertEquals(10L,principal.tokenId());
        assertEquals(60L,principal.executionScope().turnId());
        turn.setSubmittedUserId(71L); turn.setDispatchAttempt(2);
        String second=service.issueForTurn(conversation,60L);
        assertEquals(71L,service.authenticate(second).userId());
        assertThrows(BizException.class,()->service.authenticate(first));
    }
    @Test void terminalAttemptExecutorVersionAndTenantChangesInvalidateToken() {
        String token=service.issueForTurn(conversation,60L);
        turn.setStatus("SUCCESS"); assertThrows(BizException.class,()->service.authenticate(token)); turn.setStatus("PROCESSING");
        turn.setDispatchAttempt(2); assertThrows(BizException.class,()->service.authenticate(token)); turn.setDispatchAttempt(1);
        conversation.setExecutorId(51L); assertThrows(BizException.class,()->service.authenticate(token)); conversation.setExecutorId(50L);
        conversation.setAgentVersionId(41L); assertThrows(BizException.class,()->service.authenticate(token)); conversation.setAgentVersionId(40L);
        turn.setTenantId(21L); assertThrows(BizException.class,()->service.authenticate(token));
    }
    @Test void deletedOrDisabledActorIsDenied() {
        String token=service.issueForTurn(conversation,60L);
        UserDO disabled=new UserDO(); disabled.setStatus(1);
        when(users.findById(70L)).thenReturn(disabled);
        assertThrows(BizException.class,()->service.authenticate(token));
        when(users.findById(70L)).thenReturn(null);
        assertThrows(BizException.class,()->service.authenticate(token));
    }
    @Test void missingActorAndInvalidBindingsCannotIssue() {
        turn.setSubmittedUserId(null); assertThrows(RuntimeException.class,()->service.issueForTurn(conversation,60L));
        turn.setSubmittedUserId(0L); assertThrows(RuntimeException.class,()->service.issueForTurn(conversation,60L));
        turn.setSubmittedUserId(70L); turn.setDispatchAttempt(0); assertThrows(RuntimeException.class,()->service.issueForTurn(conversation,60L));
    }
    @Test void legacyClarificationOnlyAuthorizesProcessingTurnWithNoActor() {
        String token=service.issue(conversation,70L);
        assertThrows(BizException.class,()->service.authenticate(token));
        turn.setSubmittedUserId(null);
        assertEquals(70L,service.authenticate(token).userId());
        turn.setStatus("QUEUED"); assertThrows(BizException.class,()->service.authenticate(token));
    }
    @Test void revokedSignedTurnLeavesSafeDenialEvidence() {
        var audit = mock(com.aliyun.autowonder.audit.AuditLogService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"executionAudit",new ConversationCredentialAuditService(audit));
        String token=service.issueForTurn(conversation,60L); turn.setStatus("SUCCESS");
        assertThrows(BizException.class,()->service.authenticate(token));
        var record=org.mockito.ArgumentCaptor.forClass(com.aliyun.autowonder.audit.AuditLogRecord.class);
        verify(audit).record(record.capture());
        assertEquals(70L,record.getValue().getActorId());
        assertEquals(60L,record.getValue().getDetail().get("turnId"));
        assertEquals(false,record.getValue().getDetail().get("success"));
        assertFalse(record.getValue().getDetail().toString().contains(token));
    }
    @Test void distinctPurposePreservesLegacyRejectionAndExpires() {
        String token=service.issueForTurn(conversation,60L);
        var claims=jwt.parseConversationTurn(token.substring(ConversationMcpTokenService.PREFIX.length()));
        assertEquals("conversation-turn-mcp",claims.purpose());
        assertNotEquals("conversation-mcp",jwt.parseConversation(token.substring(ConversationMcpTokenService.PREFIX.length())).purpose());
        String expired=ConversationMcpTokenService.PREFIX+jwt.signConversationTurn(70,20,
                ConversationMcpTokenService.TURN_PURPOSE,claims.scope(),null,-1);
        assertThrows(BizException.class,()->service.authenticate(expired));
        String sameUserOtherTurn=ConversationMcpTokenService.PREFIX+jwt.signConversationTurn(70,20,
                ConversationMcpTokenService.TURN_PURPOSE,new McpAccessTokenService.ExecutionScope(10,61,1,50,30,40),null,60);
        assertThrows(BizException.class,()->service.authenticate(sameUserOtherTurn));
    }
    @Test void chiefKeepsOwnerAndReadOnlyCeiling() {
        conversation.setChannel(PlatformConversationChannel.CHANNEL); conversation.setOwnerUserId(70L);
        var principal=service.authenticate(service.issueForTurn(conversation,60L));
        assertEquals(com.aliyun.autowonder.access.WorkspaceAccessLevel.READ_ONLY,principal.accessLevel());
        turn.setSubmittedUserId(71L); assertThrows(RuntimeException.class,()->service.issueForTurn(conversation,60L));
    }
}
