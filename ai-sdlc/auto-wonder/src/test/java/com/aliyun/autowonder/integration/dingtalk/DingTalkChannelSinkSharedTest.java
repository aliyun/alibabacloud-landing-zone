package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.conversation.AgentConversationDO;
import com.aliyun.autowonder.conversation.AgentConversationTurnDO;
import com.aliyun.autowonder.conversation.AgentConversationTurnDao;
import com.aliyun.autowonder.conversation.ImDeliveryFailedException;
import com.aliyun.autowonder.conversation.ImDeliveryRetryableException;
import com.aliyun.autowonder.conversation.ImDeliveryTargetChangedException;
import com.aliyun.autowonder.conversation.ImDeliveryUnknownException;
import com.aliyun.autowonder.im.ImDeliveryFence;
import com.aliyun.autowonder.im.ImRouteSnapshot;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DingTalkChannelSinkSharedTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void deliversSharedRepliesToTheConfirmedGroupWithoutThinkingRecall() {
        var f = new Fixture();
        var binding = sharedBinding();
        when(f.dao.findById(1L, 7L)).thenReturn(binding);
        when(f.bindings.decryptSecret(binding)).thenReturn("sk");
        when(f.imConfigs.findGroupChatById(50L)).thenReturn(config(4));
        f.source(context(snapshot(7, 2, 4), null));

        f.sink().deliverReply(conversation("binding:7:cid_group"), "reply", "m-1");

        verify(f.sender).sendGroupText(eq("ak"), eq("sk"), eq("https://gw"), eq("rc"),
                eq("cid_group"), eq("@123456\n\nreply"), eq(List.of("123456")), anyLong());
        verify(f.sender, never()).sendSessionWebhookText(anyString(), anyString(), anyList());
        verify(f.reactions, never()).recallThinking(any(), anyString(), anyString());
        verify(f.bindings).markHealth(eq(1L), eq(7L), any(Date.class), isNull());
    }

    @Test
    void fencedDeliveryLocksTheBindingAndThePlatformConfigBeforeSending() {
        var f = new Fixture();
        var binding = sharedBinding();
        ImDeliveryFence fence = mock(ImDeliveryFence.class);
        when(fence.run(any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
        when(f.dao.findByIdForUpdate(1L, 7L)).thenReturn(binding);
        when(f.bindings.decryptSecret(binding)).thenReturn("sk");
        when(f.imConfigs.lockGroupChatForDelivery("DINGTALK")).thenReturn(config(4));
        f.source(context(snapshot(7, 2, 4), null));

        f.sink(fence).deliverReply(conversation("binding:7:cid_group"), "reply", "m-1");

        verify(f.dao).findByIdForUpdate(1L, 7L);
        verify(f.dao, never()).findById(1L, 7L);
        verify(f.imConfigs).lockGroupChatForDelivery("DINGTALK");
        verify(f.sender).sendGroupText(eq("ak"), eq("sk"), eq("https://gw"), eq("rc"),
                eq("cid_group"), eq("@123456\n\nreply"), eq(List.of("123456")), anyLong());
    }

    @Test
    void sharedRepliesPreferAFreshSessionWebhook() {
        var f = new Fixture();
        var binding = sharedBinding();
        when(f.dao.findById(1L, 7L)).thenReturn(binding);
        when(f.bindings.decryptSecret(binding)).thenReturn("sk");
        when(f.imConfigs.findGroupChatById(50L)).thenReturn(config(4));
        var ctx = context(snapshot(7, 2, 4), null);
        ctx.setSessionWebhook("https://oapi.dingtalk.com/robot/sendBySession?session=s1");
        ctx.setSessionWebhookExpiredTime(System.currentTimeMillis() + 60_000);
        f.source(ctx);

        f.sink().deliverReply(conversation("binding:7:cid_group"), "reply", "m-1");

        verify(f.sender).sendSessionWebhookText(eq(ctx.getSessionWebhook()),
                eq("@123456\n\nreply"), eq(List.of("123456")));
        verify(f.sender, never()).sendGroupText(anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyList(), anyLong());
        verify(f.reactions, never()).recallThinking(any(), anyString(), anyString());
        verify(f.bindings).markHealth(eq(1L), eq(7L), any(Date.class), isNull());
    }

    @Test
    void sharedRepliesFallBackToTheConfirmedGroupWhenTheWebhookFails() {
        var f = new Fixture();
        var binding = sharedBinding();
        when(f.dao.findById(1L, 7L)).thenReturn(binding);
        when(f.bindings.decryptSecret(binding)).thenReturn("sk");
        when(f.imConfigs.findGroupChatById(50L)).thenReturn(config(4));
        var ctx = context(snapshot(7, 2, 4), null);
        ctx.setSessionWebhook("https://oapi.dingtalk.com/robot/sendBySession?session=s1");
        ctx.setSessionWebhookExpiredTime(System.currentTimeMillis() + 60_000);
        f.source(ctx);
        doThrow(new RuntimeException("webhook failed")).when(f.sender)
                .sendSessionWebhookText(eq(ctx.getSessionWebhook()), anyString(), anyList());

        f.sink().deliverReply(conversation("binding:7:cid_group"), "reply", "m-1");

        verify(f.sender).sendGroupText(eq("ak"), eq("sk"), eq("https://gw"), eq("rc"),
                eq("cid_group"), eq("@123456\n\nreply"), eq(List.of("123456")), anyLong());
        verify(f.reactions, never()).recallThinking(any(), anyString(), anyString());
        verify(f.bindings).markHealth(eq(1L), eq(7L), any(Date.class), isNull());
    }

    @Test
    void ambiguousSharedWebhookFailuresAreNotResentProactively() {
        for (RuntimeException failure : List.of(
                new DingTalkHttpException(429, "{}"),
                new DingTalkHttpException(408, "{}"),
                new DingTalkHttpException(503, "{}"),
                new RuntimeException(new IOException("lost response")))) {
            var f = new Fixture();
            var binding = sharedBinding();
            when(f.dao.findById(1L, 7L)).thenReturn(binding);
            when(f.bindings.decryptSecret(binding)).thenReturn("sk");
            when(f.imConfigs.findGroupChatById(50L)).thenReturn(config(4));
            var ctx = context(snapshot(7, 2, 4), null);
            ctx.setSessionWebhook("https://oapi.dingtalk.com/robot/sendBySession?session=s1");
            ctx.setSessionWebhookExpiredTime(System.currentTimeMillis() + 60_000);
            f.source(ctx);
            doThrow(failure).when(f.sender)
                    .sendSessionWebhookText(eq(ctx.getSessionWebhook()), anyString(), anyList());

            assertThrows(ImDeliveryUnknownException.class,
                    () -> f.sink().deliverReply(conversation("binding:7:cid_group"), "reply", "m-1"),
                    failure.toString());

            verify(f.sender, never()).sendGroupText(anyString(), anyString(), anyString(),
                    anyString(), anyString(), anyString(), anyList(), anyLong());
            verify(f.bindings).markHealth(eq(1L), eq(7L), isNull(), anyString());
        }
    }

    @Test
    void failsClosedWhenASharedBindingHasNoRouteSnapshot() {
        var f = new Fixture();
        var binding = sharedBinding();
        when(f.dao.listByTenant(1L)).thenReturn(List.of(binding));
        f.source(context(null, null));

        var failure = assertThrows(ImDeliveryTargetChangedException.class,
                () -> f.sink().deliverReply(conversation("binding:7:cid_group"), "reply", "m-1"));

        assertEquals("共享群聊绑定缺少路由快照，未投递", failure.getMessage());
        verifyNoInteractions(f.sender);
    }

    @Test
    void rejectsSharedRoutesWhoseBindingOrConfigChanged() {
        var f = new Fixture();
        var binding = sharedBinding();
        var conv = conversation("binding:7:cid_group");
        when(f.bindings.decryptSecret(binding)).thenReturn("sk");
        when(f.dao.findById(1L, 7L)).thenReturn(binding);
        when(f.imConfigs.findGroupChatById(50L)).thenReturn(config(4));

        when(f.dao.findById(1L, 99L)).thenReturn(null);
        expectRejected(f, conv, context(snapshot(99, 2, 4), null),
                "no DingTalk binding for agent 3");
        when(f.dao.findById(1L, 7L)).thenReturn(binding);

        var foreign = sharedBinding();
        foreign.setId(9L);
        when(f.dao.findById(1L, 7L)).thenReturn(foreign);
        expectRejected(f, conv, context(snapshot(7, 2, 4), null), "钉钉绑定已变更");
        when(f.dao.findById(1L, 7L)).thenReturn(binding);

        expectRejected(f, conv, context(new ImRouteSnapshot("DINGTALK", 7L, "SHARED", 50L,
                2L, 4L, 2L, 3L, "cid_group", 0L, System.currentTimeMillis()), null),
                "钉钉绑定空间已变更");
        expectRejected(f, conv, context(new ImRouteSnapshot("DINGTALK", 7L, "SHARED", 50L,
                2L, 4L, 1L, 9L, "cid_group", 0L, System.currentTimeMillis()), null),
                "钉钉绑定数字人已变更");

        binding.setStatus("DISABLED");
        expectRejected(f, conv, context(snapshot(7, 2, 4), null), "钉钉绑定已停用");
        binding.setStatus("ENABLED");

        expectRejected(f, conv, context(snapshot(7, 1, 4), null), "钉钉绑定路由已变更");

        binding.setBindingMode("LEGACY");
        expectRejected(f, conv, context(snapshot(7, 2, 4), null), "钉钉绑定模式已变更");
        binding.setBindingMode("SHARED");

        expectRejected(f, conv, context(new ImRouteSnapshot("DINGTALK", 7L, "SHARED", 51L,
                2L, 4L, 1L, 3L, "cid_group", 0L, System.currentTimeMillis()), null),
                "共享群聊配置已变更");
        expectRejected(f, conv, context(new ImRouteSnapshot("DINGTALK", 7L, "SHARED", 50L,
                2L, 4L, 1L, 3L, "cid_other", 0L, System.currentTimeMillis()), null),
                "共享群聊目标群已变更");

        when(f.imConfigs.findGroupChatById(50L)).thenReturn(null);
        expectRejected(f, conv, context(snapshot(7, 2, 4), null), "共享群聊机器人配置已删除");

        var wrongProvider = config(4);
        wrongProvider.setProvider("FEISHU");
        when(f.imConfigs.findGroupChatById(50L)).thenReturn(wrongProvider);
        expectRejected(f, conv, context(snapshot(7, 2, 4), null), "共享群聊渠道已变更");

        var disabled = config(4);
        disabled.setEnabled(0);
        when(f.imConfigs.findGroupChatById(50L)).thenReturn(disabled);
        expectRejected(f, conv, context(snapshot(7, 2, 4), null), "共享群聊机器人已停用");

        when(f.imConfigs.findGroupChatById(50L)).thenReturn(config(3));
        expectRejected(f, conv, context(snapshot(7, 2, 4), null), "共享群聊机器人路由已变更");
        when(f.imConfigs.findGroupChatById(50L)).thenReturn(config(4));

        expectRejected(f, conv, context(ImRouteSnapshot.legacy("DINGTALK", 7L, 2, 1L, 3L, 0,
                System.currentTimeMillis()), null), "钉钉绑定模式已变更");

        binding.setBindingMode("OTHER");
        expectRejected(f, conv, context(ImRouteSnapshot.legacy("DINGTALK", 7L, 2, 1L, 3L, 0,
                System.currentTimeMillis()), null), "钉钉绑定模式无效");

        binding.setBindingMode("LEGACY");
        expectRejected(f, conv, context(ImRouteSnapshot.legacy("DINGTALK", 7L, 2, 1L, 3L, 0,
                System.currentTimeMillis()), "rc-other"), "钉钉机器人已变更");

        var malformed = context(snapshot(7, 2, 4), null);
        malformed.setRouteSnapshot("{invalid");
        expectRejected(f, conv, malformed, "钉钉路由快照无效，未投递");

        verifyNoInteractions(f.sender);
        verify(f.bindings, never()).markHealth(any(), any(), any(), any());
    }

    @Test
    void classifiesSharedTransportFailuresForTheDeliveryQueue() {
        var f = new Fixture();
        var binding = sharedBinding();
        var conv = conversation("binding:7:cid_group");
        var ctx = context(snapshot(7, 2, 4), null);
        when(f.dao.findById(1L, 7L)).thenReturn(binding);
        when(f.bindings.decryptSecret(binding)).thenReturn("sk");
        when(f.imConfigs.findGroupChatById(50L)).thenReturn(config(4));
        f.source(ctx);

        assertClassified(f, conv, ctx, new DingTalkHttpException(429, "{}"),
                ImDeliveryRetryableException.class);
        assertClassified(f, conv, ctx, new DingTalkHttpException(400, "{}"),
                ImDeliveryFailedException.class);
        assertClassified(f, conv, ctx, new DingTalkHttpException(408, "{}"),
                ImDeliveryUnknownException.class);
        assertClassified(f, conv, ctx, new DingTalkHttpException(500, "{}"),
                ImDeliveryUnknownException.class);
        assertClassified(f, conv, ctx, new RuntimeException(new IOException("lost response")),
                ImDeliveryUnknownException.class);
        assertClassified(f, conv, ctx, new IllegalStateException("共享群聊机器人配置已变更"),
                ImDeliveryTargetChangedException.class);
        assertClassified(f, conv, ctx, new IllegalStateException("共享群聊机器人凭据不可用"),
                ImDeliveryFailedException.class);
        assertClassified(f, conv, ctx, new IllegalStateException("access token response is incomplete"),
                ImDeliveryFailedException.class);
        assertClassified(f, conv, ctx, new RuntimeException("boom"), ImDeliveryUnknownException.class);
        assertClassified(f, conv, ctx, new ImDeliveryRetryableException("already classified"),
                ImDeliveryRetryableException.class);

        verify(f.bindings, org.mockito.Mockito.atLeastOnce())
                .markHealth(eq(1L), eq(7L), isNull(), anyString());
    }

    private static void expectRejected(Fixture f, AgentConversationDO conv,
            DingTalkSourceContext ctx, String message) {
        f.source(ctx);
        var failure = assertThrows(ImDeliveryTargetChangedException.class,
                () -> f.sink().deliverReply(conv, "reply", "m-1"));
        assertEquals(message, failure.getMessage());
    }

    private static void assertClassified(Fixture f, AgentConversationDO conv,
            DingTalkSourceContext ctx, RuntimeException failure,
            Class<? extends RuntimeException> type) {
        doThrow(failure).when(f.sender).sendGroupText(anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString(), anyList(), anyLong());
        var thrown = assertThrows(type,
                () -> f.sink().deliverReply(conv, "reply", "m-1"));
        assertEquals(type, thrown.getClass());
    }

    @Test
    void sharedSendUsesTheCurrentConfigurationIdentityAfterAnAppReplacement() {
        var f = new Fixture();
        var binding = sharedBinding();
        var config = config(4);
        config.setAppKey("ak-new");
        config.setRobotCode("rc-new");
        config.setBaseUrl("https://gw-new");
        when(f.dao.findById(1L, 7L)).thenReturn(binding);
        when(f.bindings.decryptSecret(binding)).thenReturn("sk-new");
        when(f.imConfigs.findGroupChatById(50L)).thenReturn(config);
        f.source(context(snapshot(7, 2, 4), null));

        f.sink().deliverReply(conversation("binding:7:cid_group"), "reply", "m-1");

        // The binding row keeps the creation-time snapshot: sending must follow the new app.
        verify(f.sender).sendGroupText(eq("ak-new"), eq("sk-new"), eq("https://gw-new"),
                eq("rc-new"), eq("cid_group"), eq("@123456\n\nreply"),
                eq(List.of("123456")), anyLong());
    }

    @Test
    void sharedSendUsesTheNewRobotCodeWhenOnlyTheRobotChanged() {
        var f = new Fixture();
        var binding = sharedBinding();
        var config = config(4);
        config.setRobotCode("rc-only-new");
        config.setBaseUrl(null);
        when(f.dao.findById(1L, 7L)).thenReturn(binding);
        when(f.bindings.decryptSecret(binding)).thenReturn("sk");
        when(f.imConfigs.findGroupChatById(50L)).thenReturn(config);
        f.source(context(snapshot(7, 2, 4), null));

        f.sink().deliverReply(conversation("binding:7:cid_group"), "reply", "m-1");

        // The configuration has no gateway of its own, so the binding's base url is still used.
        verify(f.sender).sendGroupText(eq("ak"), eq("sk"), eq("https://gw"), eq("rc-only-new"),
                eq("cid_group"), eq("@123456\n\nreply"), eq(List.of("123456")), anyLong());
    }

    @Test
    void failureHealthIsWrittenOutsideTheFenceTransaction() {
        var f = new Fixture();
        var binding = sharedBinding();
        when(f.dao.findByIdForUpdate(1L, 7L)).thenReturn(binding);
        when(f.bindings.decryptSecret(binding)).thenReturn("sk");
        when(f.imConfigs.lockGroupChatForDelivery("DINGTALK")).thenReturn(config(4));
        f.source(context(snapshot(7, 2, 4), null));
        AtomicBoolean insideFence = new AtomicBoolean();
        ImDeliveryFence fence = mock(ImDeliveryFence.class);
        when(fence.run(any())).thenAnswer(invocation -> {
            insideFence.set(true);
            try {
                return ((Supplier<?>) invocation.getArgument(0)).get();
            } finally {
                insideFence.set(false);
            }
        });
        doThrow(new DingTalkHttpException(400, "{}")).when(f.sender).sendGroupText(anyString(),
                anyString(), anyString(), anyString(), anyString(), anyString(), anyList(),
                anyLong());
        AtomicBoolean healthWrittenInsideFence = new AtomicBoolean(true);
        doAnswer(invocation -> {
            healthWrittenInsideFence.set(insideFence.get());
            return null;
        }).when(f.bindings).markHealth(eq(1L), eq(7L), isNull(), anyString());

        var failure = assertThrows(ImDeliveryFailedException.class,
                () -> f.sink(fence).deliverReply(conversation("binding:7:cid_group"), "reply",
                        "m-1"));

        assertEquals(ImDeliveryFailedException.class, failure.getClass());
        // A failed send rolls the fence transaction back, so the diagnostic write must not be
        // inside it.
        assertFalse(healthWrittenInsideFence.get());
    }

    @Test
    void healthWriteFailureDoesNotFailADeliveredReply() {
        var f = new Fixture();
        var binding = sharedBinding();
        when(f.dao.findById(1L, 7L)).thenReturn(binding);
        when(f.bindings.decryptSecret(binding)).thenReturn("sk");
        when(f.imConfigs.findGroupChatById(50L)).thenReturn(config(4));
        doThrow(new IllegalStateException("health store down")).when(f.bindings)
                .markHealth(anyLong(), anyLong(), any(), any());
        f.source(context(snapshot(7, 2, 4), null));

        f.sink().deliverReply(conversation("binding:7:cid_group"), "reply", "m-1");

        verify(f.sender).sendGroupText(eq("ak"), eq("sk"), eq("https://gw"), eq("rc"),
                eq("cid_group"), eq("@123456\n\nreply"), eq(List.of("123456")), anyLong());
    }

    private static final class Fixture {
        final DingtalkRobotBindingDao dao = mock(DingtalkRobotBindingDao.class);
        final DingTalkBindingService bindings = mock(DingTalkBindingService.class);
        final DingTalkOutboundSender sender = mock(DingTalkOutboundSender.class);
        final DingTalkMessageReactionService reactions = mock(DingTalkMessageReactionService.class);
        final AgentConversationTurnDao turns = mock(AgentConversationTurnDao.class);
        final PlatformImChannelConfigService imConfigs =
                mock(PlatformImChannelConfigService.class);

        DingTalkChannelSink sink() {
            return new DingTalkChannelSink(dao, bindings, sender, reactions, turns, JSON, imConfigs);
        }

        DingTalkChannelSink sink(ImDeliveryFence fence) {
            return new DingTalkChannelSink(dao, bindings, sender, reactions, turns, JSON, imConfigs,
                    fence);
        }

        void source(DingTalkSourceContext ctx) {
            var turn = new AgentConversationTurnDO();
            turn.setSourceContext(ctx.toJson());
            when(turns.findByExternalMsgId(1L, "m-1")).thenReturn(turn);
        }
    }

    private static AgentConversationDO conversation(String channelConversationId) {
        var conv = new AgentConversationDO();
        conv.setId(11L);
        conv.setTenantId(1L);
        conv.setAgentId(3L);
        conv.setChannelConversationId(channelConversationId);
        return conv;
    }

    private static DingTalkSourceContext context(ImRouteSnapshot route, String robotCode) {
        var ctx = new DingTalkSourceContext();
        ctx.setConversationType("2");
        ctx.setSenderStaffId("123456");
        ctx.setRobotCode(robotCode);
        if (route != null) {
            ctx.setRouteSnapshot(route.toJson(JSON));
        }
        return ctx;
    }

    private static DingtalkRobotBindingDO sharedBinding() {
        var binding = new DingtalkRobotBindingDO();
        binding.setId(7L);
        binding.setTenantId(1L);
        binding.setAgentId(3L);
        binding.setStatus("ENABLED");
        binding.setAppKey("ak");
        binding.setRobotCode("rc");
        binding.setBaseUrl("https://gw");
        binding.setBindingMode("SHARED");
        binding.setPlatformConfigId(50L);
        binding.setGroupId("cid_group");
        binding.setRouteVersion(2L);
        return binding;
    }

    private static ImRouteSnapshot snapshot(long bindingId, long bindingRoute, long configRoute) {
        return new ImRouteSnapshot("DINGTALK", bindingId, "SHARED", 50L, bindingRoute,
                configRoute, 1L, 3L, "cid_group", 0L, System.currentTimeMillis());
    }

    private static PlatformImChannelConfigDO config(long routeVersion) {
        var config = new PlatformImChannelConfigDO();
        config.setId(50L);
        config.setProvider("DINGTALK");
        config.setPurpose("GROUP_CHAT");
        config.setAppKey("ak");
        config.setRobotCode("rc");
        config.setBaseUrl("https://gw");
        config.setEnabled(1);
        config.setRouteVersion(routeVersion);
        return config;
    }
}
