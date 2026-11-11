package com.aliyun.autowonder.mcp;

import com.aliyun.autowonder.auth.jwt.*;
import com.aliyun.autowonder.conversation.*;
import com.aliyun.autowonder.user.*;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ConversationTurnCredentialTest {
    AgentConversationDao conversations = mock(AgentConversationDao.class);
    AgentConversationTurnDao turns = mock(AgentConversationTurnDao.class);
    UserDao users = mock(UserDao.class);
    AgentConversationDO conversation = new AgentConversationDO();
    AgentConversationTurnDO turn = new AgentConversationTurnDO();
    UserDO user = new UserDO();
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
        user.setId(70L); user.setStatus(0);
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

    /**
     * 缺陷回归：同一个未过期 token，正常可用；数据库瞬时异常不能被报成“未登录或登录已失效”；
     * 依赖恢复后原 token 立即可用，不需要续期或换 token。
     */
    @Test void transientConversationReadFailureIsNotReportedAsCredentialExpiry() {
        String token=service.issueForTurn(conversation,60L);
        assertEquals(70L,service.authenticate(token).userId());
        doThrow(new TransientDataAccessResourceException("injected transient database failure"))
                .when(conversations).findById(20L,10L);
        BizException failure=assertThrows(BizException.class,()->service.authenticate(token));
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(),failure.getCode());
        assertNotEquals(ErrorCode.UNAUTHORIZED.getCode(),failure.getCode());
        assertTrue(failure.getCause() instanceof DataAccessException);
        assertSafeDependencyDiagnostic(failure,"injected transient database failure",token);
        doReturn(conversation).when(conversations).findById(20L,10L);
        assertEquals(70L,service.authenticate(token).userId());
        assertEquals(60L,service.authenticate(token).executionScope().turnId());
    }

    @Test void turnAndActorReadFailuresStayDependencyErrorsAndRecover() {
        String token=service.issueForTurn(conversation,60L);
        doThrow(new QueryTimeoutException("injected turn read timeout"))
                .when(turns).findByConversationTurn(20L,10L,60L);
        BizException turnFailure=assertThrows(BizException.class,()->service.authenticate(token));
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(),turnFailure.getCode());
        assertSafeDependencyDiagnostic(turnFailure,"injected turn read timeout",token);
        doReturn(turn).when(turns).findByConversationTurn(20L,10L,60L);
        assertEquals(70L,service.authenticate(token).userId());

        doThrow(new CannotGetJdbcConnectionException("injected connection failure")).when(users).findById(70L);
        BizException userFailure=assertThrows(BizException.class,()->service.authenticate(token));
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(),userFailure.getCode());
        assertSafeDependencyDiagnostic(userFailure,"injected connection failure",token);
        doReturn(user).when(users).findById(70L);
        assertEquals(70L,service.authenticate(token).userId());
    }

    /** 真正的凭据问题仍按既有规则拒绝：同一个 10401，且不带异常链。 */
    @Test void genuineCredentialRejectionsKeepTheUnauthorizedClassification() {
        String token=service.issueForTurn(conversation,60L);
        turn.setStatus("SUCCESS");
        BizException bindFailure=assertThrows(BizException.class,()->service.authenticate(token));
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(),bindFailure.getCode());
        assertNull(bindFailure.getCause());
        turn.setStatus("PROCESSING");
        String expired=ConversationMcpTokenService.PREFIX+jwt.signConversationTurn(70,20,
                ConversationMcpTokenService.TURN_PURPOSE,
                jwt.parseConversationTurn(token.substring(ConversationMcpTokenService.PREFIX.length())).scope(),null,-1);
        BizException expiredFailure=assertThrows(BizException.class,()->service.authenticate(expired));
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(),expiredFailure.getCode());
        assertNull(expiredFailure.getCause());
    }

    /** 审计写入失败只能丢审计行，不能把依赖故障改写成别的分类，也不能反过来吞掉凭据拒绝。 */
    @Test void auditFailureNeverMasksTheOriginalClassification() {
        var audit=mock(com.aliyun.autowonder.audit.AuditLogService.class);
        doThrow(new IllegalStateException("audit store down")).when(audit).record(any());
        org.springframework.test.util.ReflectionTestUtils.setField(service,"executionAudit",
                new ConversationCredentialAuditService(audit));
        String token=service.issueForTurn(conversation,60L);
        doThrow(new TransientDataAccessResourceException("injected transient database failure"))
                .when(conversations).findById(20L,10L);
        assertEquals(ErrorCode.SYSTEM_ERROR.getCode(),
                assertThrows(BizException.class,()->service.authenticate(token)).getCode());
        doReturn(conversation).when(conversations).findById(20L,10L);
        turn.setStatus("SUCCESS");
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(),
                assertThrows(BizException.class,()->service.authenticate(token)).getCode());
    }

    /** 失败审计仍要留下凭据派生标识与异常类别，但不含 token 或异常正文。 */
    @Test void dependencyFailureAuditKeepsTheCauseCategoryWithoutSecrets() {
        var audit=mock(com.aliyun.autowonder.audit.AuditLogService.class);
        org.springframework.test.util.ReflectionTestUtils.setField(service,"executionAudit",
                new ConversationCredentialAuditService(audit));
        String token=service.issueForTurn(conversation,60L);
        doThrow(new TransientDataAccessResourceException("injected transient database failure"))
                .when(users).findById(70L);
        assertThrows(BizException.class,()->service.authenticate(token));
        var record=org.mockito.ArgumentCaptor.forClass(com.aliyun.autowonder.audit.AuditLogRecord.class);
        verify(audit).record(record.capture());
        assertEquals(70L,record.getValue().getActorId());
        assertEquals(60L,record.getValue().getDetail().get("turnId"));
        assertEquals(false,record.getValue().getDetail().get("success"));
        assertEquals("TransientDataAccessResourceException",record.getValue().getDetail().get("error"));
        String details=record.getValue().getDetail().toString();
        assertFalse(details.contains("injected transient database failure"));
        assertFalse(details.contains(token));
    }

    /** 对客户端可见的依赖错误文案不含注入的异常正文、SQL 片段或 token。 */
    private static void assertSafeDependencyDiagnostic(BizException failure,String injected,String token) {
        String message=failure.getMessage();
        assertNotNull(message);
        assertFalse(message.contains(injected));
        assertFalse(message.contains(token));
        assertFalse(message.contains("未登录"));
        assertFalse(message.contains(ErrorCode.UNAUTHORIZED.getMessage()));
    }
}
