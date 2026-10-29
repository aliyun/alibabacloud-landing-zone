package com.aliyun.autowonder.integration.feishu;

import com.aliyun.autowonder.conversation.AgentConversationService;
import com.aliyun.autowonder.im.ImChannelAdmissionService;
import com.aliyun.autowonder.im.ImRouteSnapshot;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuSharedInboxTest {

    private static final String BOT_MENTION =
            "[{\"key\":\"@_user_1\",\"id\":{\"open_id\":\"ou_bot\"},\"name\":\"Bot\"}]";

    @Test
    void acceptSharedResolvesThePlatformGroupAndDrainsTheSnapshot() throws Exception {
        var f = new FeishuTestSupport();
        long bindingId = sharedBinding(f, "oc_chat", 2);
        var config = config(4);
        var api = mock(FeishuApiClient.class);
        var conversations = mock(AgentConversationService.class);
        var inbox = new FeishuInbox(f.jdbc, f.json, f.dao, api, conversations, null, null);

        inbox.acceptShared(config, f.event("om_shared", "group", BOT_MENTION));

        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM feishu_message_inbox", Integer.class));
        assertNull(f.jdbc.queryForObject("SELECT receipt_context FROM feishu_message_inbox", String.class));
        when(api.botOpenId(org.mockito.ArgumentMatchers.any(FeishuBinding.class))).thenReturn("ou_bot");
        inbox.drain();

        verify(conversations).submitTurn(eq(1L), eq(7L), eq("FEISHU"),
                eq(bindingId + ":oc_chat"), eq("hello"),
                eq("FEISHU:" + bindingId + ":om_shared"), anyString());
        assertEquals("DONE", f.jdbc.queryForObject("SELECT status FROM feishu_message_inbox", String.class));
    }

    @Test
    void acceptSharedRequiresGroupEventAndResolvableBinding() throws Exception {
        var f = new FeishuTestSupport();
        var api = mock(FeishuApiClient.class);
        var conversations = mock(AgentConversationService.class);
        var inbox = new FeishuInbox(f.jdbc, f.json, f.dao, api, conversations, null, null);

        inbox.acceptShared(config(4), f.event("om_private", "p2p", "[]"));
        inbox.acceptShared(config(4), f.event("om_unknown_group", "group", BOT_MENTION));
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM feishu_message_inbox", Integer.class));

        sharedBinding(f, "oc_chat", 2);
        var disabled = config(4);
        disabled.setEnabled(0);
        var admission = mock(ImChannelAdmissionService.class);
        when(admission.admit(eq("FEISHU"), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(
                new ImChannelAdmissionService.Result(
                        ImChannelAdmissionService.Decision.IGNORED, "disabled", null));
        var gated = new FeishuInbox(f.jdbc, f.json, f.dao, api, conversations, admission, null);
        gated.acceptShared(disabled, f.event("om_disabled", "group", BOT_MENTION));
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM feishu_message_inbox", Integer.class));
        verify(conversations, never()).submitTurn(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    void admissionPersistsAReceiptContextForSharedEvents() throws Exception {
        var f = new FeishuTestSupport();
        long bindingId = sharedBinding(f, "oc_chat", 2);
        var config = config(4);
        var admission = mock(ImChannelAdmissionService.class);
        var api = mock(FeishuApiClient.class);
        var conversations = mock(AgentConversationService.class);
        var imConfigs = mock(PlatformImChannelConfigService.class);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config);
        when(admission.admit(eq("FEISHU"), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            ImChannelAdmissionService.ReceiptWriter writer = invocation.getArgument(2);
            var context = new ImChannelAdmissionService.Context("FEISHU", "FEISHU", 7L,
                    Instant.now().minusSeconds(60), config);
            ImRouteSnapshot snapshot = writer.persist(context);
            return snapshot == null
                    ? new ImChannelAdmissionService.Result(
                            ImChannelAdmissionService.Decision.IGNORED, "not_admitted", null)
                    : new ImChannelAdmissionService.Result(
                            ImChannelAdmissionService.Decision.ACCEPTED, "accepted", snapshot);
        });
        var inbox = new FeishuInbox(f.jdbc, f.json, f.dao, api, conversations, admission, imConfigs);

        inbox.acceptShared(config, f.event("om_shared", "group", BOT_MENTION));

        String receipt = f.jdbc.queryForObject(
                "SELECT receipt_context FROM feishu_message_inbox", String.class);
        assertNotNull(receipt);
        ImRouteSnapshot snapshot = ImRouteSnapshot.parse(f.json, receipt);
        assertEquals("SHARED", snapshot.bindingMode());
        assertEquals(bindingId, snapshot.bindingId());
        assertEquals(50L, snapshot.platformConfigId());
        assertEquals(7L, snapshot.selectionGeneration());
        assertEquals("oc_chat", snapshot.groupId());
        when(api.botOpenId(org.mockito.ArgumentMatchers.any(FeishuBinding.class))).thenReturn("ou_bot");
        inbox.drain();
        verify(conversations).submitTurn(eq(1L), eq(7L), eq("FEISHU"),
                eq(bindingId + ":oc_chat"), eq("hello"),
                eq("FEISHU:" + bindingId + ":om_shared"), anyString());
    }

    @Test
    void drainRejectsReceiptsWhoseRouteHasChanged() throws Exception {
        var f = new FeishuTestSupport();
        long bindingId = sharedBinding(f, "oc_chat", 2);
        var config = config(4);
        var api = mock(FeishuApiClient.class);
        var conversations = mock(AgentConversationService.class);
        var imConfigs = mock(PlatformImChannelConfigService.class);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config);
        when(api.botOpenId(org.mockito.ArgumentMatchers.any(FeishuBinding.class))).thenReturn("ou_bot");
        var inbox = new FeishuInbox(f.jdbc, f.json, f.dao, api, conversations, null, imConfigs);

        enqueue(f, bindingId, "om_ok", snapshot(bindingId, 2, 4));
        enqueue(f, bindingId, "om_config_changed", snapshot(bindingId, 2, 3));
        enqueue(f, bindingId, "om_binding_changed", snapshot(bindingId, 1, 4));
        enqueue(f, bindingId, "om_group_changed",
                new ImRouteSnapshot("FEISHU", bindingId, "SHARED", 50L, 2L, 4L, 1L, 7L,
                        "oc_other", 0L, System.currentTimeMillis()));

        for (int round = 0; round < 2; round++) {
            f.jdbc.update("UPDATE feishu_message_inbox SET available_at='2000-01-01'");
            inbox.drain();
        }

        verify(conversations, times(1)).submitTurn(eq(1L), eq(7L), eq("FEISHU"),
                eq(bindingId + ":oc_chat"), eq("hello"),
                eq("FEISHU:" + bindingId + ":om_ok"), anyString());
        assertEquals("DONE", f.jdbc.queryForObject(
                "SELECT status FROM feishu_message_inbox WHERE message_id='om_config_changed'", String.class));
        assertEquals("路由已变更，消息未处理", f.jdbc.queryForObject(
                "SELECT last_error FROM feishu_message_inbox WHERE message_id='om_config_changed'", String.class));
        assertEquals("DONE", f.jdbc.queryForObject(
                "SELECT status FROM feishu_message_inbox WHERE message_id='om_binding_changed'", String.class));
        assertEquals("DONE", f.jdbc.queryForObject(
                "SELECT status FROM feishu_message_inbox WHERE message_id='om_group_changed'", String.class));

        when(imConfigs.findGroupChatById(50L)).thenReturn(null);
        enqueue(f, bindingId, "om_config_missing", snapshot(bindingId, 2, 4));
        f.jdbc.update("UPDATE feishu_message_inbox SET available_at='2000-01-01'");
        inbox.drain();
        assertEquals("DONE", f.jdbc.queryForObject(
                "SELECT status FROM feishu_message_inbox WHERE message_id='om_config_missing'", String.class));
        verify(conversations, times(1)).submitTurn(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    void acceptSharedPersistsSharedReceiptCoordinatesEvenWithoutAdmission() throws Exception {
        var f = new FeishuTestSupport();
        long bindingId = sharedBinding(f, "oc_chat", 2);
        var config = config(4);
        var inbox = new FeishuInbox(f.jdbc, f.json, f.dao, mock(FeishuApiClient.class),
                mock(AgentConversationService.class), null, null);

        inbox.acceptShared(config, f.event("om_coordinates", "group", BOT_MENTION));

        assertEquals(bindingId, f.jdbc.queryForObject(
                "SELECT binding_id FROM feishu_message_inbox WHERE message_id='om_coordinates'",
                Long.class));
        assertEquals(50L, f.jdbc.queryForObject(
                "SELECT platform_config_id FROM feishu_message_inbox WHERE message_id='om_coordinates'",
                Long.class));
        assertEquals("oc_chat", f.jdbc.queryForObject(
                "SELECT group_id FROM feishu_message_inbox WHERE message_id='om_coordinates'",
                String.class));
    }

    @Test
    void sharedRedeliveryAfterRebindIsDeduplicatedByConfigAndGroup() throws Exception {
        var f = new FeishuTestSupport();
        long firstBinding = sharedBinding(f, "oc_chat", 2);
        var config = config(4);
        var conversations = mock(AgentConversationService.class);
        var inbox = new FeishuInbox(f.jdbc, f.json, f.dao, mock(FeishuApiClient.class),
                conversations, admission(config, true), null);

        inbox.acceptShared(config, f.event("om_rebind", "group", BOT_MENTION));
        f.jdbc.update("DELETE FROM feishu_robot_binding WHERE id=?", firstBinding);
        long secondBinding = sharedBinding(f, "oc_chat", 2);
        org.junit.jupiter.api.Assertions.assertNotEquals(firstBinding, secondBinding);

        inbox.acceptShared(config, f.event("om_rebind", "group", BOT_MENTION));

        assertEquals(1, f.jdbc.queryForObject(
                "SELECT COUNT(*) FROM feishu_message_inbox WHERE message_id='om_rebind'",
                Integer.class));
        assertEquals(firstBinding, f.jdbc.queryForObject(
                "SELECT binding_id FROM feishu_message_inbox WHERE message_id='om_rebind'",
                Long.class));
        verify(conversations, never()).submitTurn(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString());
    }

    @Test
    void sharedPersistRaceIsSwallowedInsteadOfThrowing() throws Exception {
        var f = new FeishuTestSupport();
        sharedBinding(f, "oc_chat", 2);
        var config = config(4);
        var inbox = new FeishuInbox(f.jdbc, f.json, f.dao, mock(FeishuApiClient.class),
                mock(AgentConversationService.class), admission(config, false), null);

        inbox.acceptShared(config, f.event("om_race", "group", BOT_MENTION));
        inbox.acceptShared(config, f.event("om_race", "group", BOT_MENTION));

        assertEquals(1, f.jdbc.queryForObject(
                "SELECT COUNT(*) FROM feishu_message_inbox WHERE message_id='om_race'",
                Integer.class));
    }

    private static ImChannelAdmissionService admission(PlatformImChannelConfigDO config,
            boolean checkDuplicates) {
        var admission = mock(ImChannelAdmissionService.class);
        when(admission.admit(eq("FEISHU"), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(invocation -> {
            ImChannelAdmissionService.ReceiptWriter writer = invocation.getArgument(2);
            var context = new ImChannelAdmissionService.Context("FEISHU", "FEISHU", 7L,
                    Instant.now().minusSeconds(60), config);
            if (checkDuplicates && writer.alreadyReceived(context)) {
                return new ImChannelAdmissionService.Result(
                        ImChannelAdmissionService.Decision.DUPLICATE, "duplicate", null);
            }
            ImRouteSnapshot snapshot = writer.persist(context);
            return snapshot == null
                    ? new ImChannelAdmissionService.Result(
                            ImChannelAdmissionService.Decision.IGNORED, "not_admitted", null)
                    : new ImChannelAdmissionService.Result(
                            ImChannelAdmissionService.Decision.ACCEPTED, "accepted", snapshot);
        });
        return admission;
    }

    private static long sharedBinding(FeishuTestSupport f, String groupId, long routeVersion) {
        f.jdbc.update("INSERT INTO feishu_robot_binding (tenant_id,binding_mode,platform_config_id,"
                        + "group_id,app_id,credential_ref,agent_id,route_version,status,"
                        + "creator_id,modifier_id) VALUES (1,'SHARED',50,?,'cli_shared',NULL,7,?,"
                        + "'ENABLED',9,9)",
                groupId, routeVersion);
        return f.jdbc.queryForObject("SELECT id FROM feishu_robot_binding WHERE group_id=?",
                Long.class, groupId);
    }

    private static void enqueue(FeishuTestSupport f, long bindingId, String messageId,
            ImRouteSnapshot snapshot) throws Exception {
        f.jdbc.update("INSERT INTO feishu_message_inbox (binding_id,tenant_id,agent_id,message_id,"
                        + "payload,receipt_context) VALUES (?,?,?,?,?,?)",
                bindingId, 1L, 7L, messageId,
                f.event(messageId, "group", BOT_MENTION).toString(), snapshot.toJson(f.json));
    }

    private static ImRouteSnapshot snapshot(long bindingId, long bindingRoute, long configRoute) {
        return new ImRouteSnapshot("FEISHU", bindingId, "SHARED", 50L, bindingRoute, configRoute,
                1L, 7L, "oc_chat", 0L, System.currentTimeMillis());
    }

    private static PlatformImChannelConfigDO config(long routeVersion) {
        var config = new PlatformImChannelConfigDO();
        config.setId(50L);
        config.setProvider("FEISHU");
        config.setPurpose("GROUP_CHAT");
        config.setAppKey("cli_shared");
        config.setEnabled(1);
        config.setRouteVersion(routeVersion);
        return config;
    }
}
