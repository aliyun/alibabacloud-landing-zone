package com.aliyun.autowonder.integration.feishu;

import com.aliyun.autowonder.im.ImChannelAdmissionService;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Callback branches beyond the authenticated happy path: rejects, no-ops and command fallbacks. */
class FeishuCallbackControllerBranchTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void legacyCallbackRejectsUnknownBindingsAndBlankChallenges() throws Exception {
        var f = new FeishuTestSupport();
        var binding = f.create();
        var inbox = mock(FeishuInbox.class);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(
                new FeishuCallbackController(f.dao, f.service, inbox, f.json)).build();
        String url = "/api/integrations/feishu/callback?bindingId=" + binding.getId();

        mvc.perform(post("/api/integrations/feishu/callback?bindingId=999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"url_verification\",\"token\":\"verify-token\",\"challenge\":\"hi\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("not-json"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"url_verification\",\"token\":\"verify-token\",\"challenge\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"header\":{\"token\":\"verify-token\",\"app_id\":\"cli_test\","
                                + "\"event_type\":\"im.chat.member.bot.added_v1\"},\"event\":{}}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0));
        verifyNoInteractions(inbox);
    }

    @Test
    void groupCallbackRejectsUnresolvableConfigsAndBlankChallenges() throws Exception {
        var imConfigs = mock(PlatformImChannelConfigService.class);
        var bindings = mock(FeishuBindingService.class);
        var inbox = mock(FeishuInbox.class);
        var config = config("FEISHU", 1);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config);
        when(bindings.secrets(config)).thenReturn(new FeishuBindingService.Secrets("sk", "vt", null));
        var other = config("DINGTALK", 1);
        other.setId(52L);
        when(imConfigs.findGroupChatById(52L)).thenReturn(other);
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FeishuCallbackController(
                mock(FeishuBindingDao.class), bindings, inbox, json, imConfigs, null, null)).build();
        String url = "/api/integrations/feishu/group-callback?configId=50";

