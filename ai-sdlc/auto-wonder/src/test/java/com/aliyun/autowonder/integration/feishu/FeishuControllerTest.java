package com.aliyun.autowonder.integration.feishu;

import com.aliyun.autowonder.access.*;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import java.util.List;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class FeishuControllerTest {
    @AfterEach void cleanup() { AutoWonderContext.destroy(); }
    @Test void bindingEndpointsRequireAdminBeforeReadingOrWriting() {
        var service = mock(FeishuBindingService.class);
        var brandingService = mock(PlatformBrandingService.class);
        var factory = new AspectJProxyFactory(new FeishuBindingController(service, brandingService, mock(com.aliyun.autowonder.im.PlatformImChannelConfigService.class)));
        factory.addAspect(new WorkspaceAccessAspect());
        FeishuBindingController controller = factory.getProxy();
        for (var access : new WorkspaceAccessLevel[]{WorkspaceAccessLevel.READ_ONLY, WorkspaceAccessLevel.READ_WRITE}) {
            AutoWonderContext.get().setCurrentWorkspaceId(1L); AutoWonderContext.get().setUserId(9L);
            AutoWonderContext.get().setWorkspaceAccessLevel(access);
            assertThrows(WorkspaceAccessDeniedException.class, controller::list);
            assertThrows(WorkspaceAccessDeniedException.class, () -> controller.create(null));
            assertThrows(WorkspaceAccessDeniedException.class, () -> controller.update(1L, null));
            assertThrows(WorkspaceAccessDeniedException.class, () -> controller.delete(1L));
        }
        verifyNoInteractions(service);
    }
    @Test void bindingViewCallbackUrlFollowsTheBrandingDomainWithoutRestart() {
        var service = mock(FeishuBindingService.class);
        var brandingService = mock(PlatformBrandingService.class);
        var controller = new FeishuBindingController(service, brandingService, mock(com.aliyun.autowonder.im.PlatformImChannelConfigService.class));
        var binding = new FeishuBinding();
        binding.setId(7L);
        binding.setAppId("cli_test");
        binding.setAgentId(3L);
        binding.setStatus("ENABLED");
        when(service.list(1L)).thenReturn(List.of(binding));
        when(service.secrets(binding)).thenReturn(new FeishuBindingService.Secrets("sk", "vt", "ek"));
        when(brandingService.effectivePublicBaseUrl()).thenReturn("https://wonder.example.com");
        AutoWonderContext.get().setCurrentWorkspaceId(1L);

        assertEquals("https://wonder.example.com/api/integrations/feishu/callback?bindingId=7",
                controller.list().getData().get(0).callbackUrl());

        when(brandingService.effectivePublicBaseUrl()).thenReturn("https://daily.auto-wonder.example.com");

        assertEquals("https://daily.auto-wonder.example.com/api/integrations/feishu/callback?bindingId=7",
                controller.list().getData().get(0).callbackUrl());
    }
    @Test void callbackHandlesChallengeChecksAppAndNeverEnqueuesUnauthenticatedMessages() throws Exception {
        var f = new FeishuTestSupport(); var b = f.create(); var inbox = mock(FeishuInbox.class);
        var mvc = MockMvcBuilders.standaloneSetup(new FeishuCallbackController(f.dao, f.service, inbox, f.json)).build();
        var url = "/api/integrations/feishu/callback?bindingId=" + b.getId();
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"url_verification\",\"token\":\"verify-token\",\"challenge\":\"hello\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.challenge").value("hello"));
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content("{}" )).andExpect(status().isUnauthorized());
        String event = "{\"header\":{\"token\":\"verify-token\",\"app_id\":\"cli_other\",\"event_type\":\"im.message.receive_v1\"},\"event\":{}}";
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(event)).andExpect(status().isForbidden());
        verifyNoInteractions(inbox);
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(event.replace("cli_other", "cli_test"))).andExpect(status().isOk());
        verify(inbox).accept(eq(b), any());
        clearInvocations(inbox);
        f.jdbc.update("UPDATE feishu_robot_binding SET status='DISABLED'");
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(event.replace("cli_other", "cli_test"))).andExpect(status().isOk());
        verifyNoInteractions(inbox);
    }

    @Test void bindingViewForSharedBindingUsesGroupCallbackWithoutConfirmationState() {
        var service = mock(FeishuBindingService.class);
        var brandingService = mock(PlatformBrandingService.class);
        var controller = new FeishuBindingController(service, brandingService,
                mock(com.aliyun.autowonder.im.PlatformImChannelConfigService.class));
        var binding = new FeishuBinding();
        binding.setId(7L);
        binding.setBindingMode("SHARED");
        binding.setPlatformConfigId(50L);
        binding.setGroupId("oc_group");
        binding.setRouteVersion(2L);
        binding.setAgentId(3L);
        binding.setStatus("ENABLED");
        when(service.isShared(binding)).thenReturn(true);
        when(service.encryptKeyConfigured(binding)).thenReturn(true);
        when(brandingService.effectivePublicBaseUrl()).thenReturn("https://wonder.example.com");
        AutoWonderContext.get().setCurrentWorkspaceId(1L);

        FeishuBindingController.View view = controller.view(binding);

        assertEquals("SHARED", view.bindingMode());
        assertEquals(50L, view.platformConfigId());
        assertEquals(2L, view.routeVersion());
        assertTrue(view.encryptKeyConfigured());
        assertEquals("https://wonder.example.com/api/integrations/feishu/group-callback?configId=50",
                view.callbackUrl());
    }

    @Test void createRequiresSelectedChannelAndRejectsLegacyPayload() {
        var service = mock(FeishuBindingService.class);
        var imConfigs = mock(com.aliyun.autowonder.im.PlatformImChannelConfigService.class);
        var controller = new FeishuBindingController(service, mock(PlatformBrandingService.class), imConfigs);
        AutoWonderContext.get().setCurrentWorkspaceId(1L);
        AutoWonderContext.get().setUserId(9L);
        var sharedRequest = new FeishuBindingService.Request(null, null, null, null, false, 3L,
                "ENABLED", null, "oc_group", "SHARED");
        var saved = new FeishuBinding();
        saved.setId(7L);
        saved.setBindingMode("SHARED");
        saved.setPlatformConfigId(50L);
        saved.setGroupId("oc_group");
        when(service.save(1L, 9L, null, sharedRequest)).thenReturn(saved);
        when(service.isShared(saved)).thenReturn(true);

        assertEquals(7L, controller.create(sharedRequest).getData().id());

        verify(imConfigs).requireSelected("FEISHU");
        verify(service).save(1L, 9L, null, sharedRequest);

        assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                () -> controller.create(new FeishuBindingService.Request("cli_legacy", "s", "v",
                        null, false, 3L, "ENABLED", null, "oc_group", "SHARED")));
        assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                () -> controller.create(new FeishuBindingService.Request(null, null, null, null,
                        false, 3L, "ENABLED", null, "oc_group", "LEGACY")));
        verify(service, times(1)).save(any(), any(), any(), any());
    }

    @Test void updateKeepsBindingModeAndDelegatesValidEdits() {
        var service = mock(FeishuBindingService.class);
        var controller = new FeishuBindingController(service, mock(PlatformBrandingService.class),
                mock(com.aliyun.autowonder.im.PlatformImChannelConfigService.class));
        AutoWonderContext.get().setCurrentWorkspaceId(1L);
        AutoWonderContext.get().setUserId(9L);
        var legacy = new FeishuBinding();
        legacy.setId(7L);
        var shared = new FeishuBinding();
        shared.setId(8L);
        shared.setBindingMode("SHARED");
        when(service.get(1L, 7L)).thenReturn(legacy);
        when(service.get(1L, 8L)).thenReturn(shared);
        when(service.isShared(shared)).thenReturn(true);
        var sharedEdit = new FeishuBindingService.Request(null, null, null, null, false, 3L,
                "DISABLED", 2, "oc_group", "SHARED");
        when(service.save(1L, 9L, 8L, sharedEdit)).thenReturn(shared);

        assertEquals(8L, controller.update(8L, sharedEdit).getData().id());
        verify(service).save(1L, 9L, 8L, sharedEdit);

        assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                () -> controller.update(7L, new FeishuBindingService.Request(null, null, null, null,
                        false, 3L, "ENABLED", null, "oc_group", "SHARED")));
        assertThrows(com.aliyun.autowonder.common.error.BizException.class,
                () -> controller.update(8L, new FeishuBindingService.Request(null, null, null, null,
                        false, 3L, "ENABLED", null, "oc_group", "LEGACY")));
    }

    @Test void groupCallbackAuthenticatesSharedConfigAndRoutesGroupEvents() throws Exception {
        var f = new FeishuTestSupport();
        var imConfigs = mock(com.aliyun.autowonder.im.PlatformImChannelConfigService.class);
        var bindings = mock(FeishuBindingService.class);
        var inbox = mock(FeishuInbox.class);
        var api = mock(FeishuApiClient.class);
        var config = new com.aliyun.autowonder.im.PlatformImChannelConfigDO();
        config.setId(50L);
        config.setProvider("FEISHU");
        config.setAppKey("cli_shared");
        config.setEnabled(1);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config);
        when(bindings.secrets(config)).thenReturn(new FeishuBindingService.Secrets("sk", "vt", null));
        var mvc = MockMvcBuilders.standaloneSetup(new FeishuCallbackController(
                mock(FeishuBindingDao.class), bindings, inbox, f.json, imConfigs, api, null)).build();
        String url = "/api/integrations/feishu/group-callback?configId=50";

        mvc.perform(post("/api/integrations/feishu/group-callback?configId=51")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"url_verification\",\"token\":\"vt\",\"challenge\":\"hi\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"url_verification\",\"token\":\"vt\",\"challenge\":\"hi\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.challenge").value("hi"));
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"url_verification\",\"token\":\"wrong\",\"challenge\":\"hi\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content(groupEvent(f.json, "cli_other", "group", "hello")))
                .andExpect(status().isForbidden());
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content(groupEvent(f.json, "cli_shared", "p2p", "hello")))
                .andExpect(status().isOk());
        verifyNoInteractions(inbox);

        var sharedEvent = groupEvent(f.json, "cli_shared", "group", "hello");
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(sharedEvent))
                .andExpect(status().isOk());
        var payload = f.json.readTree(sharedEvent).path("event");
        verify(inbox).acceptShared(eq(config), eq(payload));

        config.setEnabled(0);
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(sharedEvent))
                .andExpect(status().isOk());
        verify(inbox, times(1)).acceptShared(any(), any());
    }

    @Test void groupCallbackAnswersGroupCommandsWithSharedCredentials() throws Exception {
        var f = new FeishuTestSupport();
        var imConfigs = mock(com.aliyun.autowonder.im.PlatformImChannelConfigService.class);
        var bindings = mock(FeishuBindingService.class);
        var inbox = mock(FeishuInbox.class);
        var api = mock(FeishuApiClient.class);
        var admission = mock(com.aliyun.autowonder.im.ImChannelAdmissionService.class);
        var config = new com.aliyun.autowonder.im.PlatformImChannelConfigDO();
        config.setId(50L);
        config.setProvider("FEISHU");
        config.setAppKey("cli_shared");
        config.setEnabled(1);
        var binding = new FeishuBinding();
        binding.setId(8L);
        binding.setBindingMode("SHARED");
        binding.setGroupId("oc_group");
        when(imConfigs.findGroupChatById(50L)).thenReturn(config);
        when(bindings.secrets(config)).thenReturn(new FeishuBindingService.Secrets("sk", "vt", null));
        when(admission.allowsManagement("FEISHU")).thenReturn(true);
        when(api.botOpenIdForConfig(config)).thenReturn("ou_bot");
        when(bindings.findShared(50L, "oc_group")).thenReturn(binding);
        var mvc = MockMvcBuilders.standaloneSetup(new FeishuCallbackController(
                mock(FeishuBindingDao.class), bindings, inbox, f.json, imConfigs, api, admission)).build();
        String url = "/api/integrations/feishu/group-callback?configId=50";

        String groupInfo = groupEventMentioningBot(f.json, "群信息");
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(groupInfo))
                .andExpect(status().isOk());
        verify(api).replyForConfig(config, "om_group",
                "群信息\n群 ID：oc_group\n绑定状态：已绑定\n启用状态：已停用", "om_group");
        verifyNoInteractions(inbox);

        String code = "abcdefghijklmnopqrstuvwxyz0123456789_-abcde";
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content(groupEventMentioningBot(f.json, "绑定群聊:" + code)))
                .andExpect(status().isOk());
        verify(api).replyForConfig(config, "om_group",
                "群确认已取消，请在空间消息渠道集成中管理群绑定。", "om_group");

        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content(groupEventMentioningBot(f.json, "确认 " + code)))
                .andExpect(status().isOk());
        verify(api, times(2)).replyForConfig(config, "om_group",
                "群确认已取消，请在空间消息渠道集成中管理群绑定。", "om_group");

        when(admission.allowsManagement("FEISHU")).thenReturn(false);
        clearInvocations(api, inbox);
        mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(groupInfo))
                .andExpect(status().isOk());
        verifyNoInteractions(api, inbox);
    }

    private static String groupEvent(com.fasterxml.jackson.databind.ObjectMapper json, String appId,
            String chatType, String text) throws Exception {
        var root = json.createObjectNode();
        var header = root.putObject("header");
        header.put("token", "vt");
        header.put("app_id", appId);
        header.put("event_type", "im.message.receive_v1");
        var event = root.putObject("event");
        event.putObject("sender").put("sender_type", "user");
        var message = event.putObject("message");
        message.put("chat_type", chatType);
        message.put("chat_id", "oc_group");
        message.put("message_id", "om_group");
        message.put("message_type", "text");
        message.put("content", json.writeValueAsString(java.util.Map.of("text", text)));
        message.putArray("mentions");
        return json.writeValueAsString(root);
    }

    private static String groupEventMentioningBot(com.fasterxml.jackson.databind.ObjectMapper json,
            String text) throws Exception {
        var root = json.readTree(groupEvent(json, "cli_shared", "group", "@_user_1 " + text));
        var mention = ((com.fasterxml.jackson.databind.node.ObjectNode) root.path("event")
                .path("message")).withArray("mentions").addObject();
        mention.put("key", "@_user_1");
        mention.putObject("id").put("open_id", "ou_bot");
        mention.put("name", "Bot");
        return json.writeValueAsString(root);
    }
}
