package com.aliyun.autowonder.integration.feishu;

import com.aliyun.autowonder.conversation.AgentConversationService;
import com.aliyun.autowonder.im.ImChannelAdmissionService;
import com.aliyun.autowonder.im.ImRouteSnapshot;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Edge branches of the durable Feishu inbox that the happy-path suites do not reach. */
class FeishuInboxBranchTest {

    private static final String BOT_MENTION =
            "[{\"key\":\"@_user_1\",\"id\":{\"open_id\":\"ou_bot\"},\"name\":\"Bot\"}]";

    @Test
    void rejectsEventsOutsideTheInboxContract() throws Exception {
        var f = new FeishuTestSupport();
        var binding = f.create();
        var inbox = new FeishuInbox(f.jdbc, f.json, f.dao, mock(FeishuApiClient.class),
                mock(AgentConversationService.class));

        inbox.accept(binding, f.event("om_chat", "channel", "[]"));
        inbox.accept(binding, f.event("bad_message", "p2p", "[]"));
        ObjectNode badChat = (ObjectNode) f.event("om_chat_id", "p2p", "[]");
        ((ObjectNode) badChat.path("message")).put("chat_id", "bad");
        inbox.accept(binding, badChat);
        ObjectNode longThread = (ObjectNode) f.event("om_thread", "p2p", "[]");
        ((ObjectNode) longThread.path("message")).put("thread_id", "t".repeat(101));
        inbox.accept(binding, longThread);

        assertEquals(0, f.jdbc.queryForObject(
                "SELECT COUNT(*) FROM feishu_message_inbox", Integer.class));
    }

    @Test
    void eventTimeHandlesEveryPayloadShape() {
        var f = new FeishuTestSupport();
        assertNull(FeishuInbox.eventTime(f.json.createObjectNode()));

        ObjectNode blank = f.json.createObjectNode();
        blank.putObject("message").put("create_time", "   ");
        assertNull(FeishuInbox.eventTime(blank));

        ObjectNode malformed = f.json.createObjectNode();
        malformed.putObject("message").put("create_time", "not-a-number");
        assertNull(FeishuInbox.eventTime(malformed));

        ObjectNode zero = f.json.createObjectNode();
        zero.putObject("message").put("create_time", 0);
        assertNull(FeishuInbox.eventTime(zero));

        ObjectNode booleanNode = f.json.createObjectNode();
        booleanNode.putObject("message").put("create_time", true);
        assertNull(FeishuInbox.eventTime(booleanNode));

        ObjectNode valid = f.json.createObjectNode();
        valid.putObject("message").put("create_time", 1234L);
        assertEquals(Instant.ofEpochMilli(1234L), FeishuInbox.eventTime(valid));
    }

    @Test
    void legacyAdmissionRejectsBindingsThatChangedBeforePersist() throws Exception {
        var f = new FeishuTestSupport();
        var binding = f.create();
        var contextConfig = new AtomicReference<PlatformImChannelConfigDO>();
        var inbox = new FeishuInbox(f.jdbc, f.json, f.dao, mock(FeishuApiClient.class),
                mock(AgentConversationService.class), admission(contextConfig, true), null);

        var transientBinding = new FeishuBinding();
        transientBinding.setId(999L);
        transientBinding.setTenantId(1L);
        transientBinding.setAgentId(7L);
        transientBinding.setStatus("ENABLED");
        transientBinding.setAppId("cli_transient");
        inbox.accept(transientBinding, f.event("om_missing_binding", "p2p", "[]"));

        inbox.accept(null, f.event("om_no_candidate", "p2p", "[]"));

        f.jdbc.update("INSERT INTO feishu_message_inbox "
                + "(binding_id,tenant_id,agent_id,message_id,payload) VALUES (?,?,?,?,?)",
                binding.getId(), 1L, 7L, "om_duplicate", "{}");
        inbox.accept(binding, f.event("om_duplicate", "p2p", "[]"));

        f.jdbc.update("UPDATE feishu_robot_binding SET status='DISABLED' WHERE id=?",
                binding.getId());
        inbox.accept(binding, f.event("om_disabled_binding", "p2p", "[]"));

        assertEquals(1, f.jdbc.queryForObject(
                "SELECT COUNT(*) FROM feishu_message_inbox", Integer.class));
    }

    @Test
    void legacyAdmissionRejectsBindingsThatBecameShared() throws Exception {
        var f = new FeishuTestSupport();
        long bindingId = sharedBinding(f, "oc_legacy_mode", 2);
        FeishuBinding shared = f.dao.find(1L, bindingId);
        var contextConfig = new AtomicReference<PlatformImChannelConfigDO>();
        var inbox = new FeishuInbox(f.jdbc, f.json, f.dao, mock(FeishuApiClient.class),
                mock(AgentConversationService.class), admission(contextConfig, false), null);

        inbox.accept(shared, f.event("om_shared_mode", "group", BOT_MENTION));

        assertEquals(0, f.jdbc.queryForObject(
                "SELECT COUNT(*) FROM feishu_message_inbox", Integer.class));
    }

