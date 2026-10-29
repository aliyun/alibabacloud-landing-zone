package com.aliyun.autowonder.squad.workflow;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.AgentConversationDao;
import com.aliyun.autowonder.mcp.McpAccessTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 提交工具只接受属于本次工作流会话、且由会话 Owner 本人签发的对话凭证；
 * 任何普通工作空间凭证或他人令牌都必须在进入快照状态机前被挡住。
 */
class SquadWorkflowSubmissionGuardTest {

    private static final long WS = 100L;
    private static final long CONV = 77L;
    private static final long USER = 7L;

    private AgentConversationDao conversationDao;
    private SquadWorkflowService workflowService;
    private SquadWorkflowSubmissionGuard guard;

    @BeforeEach
    void setUp() {
        conversationDao = mock(AgentConversationDao.class);
        workflowService = mock(SquadWorkflowService.class);
        guard = new SquadWorkflowSubmissionGuard(conversationDao, workflowService);
    }

    @Test
    void onlyActiveWorkflowConversationsAreRecognised() {
        assertFalse(guard.isWorkflowConversation(null, CONV));
        assertFalse(guard.isWorkflowConversation(WS, null));

        when(conversationDao.findById(WS, CONV)).thenReturn(null);
        assertFalse(guard.isWorkflowConversation(WS, CONV));

        AgentConversationDO dingTalk = conversation(SquadWorkflowChannelSink.CHANNEL, USER, "ACTIVE");
        dingTalk.setChannel("DINGTALK");
        when(conversationDao.findById(WS, CONV)).thenReturn(dingTalk);
        assertFalse(guard.isWorkflowConversation(WS, CONV));

        when(conversationDao.findById(WS, CONV))
                .thenReturn(conversation(SquadWorkflowChannelSink.CHANNEL, USER, "CLOSED"));
        assertFalse(guard.isWorkflowConversation(WS, CONV));

        when(conversationDao.findById(WS, CONV))
                .thenReturn(conversation(SquadWorkflowChannelSink.CHANNEL, USER, "ACTIVE"));
        assertTrue(guard.isWorkflowConversation(WS, CONV));
    }

    @Test
    void nonConversationCredentialsCannotSubmit() {
        assertEquals(ErrorCode.SQUAD_WORKFLOW_NOT_AVAILABLE.getCode(),
                assertThrows(BizException.class,
                        () -> guard.submit(null, args())).getCode());
        assertEquals(ErrorCode.SQUAD_WORKFLOW_NOT_AVAILABLE.getCode(),
                assertThrows(BizException.class, () -> guard.submit(
                        McpAccessTokenService.Principal.personal(USER, 1L), args())).getCode());
        verify(conversationDao, never()).findById(anyLong(), anyLong());
    }

    @Test
    void theConversationMustBeOwnedByTheCaller() {
        when(conversationDao.findById(WS, CONV))
                .thenReturn(conversation(SquadWorkflowChannelSink.CHANNEL, USER + 1, "ACTIVE"));

        assertEquals(ErrorCode.SQUAD_WORKFLOW_NOT_AVAILABLE.getCode(),
                assertThrows(BizException.class, () -> guard.submit(principal(USER), args())).getCode());

        when(conversationDao.findById(WS, CONV))
                .thenReturn(conversation("DINGTALK", USER, "ACTIVE"));
        assertThrows(BizException.class, () -> guard.submit(principal(USER), args()));

        when(conversationDao.findById(WS, CONV))
                .thenReturn(conversation(SquadWorkflowChannelSink.CHANNEL, USER, "CLOSED"));
        assertThrows(BizException.class, () -> guard.submit(principal(USER), args()));

        when(conversationDao.findById(WS, CONV)).thenReturn(null);
        assertThrows(BizException.class, () -> guard.submit(principal(USER), args()));

        verify(workflowService, never()).acceptSubmission(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void everySubmissionFieldIsRequired() {
        when(conversationDao.findById(WS, CONV))
                .thenReturn(conversation(SquadWorkflowChannelSink.CHANNEL, USER, "ACTIVE"));

        for (String missing : new String[]{"generationId", "attemptId", "submissionId", "payloadJson"}) {
            Map<String, Object> args = args();
            args.remove(missing);
            assertEquals(ErrorCode.SQUAD_WORKFLOW_INVALID_REQUEST.getCode(),
                    assertThrows(BizException.class,
                            () -> guard.submit(principal(USER), args)).getCode(), missing);
        }
        Map<String, Object> nonString = args();
        nonString.put("attemptId", 42);
        assertThrows(BizException.class, () -> guard.submit(principal(USER), nonString));
        assertThrows(BizException.class, () -> guard.submit(principal(USER), null));
    }

    @Test
    void anOversizedPayloadIsRejectedBeforeTouchingTheSnapshot() {
        when(conversationDao.findById(WS, CONV))
                .thenReturn(conversation(SquadWorkflowChannelSink.CHANNEL, USER, "ACTIVE"));
        Map<String, Object> args = args();
        args.put("payloadJson", "x".repeat(1024 * 1024 + 1));

        assertEquals(ErrorCode.SQUAD_WORKFLOW_INPUT_TOO_LARGE.getCode(),
                assertThrows(BizException.class, () -> guard.submit(principal(USER), args)).getCode());
        verify(workflowService, never()).acceptSubmission(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void aValidSubmissionDelegatesToTheSnapshotStateMachine() {
        AgentConversationDO conversation =
                conversation(SquadWorkflowChannelSink.CHANNEL, USER, "ACTIVE");
        when(conversationDao.findById(WS, CONV)).thenReturn(conversation);
        WorkflowFeedback feedback = new WorkflowFeedback(WorkflowFeedback.REJECTED, "s1",
                java.util.List.of(), "bad", null);
        when(workflowService.acceptSubmission(WS, CONV, "42", "attempt-1", "s1", "{\"a\":1}"))
                .thenReturn(feedback);

        assertSame(feedback, guard.submit(principal(USER), args()));

        verify(workflowService).acceptSubmission(WS, CONV, "42", "attempt-1", "s1", "{\"a\":1}");
        verify(conversationDao).findById(eq(WS), eq(CONV));
    }

    private McpAccessTokenService.Principal principal(long userId) {
        return new McpAccessTokenService.Principal(WS, userId, CONV,
                com.aliyun.autowonder.access.WorkspaceAccessLevel.READ_ONLY,
                McpAccessTokenService.CredentialType.CONVERSATION);
    }

    private AgentConversationDO conversation(String channel, long owner, String status) {
        AgentConversationDO conversation = new AgentConversationDO();
        conversation.setId(CONV);
        conversation.setTenantId(WS);
        conversation.setChannel(channel);
        conversation.setOwnerUserId(owner);
        conversation.setStatus(status);
        return conversation;
    }

    private Map<String, Object> args() {
        Map<String, Object> args = new HashMap<>();
        args.put("generationId", "42");
        args.put("attemptId", "attempt-1");
        args.put("submissionId", "s1");
        args.put("payloadJson", "{\"a\":1}");
        return args;
    }
}
