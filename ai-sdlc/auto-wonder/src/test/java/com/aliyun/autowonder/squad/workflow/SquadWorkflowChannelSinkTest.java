package com.aliyun.autowonder.squad.workflow;

import com.aliyun.autowonder.conversation.AgentConversationDO;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SquadWorkflowChannelSinkTest {

    private final SquadWorkflowFinalizer finalizer = mock(SquadWorkflowFinalizer.class);
    private final SquadWorkflowChannelSink sink = new SquadWorkflowChannelSink(finalizer);

    @Test
    void declaresTheWorkflowChannel() {
        assertEquals(SquadWorkflowChannelSink.CHANNEL, sink.channel());
    }

    @Test
    void repliesHaveNoExternalTransport() {
        assertDoesNotThrow(() -> sink.deliverReply(new AgentConversationDO(), "reply", "ext-1"));
    }

    @Test
    void aTerminalTurnConvergesTheOwningSnapshot() {
        AgentConversationDO conversation = new AgentConversationDO();
        conversation.setId(77L);
        conversation.setTenantId(100L);

        sink.turnTerminal(conversation, "FAILED", null);

        verify(finalizer).conversationTerminal(100L, 77L, "FAILED", null);
    }

    @Test
    void executorDiagnosticReachesTheSnapshotFinalizer() {
        AgentConversationDO conversation = new AgentConversationDO();
        conversation.setId(77L);
        conversation.setTenantId(100L);
        sink.turnTerminal(conversation, "FAILED", "partial reply", "qoder preflight: dead owner pid 38553");
        verify(finalizer).conversationTerminal(100L, 77L, "FAILED", "qoder preflight: dead owner pid 38553");
    }

    @Test
    void incompleteConversationsAreIgnored() {
        sink.turnTerminal(null, "FAILED", null);
        sink.turnTerminal(new AgentConversationDO(), "FAILED", null);

        AgentConversationDO noTenant = new AgentConversationDO();
        noTenant.setId(77L);
        sink.turnTerminal(noTenant, "FAILED", null);

        verify(finalizer, never()).conversationTerminal(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }
}
