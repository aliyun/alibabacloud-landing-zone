package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.conversation.AgentConversationService;
import com.aliyun.autowonder.im.ImChannelAdmissionService;
import com.aliyun.autowonder.im.ImGroupChatCredentials;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.json.JSONObject;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Admission and reply branches of the DingTalk inbound path not covered by the happy suites. */
class DingTalkInboundBranchTest {

    private static final String GROUP_INFO = "群信息";
    private static final String CONFIRM_REMOVED = "群确认已取消，请在空间消息渠道集成中管理群绑定。";

    private final DingtalkRobotBindingDao dao = mock(DingtalkRobotBindingDao.class);
    private final DingTalkBindingService bindingService = mock(DingTalkBindingService.class);
    private final AgentConversationService conversations = mock(AgentConversationService.class);
    private final DingTalkMessageReactionService reactions =
            mock(DingTalkMessageReactionService.class);
    private final PlatformImChannelConfigService imConfigs =
            mock(PlatformImChannelConfigService.class);
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void legacyScopeNullAndExplicitScopesShareTheRobotBinding() {
        var sender = mock(DingTalkOutboundSender.class);
        var service = new DingTalkInboundService(dao, bindingService, conversations, reactions,
                null, imConfigs, sender, json);
        var binding = legacyRow("rc");
        when(dao.findByRobotCodeGlobal("rc")).thenReturn(binding);

        var msg = message("hello", "text", "2", true, atUsers(), null, null, 1000L, "cid", "rc");
        service.handle(msg, null);
        service.handle(msg, DingTalkStreamScope.legacy());

        verify(conversations, times(2)).submitTurn(eq(1L), eq(3L), eq("DINGTALK"),
                eq("cid"), eq("hello"), eq("m-1"), anyString());
        verify(reactions, times(2)).markThinking(binding, "cid", "m-1");

        binding.setStatus("DISABLED");
        service.handle(msg);
        verify(conversations, times(2)).submitTurn(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void legacyAdmissionRequiresTheCurrentEnabledLegacyBinding() {
        var admission = mock(ImChannelAdmissionService.class);
        var service = new DingTalkInboundService(dao, bindingService, conversations, reactions,
                admission, imConfigs, null, json);
        var binding = legacyRow("rc");
        when(dao.findByRobotCodeGlobal("rc")).thenReturn(binding);
        var current = new AtomicReference<>(legacyRow("rc"));
        when(admission.admit(eq("DINGTALK"), any(), any())).thenAnswer(invocation -> {
            ImChannelAdmissionService.ReceiptWriter writer = invocation.getArgument(2);
            var context = new ImChannelAdmissionService.Context("DINGTALK", "DINGTALK", 5L,
                    Instant.now().minusSeconds(60), null);
            if (writer.alreadyReceived(context)) {
                return new ImChannelAdmissionService.Result(
                        ImChannelAdmissionService.Decision.DUPLICATE, "duplicate", null);
            }
            var snapshot = writer.persist(context);
            return snapshot == null
                    ? new ImChannelAdmissionService.Result(
                            ImChannelAdmissionService.Decision.IGNORED, "not_admitted", null)
                    : new ImChannelAdmissionService.Result(
                            ImChannelAdmissionService.Decision.ACCEPTED, "accepted", snapshot);
        });
        var msg = message("hello", "text", "2", true, atUsers(), null, null, 1000L, "cid", "rc");

        when(conversations.hasExternalMessage(1L, "m-1")).thenReturn(true);
        service.handle(msg);

        when(conversations.hasExternalMessage(1L, "m-1")).thenReturn(false);
        current.set(null);
        service.handle(msg);

        var disabled = legacyRow("rc");
        disabled.setStatus("DISABLED");
        current.set(disabled);
        service.handle(msg);

        var shared = legacyRow("rc");
        shared.setBindingMode("SHARED");
        current.set(shared);
        service.handle(msg);

        current.set(legacyRow("other"));
        service.handle(msg);

        verify(conversations, never()).submitTurn(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(reactions);

        current.set(legacyRow("rc"));
        when(dao.findByIdGlobal(5L)).thenReturn(current.get());
        service.handle(msg);

        verify(conversations).submitTurn(eq(1L), eq(3L), eq("DINGTALK"), eq("cid"),
                eq("hello"), eq("m-1"), anyString());
        verify(reactions).markThinking(binding, "cid", "m-1");
    }

    @Test
    void legacyAdmissionSerializesSourcesButFallsBackWhenTheSnapshotFails() throws Exception {
        var broken = mock(ObjectMapper.class);
        when(broken.writeValueAsString(any(Object.class)))
                .thenThrow(new IllegalStateException("cannot serialize"));
        var service = new DingTalkInboundService(dao, bindingService, conversations, reactions,
                null, imConfigs, null, broken);
        var binding = legacyRow("rc");
        when(dao.findByRobotCodeGlobal("rc")).thenReturn(binding);

        service.handle(message("hello", "text", "2", true, atUsers(), null, null, 1000L, "cid",
                "rc"));

        verify(conversations).submitTurn(eq(1L), eq(3L), eq("DINGTALK"), eq("cid"),
                eq("hello"), eq("m-1"), argThat(source ->
                        JSONObject.parseObject(source).getString("routeSnapshot") == null));
    }

    @Test
    void sharedScopeRequiresConfigAndRobotIdentity() {
        var service = new DingTalkInboundService(dao, bindingService, conversations, reactions,
                null, imConfigs, null, json);
        when(imConfigs.findGroupChatById(50L)).thenReturn(null);
        service.handle(sharedMessage("hello", true, "cid_group", "ding-robot"),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        var foreign = config();
        foreign.setProvider("FEISHU");
        when(imConfigs.findGroupChatById(50L)).thenReturn(foreign);
        service.handle(sharedMessage("hello", true, "cid_group", "ding-robot"),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        var disabled = config();
        disabled.setEnabled(0);
        when(imConfigs.findGroupChatById(50L)).thenReturn(disabled);
        service.handle(sharedMessage("hello", true, "cid_group", "ding-robot"),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        var otherRobot = config();
        otherRobot.setRobotCode("other-robot");
        when(imConfigs.findGroupChatById(50L)).thenReturn(otherRobot);
        service.handle(sharedMessage("hello", true, "cid_group", "ding-robot"),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        var anyRobot = config();
        anyRobot.setRobotCode(null);
        when(imConfigs.findGroupChatById(50L)).thenReturn(anyRobot);
        service.handle(sharedMessage("hello", true, "cid_group", "ding-robot"),
                DingTalkStreamScope.shared(50L, "ding-app", null));

        when(imConfigs.findGroupChatById(50L)).thenReturn(config());
        service.handle(sharedMessage("hello", true, "cid_group", null),
                DingTalkStreamScope.shared(50L, "ding-app", null));

        when(imConfigs.findGroupChatById(50L)).thenReturn(config());
        service.handle(sharedMessage("hello", true, "   ", "ding-robot"),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));
        service.handle(sharedMessage("hello", false, "cid_group", "ding-robot"),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));
        service.handle(sharedMessage("hello", true, "cid_group", "ding-robot"),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        verifyNoInteractions(conversations, reactions);
    }

    @Test
    void sharedScopeAcceptsMentionsFromTheAtList() {
        var service = new DingTalkInboundService(dao, bindingService, conversations, reactions,
                null, imConfigs, null, json);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config());
        var binding = sharedRow();
        when(dao.findSharedByConfigAndGroup(50L, "cid_group")).thenReturn(binding);

        var msg = new InboundBotMessage("cid_group", "Requirements group", "2", "user1",
                "Wang Wu", "staff-1", "corp-1",
                List.of(new InboundBotMessage.AtUser("bot-user", null)), false, "corp-1",
                "bot-user", "ding-robot", "hello", "m-1", "text", null, null, 1000L, "{}");
        service.handle(msg, DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        verify(conversations).submitTurn(eq(1L), eq(7L), eq("DINGTALK"), eq("5:cid_group"),
                eq("hello"), eq("DINGTALK:5:m-1"), anyString());
    }

    @Test
    void sharedAdmissionWithoutServiceSkipsDuplicatesAndBlankText() {
        var service = new DingTalkInboundService(dao, bindingService, conversations, reactions,
                null, imConfigs, null, json);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config());
        when(dao.findSharedByConfigAndGroup(50L, "cid_group")).thenReturn(sharedRow());
        when(conversations.hasExternalMessage(1L, "DINGTALK:5:m-1")).thenReturn(true);

        service.handle(sharedMessage("hello", true, "cid_group", "ding-robot"),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        verify(conversations).hasExternalMessage(1L, "DINGTALK:5:m-1");
        verify(conversations, never()).submitTurn(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(reactions);

        when(conversations.hasExternalMessage(1L, "DINGTALK:5:m-1")).thenReturn(false);
        service.handle(sharedMessage("   ", true, "cid_group", "ding-robot"),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        verify(conversations, never()).submitTurn(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(reactions);

        service.handle(sharedMessage("hello", true, "cid_group", "ding-robot"),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        verify(conversations).submitTurn(eq(1L), eq(7L), eq("DINGTALK"), eq("5:cid_group"),
                eq("hello"), eq("DINGTALK:5:m-1"), anyString());
        verify(reactions).markThinking(any(), eq("cid_group"), eq("m-1"));
    }

    @Test
    void sharedAdmissionRejectsReroutedConfigsAndBindings() {
        var admission = mock(ImChannelAdmissionService.class);
        var service = new DingTalkInboundService(dao, bindingService, conversations, reactions,
                admission, imConfigs, null, json);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config());
        var ready = sharedRow();
        when(dao.findSharedByConfigAndGroup(50L, "cid_group")).thenReturn(ready);
        var currentConfig = new AtomicReference<>(config());
        var currentBinding = new AtomicReference<>(sharedRow());
        when(dao.findByIdGlobal(5L)).thenAnswer(invocation -> currentBinding.get());
        when(admission.admit(eq("DINGTALK"), any(), any())).thenAnswer(invocation -> {
            ImChannelAdmissionService.ReceiptWriter writer = invocation.getArgument(2);
            var context = new ImChannelAdmissionService.Context("DINGTALK", "DINGTALK", 5L,
                    Instant.now().minusSeconds(60), currentConfig.get());
            var snapshot = writer.persist(context);
            return snapshot == null
                    ? new ImChannelAdmissionService.Result(
                            ImChannelAdmissionService.Decision.IGNORED, "not_admitted", null)
                    : new ImChannelAdmissionService.Result(
                            ImChannelAdmissionService.Decision.ACCEPTED, "accepted", snapshot);
        });
        var msg = sharedMessage("hello", true, "cid_group", "ding-robot");
        var scope = DingTalkStreamScope.shared(50L, "ding-app", "ding-robot");

        currentConfig.set(null);
        service.handle(msg, scope);

        var foreign = config();
        foreign.setProvider("FEISHU");
        currentConfig.set(foreign);
        service.handle(msg, scope);

        var disabled = config();
        disabled.setEnabled(0);
        currentConfig.set(disabled);
        service.handle(msg, scope);

        var otherRobot = config();
        otherRobot.setRobotCode("other-robot");
        currentConfig.set(otherRobot);
        service.handle(msg, scope);

        currentConfig.set(config());
        currentBinding.set(null);
        service.handle(msg, scope);

        var legacy = sharedRow();
        legacy.setBindingMode("LEGACY");
        currentBinding.set(legacy);
        service.handle(msg, scope);

        var disabledBinding = sharedRow();
        disabledBinding.setStatus("DISABLED");
        currentBinding.set(disabledBinding);
        service.handle(msg, scope);

        var otherConfig = sharedRow();
        otherConfig.setPlatformConfigId(99L);
        currentBinding.set(otherConfig);
        service.handle(msg, scope);

        var otherGroup = sharedRow();
        otherGroup.setGroupId("other-group");
        currentBinding.set(otherGroup);
        service.handle(msg, scope);

        verify(conversations, never()).submitTurn(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(reactions);

        currentBinding.set(sharedRow());
        service.handle(msg, scope);

        verify(conversations).submitTurn(eq(1L), eq(7L), eq("DINGTALK"), eq("5:cid_group"),
                eq("hello"), eq("DINGTALK:5:m-1"), anyString());
        verify(reactions).markThinking(any(), eq("cid_group"), eq("m-1"));
    }

    @Test
    void groupInfoRepliesCoverEveryBindingStateAndLegacyCommandsAreRejected() {
        var admission = mock(ImChannelAdmissionService.class);
        when(admission.allowsManagement("DINGTALK")).thenReturn(true);
        var sender = mock(DingTalkOutboundSender.class);
        var service = new DingTalkInboundService(dao, bindingService, conversations, reactions,
                admission, imConfigs, sender, json);
        var config = config();
        when(imConfigs.findGroupChatById(50L)).thenReturn(config);
        String webhook = "https://hook";
        Long expires = System.currentTimeMillis() + 60_000;

        service.handle(groupCommand(GROUP_INFO, webhook, expires),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));
        verify(sender).sendSessionWebhookText(webhook,
                "群信息\n群 ID：cid_group\n绑定状态：未绑定\n启用状态：已停用", List.of());

        var enabled = sharedRow();
        when(dao.findSharedByConfigAndGroup(50L, "cid_group")).thenReturn(enabled);
        service.handle(groupCommand(GROUP_INFO, webhook, expires),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));
        verify(sender).sendSessionWebhookText(webhook,
                "群信息\n群 ID：cid_group\n绑定状态：已绑定\n启用状态：已启用", List.of());

        var disabled = sharedRow();
        disabled.setStatus("DISABLED");
        when(dao.findSharedByConfigAndGroup(50L, "cid_group")).thenReturn(disabled);
        service.handle(groupCommand(GROUP_INFO, webhook, expires),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));
        verify(sender).sendSessionWebhookText(webhook,
                "群信息\n群 ID：cid_group\n绑定状态：已绑定\n启用状态：已停用", List.of());

        when(dao.findSharedByConfigAndGroup(50L, "cid_group")).thenReturn(enabled);
        service.handle(groupCommand("绑定群聊:any-code", webhook, expires),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));
        verify(sender).sendSessionWebhookText(webhook, CONFIRM_REMOVED, List.of());

        when(imConfigs.decryptGroupChatCredentials(config))
                .thenReturn(new ImGroupChatCredentials("ding-secret", null, null));
        service.handle(groupCommand("绑定群聊：any-code", null,
                        System.currentTimeMillis() - 1_000),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));
        verify(sender).sendGroupText(eq("ding-app"), eq("ding-secret"), isNull(),
                eq("ding-robot"), eq("cid_group"), eq(CONFIRM_REMOVED), eq(List.of()), anyLong());

        service.handle(groupCommand("确认 any-code", webhook, expires),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));
        verify(sender, times(2)).sendSessionWebhookText(webhook, CONFIRM_REMOVED, List.of());
        verifyNoInteractions(conversations);
    }

    @Test
    void sharedInboundIgnoresCallbacksFromAReplacedApplication() {
        var service = new DingTalkInboundService(dao, bindingService, conversations, reactions,
                null, imConfigs, mock(DingTalkOutboundSender.class), json);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config());
        when(dao.findSharedByConfigAndGroup(50L, "cid_group")).thenReturn(sharedRow());
        var msg = sharedMessage("hello", true, "cid_group", "ding-robot");

        // A connection opened for the previous application keeps delivering until reconcile stops
        // it; those events must not be routed as the new application.
        service.handle(msg, DingTalkStreamScope.shared(50L, "replaced-app", "ding-robot"));
        verify(conversations, never()).submitTurn(anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyString(), anyString());
        verifyNoInteractions(reactions);

        service.handle(msg, DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));
        verify(conversations).submitTurn(eq(1L), eq(7L), eq("DINGTALK"), eq("5:cid_group"),
                eq("hello"), eq("DINGTALK:5:m-1"), anyString());
    }

    @Test
    void groupCommandsAreIgnoredWithoutAManagementChannel() {
        var sender = mock(DingTalkOutboundSender.class);
        var service = new DingTalkInboundService(dao, bindingService, conversations, reactions,
                null, imConfigs, sender, json);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config());

        service.handle(groupCommand(GROUP_INFO, "https://hook",
                        System.currentTimeMillis() + 60_000),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        var admission = mock(ImChannelAdmissionService.class);
        when(admission.allowsManagement("DINGTALK")).thenReturn(false);
        var denied = new DingTalkInboundService(dao, bindingService, conversations, reactions,
                admission, imConfigs, sender, json);
        denied.handle(groupCommand(GROUP_INFO, "https://hook",
                        System.currentTimeMillis() + 60_000),
                DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        verifyNoInteractions(sender, bindingService);
    }

    @Test
    void repliesAreSkippedWithoutUsableWebhookOrCredentials() {
        var admission = mock(ImChannelAdmissionService.class);
        when(admission.allowsManagement("DINGTALK")).thenReturn(true);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config());
        var msg = groupCommand(GROUP_INFO, null, null);

        var withoutSender = new DingTalkInboundService(dao, bindingService, conversations,
                reactions, admission, imConfigs, null, json);
        withoutSender.handle(msg, DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        var noCredentialsSender = mock(DingTalkOutboundSender.class);
        var noCredentials = new DingTalkInboundService(dao, bindingService, conversations,
                reactions, admission, imConfigs, noCredentialsSender, json);
        noCredentials.handle(msg, DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        var blankSecretSender = mock(DingTalkOutboundSender.class);
        when(imConfigs.decryptGroupChatCredentials(any()))
                .thenReturn(new ImGroupChatCredentials("  ", null, null));
        new DingTalkInboundService(dao, bindingService, conversations, reactions, admission,
                imConfigs, blankSecretSender, json)
                .handle(msg, DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        var noRobotSender = mock(DingTalkOutboundSender.class);
        when(imConfigs.decryptGroupChatCredentials(any()))
                .thenReturn(new ImGroupChatCredentials("ding-secret", null, null));
        var noRobotConfig = config();
        noRobotConfig.setRobotCode("  ");
        when(imConfigs.findGroupChatById(50L)).thenReturn(noRobotConfig);
        new DingTalkInboundService(dao, bindingService, conversations, reactions, admission,
                imConfigs, noRobotSender, json)
                .handle(msg, DingTalkStreamScope.shared(50L, "ding-app", " "));

        var expiredSender = mock(DingTalkOutboundSender.class);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config());
        new DingTalkInboundService(dao, bindingService, conversations, reactions, admission,
                imConfigs, expiredSender, json)
                .handle(groupCommand(GROUP_INFO, "https://hook",
                        System.currentTimeMillis() - 1_000), DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));
        verify(expiredSender).sendGroupText(eq("ding-app"), eq("ding-secret"), isNull(),
                eq("ding-robot"), eq("cid_group"),
                eq("群信息\n群 ID：cid_group\n绑定状态：未绑定\n启用状态：已停用"),
                eq(List.of()), anyLong());

        var failingSender = mock(DingTalkOutboundSender.class);
        doThrow(new IllegalStateException("webhook down")).when(failingSender)
                .sendSessionWebhookText(anyString(), anyString(), any());
        new DingTalkInboundService(dao, bindingService, conversations, reactions, admission,
                imConfigs, failingSender, json)
                .handle(groupCommand(GROUP_INFO, "https://hook",
                        System.currentTimeMillis() + 60_000), DingTalkStreamScope.shared(50L, "ding-app", "ding-robot"));

        verifyNoInteractions(noCredentialsSender, blankSecretSender, noRobotSender,
                conversations, reactions, bindingService);
    }

    @Test
    void helpersAcceptBlankAndNullInputs() {
        assertNull(DingTalkInboundService.eventTime(
                message("x", "text", "2", true, atUsers(), null, null, null, "cid", "rc")));
        assertNull(DingTalkInboundService.eventTime(
                message("x", "text", "2", true, atUsers(), null, null, 0L, "cid", "rc")));
        assertEquals(Instant.ofEpochMilli(123L), DingTalkInboundService.eventTime(
                message("x", "text", "2", true, atUsers(), null, null, 123L, "cid", "rc")));
        assertNull(DingTalkInboundService.commandText(null));
        assertNull(DingTalkInboundService.commandText("  "));
        assertEquals("@", DingTalkInboundService.commandText("@ "));
        assertEquals("@bot", DingTalkInboundService.commandText("@bot"));
        assertEquals("群信息", DingTalkInboundService.commandText("@bot 群信息"));
    }

    private InboundBotMessage sharedMessage(String text, boolean atList, String conversationId,
            String robotCode) {
        return message(text, "text", "2", atList, atUsers(), null, null, 1000L, conversationId,
                robotCode);
    }

    private InboundBotMessage groupCommand(String text, String webhook, Long expires) {
        return message(text, "text", "2", true, atUsers(), webhook, expires, 1000L, "cid_group",
                "ding-robot");
    }

    private static List<InboundBotMessage.AtUser> atUsers() {
        return List.of(new InboundBotMessage.AtUser("dt-1", "staff-1"));
    }

    private static InboundBotMessage message(String text, String msgType, String conversationType,
            boolean atList, List<InboundBotMessage.AtUser> atUsers, String webhook,
            Long webhookExpiresAt, Long createAt, String conversationId, String robotCode) {
        return new InboundBotMessage(conversationId, "Requirements group", conversationType,
                "user1", "Wang Wu", "staff-1", "corp-1", atUsers, atList, "corp-1", "bot-user",
                robotCode, text, "m-1", msgType, webhook, webhookExpiresAt, createAt, "{}");
    }

    private static DingtalkRobotBindingDO legacyRow(String robotCode) {
        var row = new DingtalkRobotBindingDO();
        row.setId(5L);
        row.setTenantId(1L);
        row.setBindingMode("LEGACY");
        row.setRobotCode(robotCode);
        row.setAgentId(3L);
        row.setRouteVersion(1L);
        row.setStatus("ENABLED");
        return row;
    }

    private static DingtalkRobotBindingDO sharedRow() {
        var row = new DingtalkRobotBindingDO();
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
