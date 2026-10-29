package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.conversation.AgentConversationService;
import com.aliyun.autowonder.im.ImChannelAdmissionService;
import com.aliyun.autowonder.im.ImGroupChatCredentials;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DingTalkSharedInboundTest {

    private final DingtalkRobotBindingDao dao = mock(DingtalkRobotBindingDao.class);
    private final DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
    private final AgentConversationService conversations = mock(AgentConversationService.class);
    private final DingTalkMessageReactionService reactions = mock(DingTalkMessageReactionService.class);
    private final PlatformImChannelConfigService imConfigs =
            mock(PlatformImChannelConfigService.class);
    private final DingTalkOutboundSender sender = mock(DingTalkOutboundSender.class);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void routesMentionedSharedGroupMessagesWithSharedIdentifiers() throws Exception {
        var service = service(null);
        var config = config();
        when(imConfigs.findGroupChatById(50L)).thenReturn(config);
        when(dao.findSharedByConfigAndGroup(50L, "cid_group")).thenReturn(sharedRow());

        InboundBotMessage msg = message("hello", "text", "2", true, "https://hook", null);
        service.handle(msg, DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        verify(conversations).submitTurn(eq(1L), eq(7L), eq("DINGTALK"),
                eq("5:cid_group"), eq("hello"), eq("DINGTALK:5:m-1"),
                argThat(source -> {
                    try {
                        var snapshot = json.readTree(
                                json.readTree(source).path("routeSnapshot").asText());
                        return snapshot.path("bindingMode").asText().equals("SHARED")
                                && snapshot.path("platformConfigId").asLong() == 50L
                                && snapshot.path("bindingRouteVersion").asLong() == 2L
                                && snapshot.path("configRouteVersion").asLong() == 6L
                                && snapshot.path("groupId").asText().equals("cid_group");
                    } catch (Exception e) {
                        return false;
                    }
                }));
        verify(reactions).markThinking(any(), eq("cid_group"), eq("m-1"));
    }

    @Test
    void ignoresSharedMessagesOutsideTheGroupContract() {
        var service = service(null);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config());
        when(dao.findSharedByConfigAndGroup(50L, "cid_group")).thenReturn(sharedRow());

        service.handle(message("hello", "text", "2", true, null, null),
                DingTalkStreamScope.shared(99L, "ding-app", "ding-robot"));
        service.handle(message("hello", "text", "2", true, null, null),
                DingTalkStreamScope.shared(50L, "ding-app", "other-robot"));
        service.handle(message("hello", "text", "1", true, null, null),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));
        service.handle(message("hello", "picture", "2", true, null, null),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));
        service.handle(message("hello", "text", "2", false, null, null),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));
        service.handle(message("hello", "text", "2", true, null, null),
                DingTalkStreamScope.shared(null, "ding-app", "ding-robot"));

        var disabled = sharedRow();
        disabled.setStatus("DISABLED");
        when(dao.findSharedByConfigAndGroup(50L, "cid_group")).thenReturn(disabled);
        service.handle(message("hello", "text", "2", true, null, null),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        when(dao.findSharedByConfigAndGroup(50L, "cid_group")).thenReturn(null);
        service.handle(message("hello", "text", "2", true, null, null),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        var disabledConfig = config();
        disabledConfig.setEnabled(0);
        when(imConfigs.findGroupChatById(50L)).thenReturn(disabledConfig);
        service.handle(message("hello", "text", "2", true, null, null),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        verifyNoInteractions(conversations, reactions);
    }

    @Test
    void enabledSharedBindingWithoutAnyConfirmationStateIsRouted() {
        var service = service(null);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config());
        // A binding upgraded from the confirmation era has no confirmation row at all.
        when(dao.findSharedByConfigAndGroup(50L, "cid_group")).thenReturn(sharedRow());

        service.handle(message("hello", "text", "2", true, null, null),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        verify(conversations).submitTurn(eq(1L), eq(7L), eq("DINGTALK"), eq("5:cid_group"),
                eq("hello"), eq("DINGTALK:5:m-1"), anyString());
        verify(reactions).markThinking(any(), eq("cid_group"), eq("m-1"));
    }

    @Test
    void sharedGroupInfoCommandsReplyThroughSessionWebhook() {
        var admission = mock(ImChannelAdmissionService.class);
        when(admission.allowsManagement("DINGTALK")).thenReturn(true);
        var service = service(admission);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config());
        when(dao.findSharedByConfigAndGroup(50L, "cid_group")).thenReturn(sharedRow());

        service.handle(message("群信息", "text", "2", true, "https://hook",
                System.currentTimeMillis() + 60_000), DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        verify(sender).sendSessionWebhookText("https://hook",
                "群信息\n群 ID：cid_group\n绑定状态：已绑定\n启用状态：已启用", List.of());
        verifyNoInteractions(conversations);
    }

    @Test
    void managementCommandsRequireTheSelectedChannel() {
        var admission = mock(ImChannelAdmissionService.class);
        var service = service(admission);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config());
        when(admission.allowsManagement("DINGTALK")).thenReturn(false);

        service.handle(message("群信息", "text", "2", true, "https://hook",
                System.currentTimeMillis() + 60_000), DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        verifyNoInteractions(sender, conversations, bindingService);
    }

    @Test
    void legacyBindingCommandRepliesWithRemovalGuidanceThroughEitherChannel() {
        var admission = mock(ImChannelAdmissionService.class);
        var service = service(admission);
        var config = config();
        when(imConfigs.findGroupChatById(50L)).thenReturn(config);
        when(admission.allowsManagement("DINGTALK")).thenReturn(true);
        when(dao.findSharedByConfigAndGroup(50L, "cid_group")).thenReturn(sharedRow());
        String expected = "群确认已取消，请在空间消息渠道集成中管理群绑定。";

        service.handle(message("绑定群聊:whatever-code", "text", "2", true, "https://hook",
                System.currentTimeMillis() + 60_000), DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));
        verify(sender).sendSessionWebhookText("https://hook", expected, List.of());

        when(imConfigs.decryptGroupChatCredentials(config))
                .thenReturn(new ImGroupChatCredentials("ding-secret", null, null));
        service.handle(message("绑定群聊：whatever-code", "text", "2", true, null,
                System.currentTimeMillis() - 1_000), DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));
        verify(sender).sendGroupText(eq("ding-app"), eq("ding-secret"), isNull(),
                eq("ding-robot"), eq("cid_group"), eq(expected), eq(List.of()), anyLong());
        verify(conversations, never()).submitTurn(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void historicalBindingCommandsGetGuidanceWithoutConfirming() {
        var admission = mock(ImChannelAdmissionService.class);
        var service = service(admission);
        var config = config();
        when(imConfigs.findGroupChatById(50L)).thenReturn(config);
        when(admission.allowsManagement("DINGTALK")).thenReturn(true);
        when(dao.findSharedByConfigAndGroup(50L, "cid_group")).thenReturn(sharedRow());

        service.handle(message("确认 anything", "text", "2", true, "https://hook",
                System.currentTimeMillis() + 60_000), DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));
        verify(sender).sendSessionWebhookText("https://hook",
                "群确认已取消，请在空间消息渠道集成中管理群绑定。", List.of());
        verify(conversations, never()).submitTurn(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void sharedAdmissionPersistsBeforeSubmitting() {
        var admission = mock(ImChannelAdmissionService.class);
        var service = service(admission);
        var config = config();
        when(imConfigs.findGroupChatById(50L)).thenReturn(config);
        when(dao.findSharedByConfigAndGroup(50L, "cid_group")).thenReturn(sharedRow());
        when(conversations.hasExternalMessage(1L, "DINGTALK:5:m-1")).thenReturn(false);
        when(dao.findByIdGlobal(5L)).thenReturn(sharedRow());
        when(admission.admit(eq("DINGTALK"), any(), any())).thenAnswer(invocation -> {
            ImChannelAdmissionService.ReceiptWriter writer = invocation.getArgument(2);
            var context = new ImChannelAdmissionService.Context("DINGTALK", "DINGTALK", 3L,
                    Instant.now().minusSeconds(60), config);
            var snapshot = writer.persist(context);
            return new ImChannelAdmissionService.Result(
                    ImChannelAdmissionService.Decision.ACCEPTED, "accepted", snapshot);
        });

        service.handle(message("hello", "text", "2", true, null, null),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        verify(conversations).submitTurn(eq(1L), eq(7L), eq("DINGTALK"), eq("5:cid_group"),
                eq("hello"), eq("DINGTALK:5:m-1"), anyString());
        verify(reactions).markThinking(any(), eq("cid_group"), eq("m-1"));
    }

    @Test
    void sharedAdmissionRejectsWhenBindingChangedBeforePersist() {
        var admission = mock(ImChannelAdmissionService.class);
        var service = service(admission);
        var config = config();
        when(imConfigs.findGroupChatById(50L)).thenReturn(config);
        when(dao.findSharedByConfigAndGroup(50L, "cid_group")).thenReturn(sharedRow());
        when(dao.findByIdGlobal(5L)).thenReturn(null);
        when(admission.admit(eq("DINGTALK"), any(), any())).thenAnswer(invocation -> {
            ImChannelAdmissionService.ReceiptWriter writer = invocation.getArgument(2);
            var context = new ImChannelAdmissionService.Context("DINGTALK", "DINGTALK", 3L,
                    Instant.now().minusSeconds(60), config);
            var snapshot = writer.persist(context);
            return snapshot == null
                    ? new ImChannelAdmissionService.Result(
                            ImChannelAdmissionService.Decision.IGNORED, "not_admitted", null)
                    : new ImChannelAdmissionService.Result(
                            ImChannelAdmissionService.Decision.ACCEPTED, "accepted", snapshot);
        });

        service.handle(message("hello", "text", "2", true, null, null),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        verify(conversations, never()).submitTurn(anyLong(), anyLong(), anyString(), anyString(),
                anyString(), anyString(), anyString());
        verifyNoInteractions(reactions);
    }

    @Test
    void helperMethodsExposeScopeSafeValues() {
        assertEquals("群信息", DingTalkInboundService.commandText("@bot 群信息"));
        assertEquals("@bot", DingTalkInboundService.commandText("@bot"));
        assertNull(DingTalkInboundService.commandText("  "));
        assertNull(DingTalkInboundService.eventTime(message("x", "text", "2", true, null, 0L)));
        assertEquals(Instant.ofEpochMilli(123L),
                DingTalkInboundService.eventTime(message("x", "text", "2", true, null, 123L)));
        assertEquals("DINGTALK:5:m-1", DingTalkInboundService.sharedExternalId(5L, "m-1"));
        assertEquals("5:cid_group", DingTalkInboundService.sharedConversationId(5L, "cid_group"));

        var service = service(null);
        var binding = sharedRow();
        when(dao.findByRobotCodeGlobal("rc")).thenReturn(binding);
        when(bindingService.decryptSecret(binding)).thenReturn("secret");
        assertEquals(binding, service.bindingForVerification("rc"));
        assertEquals("secret", service.secretOf(binding));
    }

    private DingTalkInboundService service(ImChannelAdmissionService admission) {
        return new DingTalkInboundService(dao, bindingService, conversations, reactions, admission,
                imConfigs, sender, json);
    }

    private InboundBotMessage message(String text, String msgType, String conversationType,
            boolean atList, String webhook, Long createAt) {
        return new InboundBotMessage("cid_group", "Requirements group", conversationType,
                "user1", "Wang Wu", "staff-1", "corp-1",
                List.of(new InboundBotMessage.AtUser("dt-1", "staff-1")), atList, "corp-1",
                "bot-user", "ding-robot", text, "m-1", msgType, webhook,
                webhook == null ? null : 4102444800000L, createAt, "{}");
    }

    private static DingtalkRobotBindingDO sharedRow() {
        DingtalkRobotBindingDO row = new DingtalkRobotBindingDO();
        row.setId(5L);
        row.setTenantId(1L);
        row.setBindingMode("SHARED");
        row.setPlatformConfigId(50L);
        row.setGroupId("cid_group");
        row.setAppKey("ding-app");
        row.setRobotCode("ding-robot");
        row.setAgentId(7L);
        row.setRouteVersion(2L);
        row.setStatus("ENABLED");
        return row;
    }

    private static PlatformImChannelConfigDO config() {
        var config = new PlatformImChannelConfigDO();
        config.setId(50L);
        config.setProvider("DINGTALK");
        config.setPurpose("GROUP_CHAT");
        config.setAppKey("ding-app");
        config.setRobotCode("ding-robot");
        config.setEnabled(1);
        config.setRouteVersion(6L);
        return config;
    }
}