        mvc.perform(post("/api/integrations/feishu/group-callback?configId=51")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"url_verification\",\"token\":\"vt\",\"challenge\":\"hi\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/integrations/feishu/group-callback?configId=52")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"url_verification\",\"token\":\"vt\",\"challenge\":\"hi\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("not-json"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"url_verification\",\"token\":\"vt\",\"challenge\":\"  \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"url_verification\",\"token\":\"vt\",\"challenge\":\"hi\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.challenge").value("hi"));
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content(groupEvent("cli_other", "group", "hello", null, "user",
                                "@_user_1", "ou_bot", "im.message.receive_v1")))
                .andExpect(status().isForbidden());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content(groupEvent("cli_shared", "group", "hello", null, "user",
                                "@_user_1", "ou_bot", "im.chat.member.bot.added_v1")))
                .andExpect(status().isOk());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content(groupEvent("cli_shared", "p2p", "hello", null, "user",
                                "@_user_1", "ou_bot", "im.message.receive_v1")))
                .andExpect(status().isOk());
        verifyNoInteractions(inbox);

        config.setEnabled(0);
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content(groupEvent("cli_shared", "group", "hello", null, "user",
                                "@_user_1", "ou_bot", "im.message.receive_v1")))
                .andExpect(status().isOk());
        verifyNoInteractions(inbox);
    }

    @Test
    void groupInfoCommandsReportEveryBindingStateWithoutConfirmation() throws Exception {
        var imConfigs = mock(PlatformImChannelConfigService.class);
        var bindings = mock(FeishuBindingService.class);
        var inbox = mock(FeishuInbox.class);
        var api = mock(FeishuApiClient.class);
        var admission = mock(ImChannelAdmissionService.class);
        var config = config("FEISHU", 1);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config);
        when(bindings.secrets(config)).thenReturn(new FeishuBindingService.Secrets("sk", "vt", null));
        when(admission.allowsManagement("FEISHU")).thenReturn(true);
        when(api.botOpenIdForConfig(config)).thenReturn("ou_bot");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FeishuCallbackController(
                mock(FeishuBindingDao.class), bindings, inbox, json, imConfigs, api, admission)).build();
        String url = "/api/integrations/feishu/group-callback?configId=50";
        String body = groupEvent("cli_shared", "group", "@_user_1 群信息", null, "user",
                "@_user_1", "ou_bot", "im.message.receive_v1");

        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(api).replyForConfig(config, "om_group",
                "群信息\n群 ID：oc_group\n绑定状态：未绑定\n启用状态：已停用", "om_group");

        var enabled = binding("ENABLED");
        when(bindings.findShared(50L, "oc_group")).thenReturn(enabled);
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(api).replyForConfig(config, "om_group",
                "群信息\n群 ID：oc_group\n绑定状态：已绑定\n启用状态：已启用", "om_group");

        var disabled = binding("DISABLED");
        when(bindings.findShared(50L, "oc_group")).thenReturn(disabled);
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(api).replyForConfig(config, "om_group",
                "群信息\n群 ID：oc_group\n绑定状态：已绑定\n启用状态：已停用", "om_group");
        verifyNoInteractions(inbox);
    }

    @Test
    void legacyBindingCommandsNeverBindAndReportRemovalWithLookupFailures() throws Exception {
        var imConfigs = mock(PlatformImChannelConfigService.class);
        var bindings = mock(FeishuBindingService.class);
        var inbox = mock(FeishuInbox.class);
        var api = mock(FeishuApiClient.class);
        var admission = mock(ImChannelAdmissionService.class);
        var config = config("FEISHU", 1);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config);
        when(bindings.secrets(config)).thenReturn(new FeishuBindingService.Secrets("sk", "vt", null));
        when(admission.allowsManagement("FEISHU")).thenReturn(true);
        when(api.botOpenIdForConfig(config)).thenReturn("ou_bot");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FeishuCallbackController(
                mock(FeishuBindingDao.class), bindings, inbox, json, imConfigs, api, admission)).build();
        String url = "/api/integrations/feishu/group-callback?configId=50";
        String body = groupEvent("cli_shared", "group", "@_user_1 绑定群聊:whatever-code", null,
                "user", "@_user_1", "ou_bot", "im.message.receive_v1");
        String expected = "群确认已取消，请在空间消息渠道集成中管理群绑定。";

        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(api).replyForConfig(config, "om_group", expected, "om_group");

        when(bindings.findShared(50L, "oc_group")).thenThrow(new IllegalStateException("unavailable"));
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(api, times(2)).replyForConfig(eq(config), eq("om_group"), anyString(), anyString());

        doThrow(new IllegalStateException("reply failed")).when(api)
                .replyForConfig(any(), anyString(), anyString(), anyString());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        verify(api, times(3)).replyForConfig(eq(config), eq("om_group"), anyString(), anyString());
        verifyNoInteractions(inbox);
    }

    @Test
    void groupCommandsAreIgnoredWithoutAdmissionApiOrMention() throws Exception {
        var imConfigs = mock(PlatformImChannelConfigService.class);
        var bindings = mock(FeishuBindingService.class);
        var inbox = mock(FeishuInbox.class);
        var config = config("FEISHU", 1);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config);
        when(bindings.secrets(config)).thenReturn(new FeishuBindingService.Secrets("sk", "vt", null));
        String url = "/api/integrations/feishu/group-callback?configId=50";
        String body = groupEvent("cli_shared", "group", "@_user_1 群信息", null, "user",
                "@_user_1", "ou_bot", "im.message.receive_v1");

        var noAdmissionApi = mock(FeishuApiClient.class);
        MockMvc withNullAdmission = MockMvcBuilders.standaloneSetup(new FeishuCallbackController(
                mock(FeishuBindingDao.class), bindings, inbox, json, imConfigs, noAdmissionApi, null))
                .build();
        withNullAdmission.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        var admission = mock(ImChannelAdmissionService.class);
        when(admission.allowsManagement("FEISHU")).thenReturn(false);
        var notAllowedApi = mock(FeishuApiClient.class);
        MockMvc withDeniedAdmission = MockMvcBuilders.standaloneSetup(new FeishuCallbackController(
                mock(FeishuBindingDao.class), bindings, inbox, json, imConfigs, notAllowedApi, admission))
                .build();
        withDeniedAdmission.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        when(admission.allowsManagement("FEISHU")).thenReturn(true);
        MockMvc withoutApi = MockMvcBuilders.standaloneSetup(new FeishuCallbackController(
                mock(FeishuBindingDao.class), bindings, inbox, json, imConfigs, null, admission)).build();
        withoutApi.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        var api = mock(FeishuApiClient.class);
        when(api.botOpenIdForConfig(config)).thenReturn("  ");
        MockMvc withoutBotId = MockMvcBuilders.standaloneSetup(new FeishuCallbackController(
                mock(FeishuBindingDao.class), bindings, inbox, json, imConfigs, api, admission)).build();
        withoutBotId.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        when(api.botOpenIdForConfig(config)).thenReturn("ou_bot");
        MockMvc withUnrelatedMention = MockMvcBuilders.standaloneSetup(new FeishuCallbackController(
                mock(FeishuBindingDao.class), bindings, inbox, json, imConfigs, api, admission)).build();
        withUnrelatedMention.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content(groupEvent("cli_shared", "group", "@_user_1 群信息", null, "user",
                                "@_user_1", "ou_someone", "im.message.receive_v1")))
                .andExpect(status().isOk());

        verifyNoInteractions(noAdmissionApi, notAllowedApi, inbox);
        verify(api, never()).replyForConfig(any(), anyString(), anyString(), anyString());
        verify(bindings, never()).findShared(any(), anyString());
    }

    @Test
    void nonCommandMessagesFallThroughToTheSharedInbox() throws Exception {
        var imConfigs = mock(PlatformImChannelConfigService.class);
        var bindings = mock(FeishuBindingService.class);
        var inbox = mock(FeishuInbox.class);
        var api = mock(FeishuApiClient.class);
        var admission = mock(ImChannelAdmissionService.class);
        var config = config("FEISHU", 1);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config);
        when(bindings.secrets(config)).thenReturn(new FeishuBindingService.Secrets("sk", "vt", null));
        when(admission.allowsManagement("FEISHU")).thenReturn(true);
        when(api.botOpenIdForConfig(config)).thenReturn("ou_bot");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(new FeishuCallbackController(
                mock(FeishuBindingDao.class), bindings, inbox, json, imConfigs, api, admission)).build();
        String url = "/api/integrations/feishu/group-callback?configId=50";

        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content(groupEvent("cli_shared", "group", "hello", "image", "user",
                                "@_user_1", "ou_bot", "im.message.receive_v1")))
                .andExpect(status().isOk());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content(groupEvent("cli_shared", "group", "hello", null, "app",
                                "@_user_1", "ou_bot", "im.message.receive_v1")))
                .andExpect(status().isOk());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content(groupEvent("cli_shared", "group", null, null, "user",
                                "@_user_1", "ou_bot", "im.message.receive_v1")))
                .andExpect(status().isOk());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content(groupEvent("cli_shared", "group", "@_user_1", null, "user",
                                "", "ou_bot", "im.message.receive_v1")))
                .andExpect(status().isOk());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content(groupEvent("cli_shared", "group", "@Alice hi", null, "user",
                                null, null, "im.message.receive_v1")))
                .andExpect(status().isOk());

        verify(inbox, times(5)).acceptShared(eq(config), any());
        verifyNoInteractions(api);
    }

    private ObjectNode event(String appId, String chatType, String text, String messageType,
            String senderType, String mentionKey, String mentionOpenId, String eventType) {
        var root = json.createObjectNode();
        var header = root.putObject("header");
        header.put("token", "vt");
        header.put("app_id", appId);
        header.put("event_type", eventType);
        var payload = root.putObject("event");
        payload.putObject("sender").put("sender_type", senderType);
        var message = payload.putObject("message");
        message.put("chat_type", chatType);
        message.put("chat_id", "oc_group");
        message.put("message_id", "om_group");
        message.put("message_type", messageType == null ? "text" : messageType);
        try {
            message.put("content", text == null ? "not-json" : json.writeValueAsString(
                    java.util.Map.of("text", text)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        var mentions = message.putArray("mentions");
        if (mentionKey != null) {
            var mention = mentions.addObject();
            mention.put("key", mentionKey);
            mention.putObject("id").put("open_id", mentionOpenId);
            mention.put("name", "Bot");
        }
        return root;
    }

    private String groupEvent(String appId, String chatType, String text, String messageType,
            String senderType, String mentionKey, String mentionOpenId, String eventType) {
        return event(appId, chatType, text, messageType, senderType, mentionKey, mentionOpenId,
                eventType).toString();
    }

    private static PlatformImChannelConfigDO config(String provider, int enabled) {
        var config = new PlatformImChannelConfigDO();
        config.setId(50L);
        config.setProvider(provider);
        config.setPurpose("GROUP_CHAT");
        config.setAppKey("cli_shared");
        config.setEnabled(enabled);
        config.setRouteVersion(1L);
        return config;
    }

    private static FeishuBinding binding(String status) {
        var binding = new FeishuBinding();
        binding.setId(8L);
        binding.setBindingMode("SHARED");
        binding.setPlatformConfigId(50L);
        binding.setGroupId("oc_group");
        binding.setStatus(status);
        return binding;
    }
}