    @Test
    void sharedAdmissionRejectsMismatchedContexts() throws Exception {
        var f = new FeishuTestSupport();
        var config = config(4L);
        var contextConfig = new AtomicReference<PlatformImChannelConfigDO>();
        var inbox = new FeishuInbox(f.jdbc, f.json, f.dao, mock(FeishuApiClient.class),
                mock(AgentConversationService.class), admission(contextConfig, false), null);

        inbox.acceptShared(config, sharedEvent(f, "oc_case_null", "om_case_null"));

        contextConfig.set(configWithId(51L));
        inbox.acceptShared(config, sharedEvent(f, "oc_case_id", "om_case_id"));

        var wrongProvider = configWithId(50L);
        wrongProvider.setProvider("DINGTALK");
        contextConfig.set(wrongProvider);
        inbox.acceptShared(config, sharedEvent(f, "oc_case_provider", "om_case_provider"));

        var disabled = configWithId(50L);
        disabled.setEnabled(0);
        contextConfig.set(disabled);
        inbox.acceptShared(config, sharedEvent(f, "oc_case_disabled", "om_case_disabled"));

        contextConfig.set(configWithId(50L));
        inbox.acceptShared(config, sharedEvent(f, "oc_case_missing", "om_case_missing"));

        sharedBinding(f, "oc_case_status", 1);
        f.jdbc.update("UPDATE feishu_robot_binding SET status='DISABLED' "
                + "WHERE group_id='oc_case_status'");
        inbox.acceptShared(config, sharedEvent(f, "oc_case_status", "om_case_status"));

        assertEquals(0, f.jdbc.queryForObject(
                "SELECT COUNT(*) FROM feishu_message_inbox", Integer.class));
    }

    @Test
    void drainDispatchesUpgradeEraRowsWithoutSnapshots() throws Exception {
        var f = new FeishuTestSupport();
        var binding = f.create();
        var conversations = mock(AgentConversationService.class);
        var inbox = new FeishuInbox(f.jdbc, f.json, f.dao, mock(FeishuApiClient.class),
                conversations);

        enqueueLegacy(f, binding.getId(), "om_legacy", null);
        enqueueLegacy(f, binding.getId(), "om_invalid_receipt", "not-json");
        inbox.drain();

        verify(conversations, times(2)).submitTurn(eq(1L), eq(7L), eq("FEISHU"),
                eq(binding.getId() + ":oc_chat"), eq("@_user_1 hello"), anyString(), anyString());
        assertEquals("DONE", f.jdbc.queryForObject(
                "SELECT status FROM feishu_message_inbox WHERE message_id='om_invalid_receipt'",
                String.class));
    }

    @Test
    void drainSharedRowsRequireResolvableConfigAndPayload() throws Exception {
        var f = new FeishuTestSupport();
        long bindingId = sharedBinding(f, "oc_chat", 2);
        var api = mock(FeishuApiClient.class);
        when(api.botOpenId(any(FeishuBinding.class))).thenReturn("ou_bot");
        var conversations = mock(AgentConversationService.class);
        var imConfigs = mock(PlatformImChannelConfigService.class);
        var config = config(null);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config);
        var inbox = new FeishuInbox(f.jdbc, f.json, f.dao, api, conversations, null, imConfigs);

        enqueueShared(f, bindingId, "om_bad_payload", snapshot(bindingId, 2, 0), "{not-json");
        enqueueShared(f, bindingId, "om_shared_ok", snapshot(bindingId, 2, 0),
                f.event("om_shared_ok", "group", BOT_MENTION).toString());
        inbox.drain();

        verify(conversations).submitTurn(eq(1L), eq(7L), eq("FEISHU"),
                eq(bindingId + ":oc_chat"), eq("hello"),
                eq("FEISHU:" + bindingId + ":om_shared_ok"), anyString());
        assertEquals("路由已变更，消息未处理", f.jdbc.queryForObject(
                "SELECT last_error FROM feishu_message_inbox WHERE message_id='om_bad_payload'",
                String.class));

        var configless = new FeishuInbox(f.jdbc, f.json, f.dao, api, conversations, null, null);
        enqueueShared(f, bindingId, "om_no_config", snapshot(bindingId, 2, 0),
                f.event("om_no_config", "group", BOT_MENTION).toString());
        configless.drain();

