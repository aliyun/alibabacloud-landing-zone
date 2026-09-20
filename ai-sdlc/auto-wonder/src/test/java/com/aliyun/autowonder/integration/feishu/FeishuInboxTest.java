package com.aliyun.autowonder.integration.feishu;

import com.aliyun.autowonder.conversation.AgentConversationService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeishuInboxTest {
    private static final String BOT_MENTION = "[{\"key\":\"@_user_1\",\"id\":{\"open_id\":\"ou_bot\"},\"name\":\"Bot\"}]";
    @Test void duplicateCallbacksAreDurableAndDispatchOnlyOnce() throws Exception {
        var f = new FeishuTestSupport(); var b = f.create();
        var conversations = mock(AgentConversationService.class); var api = mock(FeishuApiClient.class);
        when(api.botOpenId(b)).thenReturn("ou_bot");
        var inbox = new FeishuInbox(f.jdbc, f.json, f.dao, api, conversations);
        var event = f.event("om_message", "group", BOT_MENTION);
        inbox.accept(b, event); inbox.accept(b, event);
        verifyNoInteractions(api, conversations);
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM feishu_message_inbox", Integer.class));
        inbox.drain(); inbox.drain();
        verify(conversations).submitTurn(eq(1L), eq(7L), eq("FEISHU"), eq(b.getId() + ":oc_chat"), eq("hello"), eq("FEISHU:" + b.getId() + ":om_message"), anyString());
        assertEquals("DONE", f.jdbc.queryForObject("SELECT status FROM feishu_message_inbox", String.class));
    }
    @Test void ignoresGroupMessagesWithoutBotMentionAndAcceptsPrivateChat() throws Exception {
        var f = new FeishuTestSupport(); var b = f.create(); var api = mock(FeishuApiClient.class);
        when(api.botOpenId(b)).thenReturn("ou_other_bot");
        var conversations = mock(AgentConversationService.class);
        var inbox = new FeishuInbox(f.jdbc, f.json, f.dao, api, conversations);
        inbox.dispatch(b, f.event("om_group", "group", BOT_MENTION));
        verifyNoInteractions(conversations);
        inbox.dispatch(b, f.event("om_private", "p2p", "[]"));
        verify(conversations).submitTurn(eq(1L), eq(7L), eq("FEISHU"), anyString(), anyString(), eq("FEISHU:" + b.getId() + ":om_private"), anyString());
    }
    @Test void retriesFailuresAndRecoversExpiredProcessingLease() throws Exception {
        var f = new FeishuTestSupport(); var b = f.create(); var conversations = mock(AgentConversationService.class);
        doThrow(new IllegalStateException("sensitive details")).when(conversations).submitTurn(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
        var inbox = new FeishuInbox(f.jdbc, f.json, f.dao, mock(FeishuApiClient.class), conversations);
        inbox.accept(b, f.event("om_private", "p2p", "[]")); inbox.drain();
        assertEquals("PENDING", f.jdbc.queryForObject("SELECT status FROM feishu_message_inbox", String.class));
        assertFalse(f.dao.find(1L, b.getId()).getLastError().contains("sensitive"));
        reset(conversations);
        f.jdbc.update("UPDATE feishu_message_inbox SET status='PROCESSING',available_at='2000-01-01'"); inbox.drain();
        assertEquals("DONE", f.jdbc.queryForObject("SELECT status FROM feishu_message_inbox", String.class));
    }
    @Test void exhaustedRetriesStopAndBotOrNonTextMessagesAreIgnored() throws Exception {
        var f = new FeishuTestSupport(); var b = f.create(); var conversations = mock(AgentConversationService.class);
        doThrow(new IllegalStateException("unavailable")).when(conversations).submitTurn(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
        var inbox = new FeishuInbox(f.jdbc, f.json, f.dao, mock(FeishuApiClient.class), conversations);
        var botEvent = (com.fasterxml.jackson.databind.node.ObjectNode) f.event("om_bot", "p2p", "[]");
        ((com.fasterxml.jackson.databind.node.ObjectNode) botEvent.path("sender")).put("sender_type", "app");
        inbox.accept(b, botEvent);
        var imageEvent = (com.fasterxml.jackson.databind.node.ObjectNode) f.event("om_image", "p2p", "[]");
        ((com.fasterxml.jackson.databind.node.ObjectNode) imageEvent.path("message")).put("message_type", "image");
        inbox.accept(b, imageEvent);
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM feishu_message_inbox", Integer.class));
        inbox.accept(b, f.event("om_failure", "p2p", "[]"));
        for (int attempt = 0; attempt < 6; attempt++) {
            f.jdbc.update("UPDATE feishu_message_inbox SET available_at='2000-01-01'");
            inbox.drain();
        }
        assertEquals("FAILED", f.jdbc.queryForObject("SELECT status FROM feishu_message_inbox", String.class));
        verify(conversations, times(5)).submitTurn(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void disabledOrReboundAppsDoNotDispatchQueuedMessages() throws Exception {
        var f = new FeishuTestSupport(); var b = f.create(); var conversations = mock(AgentConversationService.class);
        var inbox = new FeishuInbox(f.jdbc, f.json, f.dao, mock(FeishuApiClient.class), conversations);
        inbox.accept(b, f.event("om_private", "p2p", "[]"));
        f.jdbc.update("UPDATE feishu_robot_binding SET agent_id=8"); inbox.drain();
        verifyNoInteractions(conversations);
    }
}