        assertEquals("DONE", f.jdbc.queryForObject(
                "SELECT status FROM feishu_message_inbox WHERE message_id='om_no_config'",
                String.class));
        verify(conversations, times(1)).submitTurn(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void dispatchHandlesMentionReplacementAndBlankText() throws Exception {
        var f = new FeishuTestSupport();
        var binding = f.create();
        var api = mock(FeishuApiClient.class);
        when(api.botOpenId(binding)).thenReturn("ou_bot");
        var conversations = mock(AgentConversationService.class);
        var inbox = new FeishuInbox(f.jdbc, f.json, f.dao, api, conversations);

        ObjectNode blankKey = (ObjectNode) f.event("om_blank_key", "group",
                "[{\"key\":\"\",\"id\":{\"open_id\":\"ou_bot\"},\"name\":\"Bot\"}]");
        ((ObjectNode) blankKey.path("message")).put("content",
                f.json.writeValueAsString(Map.of("text", "hello")));
        inbox.dispatch(binding, blankKey, null);

        ObjectNode nonBot = (ObjectNode) f.event("om_non_bot", "group",
                "[{\"key\":\"@_user_1\",\"id\":{\"open_id\":\"ou_bot\"},\"name\":\"Bot\"},"
                        + "{\"key\":\"@_user_2\",\"id\":{\"open_id\":\"ou_user\"},\"name\":\"Alice\"}]");
        ((ObjectNode) nonBot.path("message")).put("content",
                f.json.writeValueAsString(Map.of("text", "@_user_1 @_user_2 hi")));
        inbox.dispatch(binding, nonBot, null);

        ObjectNode blankText = (ObjectNode) f.event("om_blank_text", "group", BOT_MENTION);
        ((ObjectNode) blankText.path("message")).put("content",
                f.json.writeValueAsString(Map.of("text", "@_user_1")));
        inbox.dispatch(binding, blankText, null);

        ObjectNode privateMention = (ObjectNode) f.event("om_p2p_mention", "p2p",
                "[{\"key\":\"@_user_2\",\"id\":{\"open_id\":\"ou_user\"},\"name\":\"Alice\"}]");
        ((ObjectNode) privateMention.path("message")).put("content",
                f.json.writeValueAsString(Map.of("text", "@_user_2 yo")));
        inbox.dispatch(binding, privateMention, null);

        verify(conversations).submitTurn(eq(1L), eq(7L), eq("FEISHU"),
                eq(binding.getId() + ":oc_chat"), eq("hello"),
                eq("FEISHU:" + binding.getId() + ":om_blank_key"), anyString());
        verify(conversations).submitTurn(eq(1L), eq(7L), eq("FEISHU"),
                eq(binding.getId() + ":oc_chat"), eq("@Alice hi"),
                eq("FEISHU:" + binding.getId() + ":om_non_bot"), anyString());
        verify(conversations).submitTurn(eq(1L), eq(7L), eq("FEISHU"),
                eq(binding.getId() + ":oc_chat"), eq("@Alice yo"),
                eq("FEISHU:" + binding.getId() + ":om_p2p_mention"), anyString());
        verify(conversations, times(3)).submitTurn(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    private static ImChannelAdmissionService admission(
            AtomicReference<PlatformImChannelConfigDO> contextConfig, boolean checkDuplicates) {
        var admission = mock(ImChannelAdmissionService.class);
        when(admission.admit(eq("FEISHU"), any(), any())).thenAnswer(invocation -> {
            ImChannelAdmissionService.ReceiptWriter writer = invocation.getArgument(2);
            var context = new ImChannelAdmissionService.Context("FEISHU", "FEISHU", 1L,
                    Instant.now().minusSeconds(60), contextConfig.get());
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

    private static JsonNode sharedEvent(FeishuTestSupport f, String groupId, String messageId)
            throws Exception {
        ObjectNode event = (ObjectNode) f.event(messageId, "group", BOT_MENTION);
        ((ObjectNode) event.path("message")).put("chat_id", groupId);
        return event;
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

    private static void enqueueLegacy(FeishuTestSupport f, long bindingId, String messageId,
            String receiptContext) throws Exception {
        f.jdbc.update("INSERT INTO feishu_message_inbox (binding_id,tenant_id,agent_id,message_id,"
                        + "payload,receipt_context) VALUES (?,?,?,?,?,?)",
                bindingId, 1L, 7L, messageId, f.event(messageId, "p2p", "[]").toString(),
                receiptContext);
    }

    private static void enqueueShared(FeishuTestSupport f, long bindingId, String messageId,
            ImRouteSnapshot snapshot, String payload) {
        f.jdbc.update("INSERT INTO feishu_message_inbox (binding_id,tenant_id,agent_id,message_id,"
                        + "payload,receipt_context) VALUES (?,?,?,?,?,?)",
                bindingId, 1L, 7L, messageId, payload, snapshot.toJson(f.json));
    }

    private static ImRouteSnapshot snapshot(long bindingId, long bindingRoute, long configRoute) {
        return new ImRouteSnapshot("FEISHU", bindingId, "SHARED", 50L, bindingRoute, configRoute,
                1L, 7L, "oc_chat", 0L, System.currentTimeMillis());
    }

    private static PlatformImChannelConfigDO config(Long routeVersion) {
        var config = configWithId(50L);
        config.setRouteVersion(routeVersion);
        return config;
    }

    private static PlatformImChannelConfigDO configWithId(long id) {
        var config = new PlatformImChannelConfigDO();
        config.setId(id);
        config.setProvider("FEISHU");
        config.setPurpose("GROUP_CHAT");
        config.setAppKey("cli_shared");
        config.setEnabled(1);
        config.setRouteVersion(4L);
        return config;
    }
}
