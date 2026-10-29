package com.aliyun.autowonder.integration.feishu;

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
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FeishuChannelSinkSharedTest {

    @Test
    void deliversSharedRepliesThroughThePlatformConfigAndClearsHealth() throws Exception {
        var f = new FeishuTestSupport();
        var binding = sharedBinding(f);
        var config = config();
        var imConfigs = mock(PlatformImChannelConfigService.class);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config);
        var api = mock(FeishuApiClient.class);
        var source = source(binding, snapshot(binding.getId(), 2, 4));
        var sink = new FeishuChannelSink(turns(f, source), f.dao, api, f.json, imConfigs);

        sink.deliverReply(conversation(source), "hello", source.externalId());

        verify(api).replyForConfig(eq(config), eq("om_message"), eq("hello"), eq(source.externalId()));
        verify(api, never()).reply(any(), anyString(), anyString(), anyString());
        assertNotNull(f.dao.find(1L, binding.getId()).getLastSuccessAt());
        assertNull(f.dao.find(1L, binding.getId()).getLastError());
    }

    @Test
    void fencedDeliveryLocksTheBindingAndThePlatformConfigBeforeSending() throws Exception {
        var f = new FeishuTestSupport();
        var binding = sharedBinding(f);
        var config = config();
        var imConfigs = mock(PlatformImChannelConfigService.class);
        when(imConfigs.lockGroupChatForDelivery("FEISHU")).thenReturn(config);
        var api = mock(FeishuApiClient.class);
        var source = source(binding, snapshot(binding.getId(), 2, 4));
        ImDeliveryFence fence = mock(ImDeliveryFence.class);
        when(fence.run(any())).thenAnswer(invocation ->
                ((java.util.function.Supplier<?>) invocation.getArgument(0)).get());
        var sink = new FeishuChannelSink(turns(f, source), f.dao, api, f.json, imConfigs, fence);

        sink.deliverReply(conversation(source), "hello", source.externalId());

        verify(imConfigs).lockGroupChatForDelivery("FEISHU");
        verify(imConfigs, never()).findGroupChatById(any());
        verify(api).replyForConfig(eq(config), eq("om_message"), eq("hello"), eq(source.externalId()));
    }

    @Test
    void rejectsSharedRoutesWhoseBindingOrConfigChanged() throws Exception {
        var f = new FeishuTestSupport();
        var binding = sharedBinding(f);
        var imConfigs = mock(PlatformImChannelConfigService.class);
        var api = mock(FeishuApiClient.class);
        var turns = turns(f, source(binding, snapshot(binding.getId(), 2, 4)));
        var sink = new FeishuChannelSink(turns, f.dao, api, f.json, imConfigs);

        var source = source(binding, snapshot(binding.getId(), 2, 4));
        var conv = conversation(source);

        when(imConfigs.findGroupChatById(50L)).thenReturn(null);
        assertTargetChanged(sink, conv, source, "共享群聊飞书机器人配置已删除");

        var wrongProvider = config();
        wrongProvider.setProvider("DINGTALK");
        when(imConfigs.findGroupChatById(50L)).thenReturn(wrongProvider);
        assertTargetChanged(sink, conv, source, "共享群聊渠道已变更");

        var disabled = config();
        disabled.setEnabled(0);
        when(imConfigs.findGroupChatById(50L)).thenReturn(disabled);
        assertTargetChanged(sink, conv, source, "共享群聊飞书机器人已停用");

        when(imConfigs.findGroupChatById(50L)).thenReturn(config(3));
        assertTargetChanged(sink, conv, source, "共享群聊飞书机器人路由已变更");
        verify(api, never()).replyForConfig(any(), anyString(), anyString(), anyString());

        var changedGroup = source(binding, new ImRouteSnapshot("FEISHU", binding.getId(), "SHARED",
                50L, 2L, 4L, 1L, 7L, "oc_other", 0L, System.currentTimeMillis()));
        when(imConfigs.findGroupChatById(50L)).thenReturn(config());
        restub(turns, f, changedGroup);
        assertTargetChanged(sink, conversation(changedGroup), changedGroup, "共享群聊目标群已变更");

        var changedConfig = source(binding, new ImRouteSnapshot("FEISHU", binding.getId(), "SHARED",
                51L, 2L, 4L, 1L, 7L, "oc_chat", 0L, System.currentTimeMillis()));
        restub(turns, f, changedConfig);
        assertTargetChanged(sink, conversation(changedConfig), changedConfig, "共享群聊配置已变更");

        var legacySnapshot = source(binding, ImRouteSnapshot.legacy("FEISHU", binding.getId(), 2, 1L, 7L, 0, 0));
        restub(turns, f, legacySnapshot);
        assertTargetChanged(sink, conversation(legacySnapshot), legacySnapshot, "飞书绑定模式已变更");

    }

    @Test
    void failsClosedWhenASharedBindingHasNoRouteSnapshot() throws Exception {
        var f = new FeishuTestSupport();
        var binding = sharedBinding(f);
        var api = mock(FeishuApiClient.class);
        var source = source(binding, null);
        var sink = new FeishuChannelSink(turns(f, source), f.dao, api, f.json,
                mock(PlatformImChannelConfigService.class));

        var failure = assertThrows(ImDeliveryTargetChangedException.class,
                () -> sink.deliverReply(conversation(source), "hello", source.externalId()));
        assertEquals("共享群聊绑定缺少路由快照，未投递", failure.getMessage());
    }

    @Test
    void upgradeEraLegacyRowsStillRejectForeignAppsAndDeliverTheirOwn() throws Exception {
        var f = new FeishuTestSupport();
        var binding = f.create();
        var api = mock(FeishuApiClient.class);
        var foreign = new FeishuSourceContext(binding.getId(), "cli_other", "oc_chat", "om_message",
                "ou_user", "", null);
        var sink = new FeishuChannelSink(turns(f, foreign), f.dao, api, f.json,
                mock(PlatformImChannelConfigService.class));
        var failure = assertThrows(ImDeliveryTargetChangedException.class,
                () -> sink.deliverReply(conversation(foreign), "hello", foreign.externalId()));
        assertEquals("飞书应用已变更", failure.getMessage());

        var own = new FeishuSourceContext(binding.getId(), "cli_test", "oc_chat", "om_message",
                "ou_user", "", null);
        var ownSink = new FeishuChannelSink(turns(f, own), f.dao, api, f.json,
                mock(PlatformImChannelConfigService.class));
        ownSink.deliverReply(conversation(own), "hello", own.externalId());
        verify(api).reply(eq(binding), eq("om_message"), eq("hello"), eq(own.externalId()));
    }

    @Test
    void classifiesFeishuApiFailuresForTheDeliveryQueue() throws Exception {
        var f = new FeishuTestSupport();
        var binding = sharedBinding(f);
        var imConfigs = mock(PlatformImChannelConfigService.class);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config());
        var api = mock(FeishuApiClient.class);
        var source = source(binding, snapshot(binding.getId(), 2, 4));
        var sink = new FeishuChannelSink(turns(f, source), f.dao, api, f.json, imConfigs);

        assertClassified(sink, api, source, new FeishuApiException("network", null, null, true),
                ImDeliveryUnknownException.class);
        assertClassified(sink, api, source, new FeishuApiException("throttled", 429, null, false),
                ImDeliveryRetryableException.class);
        assertClassified(sink, api, source, new FeishuApiException("timeout", 408, null, false),
                ImDeliveryUnknownException.class);
        assertClassified(sink, api, source, new FeishuApiException("unavailable", 503, null, false),
                ImDeliveryUnknownException.class);
        assertClassified(sink, api, source, new FeishuApiException("rejected", 400, null, false),
                ImDeliveryFailedException.class);
        assertClassified(sink, api, source, new FeishuApiException("auth", 200, 10003, false),
                ImDeliveryFailedException.class);
        assertClassified(sink, api, source, new FeishuApiException("business", 200, 230001, false),
                ImDeliveryRetryableException.class);
        assertClassified(sink, api, source, new FeishuApiException("unknown", null, null, false),
                ImDeliveryUnknownException.class);
        assertClassified(sink, api, source, new IllegalArgumentException("bad payload"),
                ImDeliveryFailedException.class);
        assertClassified(sink, api, source, new IllegalStateException("共享群聊飞书机器人配置已变更"),
                ImDeliveryTargetChangedException.class);
        assertClassified(sink, api, source, new IllegalStateException("无法读取飞书凭据"),
                ImDeliveryFailedException.class);
        assertClassified(sink, api, source, new RuntimeException("boom"), ImDeliveryUnknownException.class);

        assertEquals("飞书回复失败，请检查应用权限、网络及原消息是否仍可回复",
                f.dao.find(1L, binding.getId()).getLastError());
    }

    @Test
    void failureHealthIsWrittenOutsideTheFenceTransaction() throws Exception {
        var f = new FeishuTestSupport();
        var binding = sharedBinding(f);
        var source = source(binding, snapshot(binding.getId(), 2, 4));
        var dao = mock(FeishuBindingDao.class);
        when(dao.findForUpdate(1L, binding.getId())).thenReturn(binding);
        var imConfigs = mock(PlatformImChannelConfigService.class);
        when(imConfigs.lockGroupChatForDelivery("FEISHU")).thenReturn(config());
        var api = mock(FeishuApiClient.class);
        doThrow(new RuntimeException("boom")).when(api)
                .replyForConfig(any(), anyString(), anyString(), anyString());
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
        AtomicBoolean healthWrittenInsideFence = new AtomicBoolean(true);
        doAnswer(invocation -> {
            healthWrittenInsideFence.set(insideFence.get());
            return null;
        }).when(dao).health(any(), anyString());
        var sink = new FeishuChannelSink(turns(f, source), dao, api, f.json, imConfigs, fence);

        assertThrows(ImDeliveryUnknownException.class,
                () -> sink.deliverReply(conversation(source), "hello", source.externalId()));

        assertFalse(healthWrittenInsideFence.get(),
                "the failure diagnostic must survive the fence rollback");
    }

    @Test
    void healthWriteFailureDoesNotFailADeliveredReply() throws Exception {
        var f = new FeishuTestSupport();
        var binding = sharedBinding(f);
        var source = source(binding, snapshot(binding.getId(), 2, 4));
        var dao = mock(FeishuBindingDao.class);
        when(dao.find(1L, binding.getId())).thenReturn(binding);
        doThrow(new IllegalStateException("health store down")).when(dao).health(any(), any());
        var api = mock(FeishuApiClient.class);
        var imConfigs = mock(PlatformImChannelConfigService.class);
        when(imConfigs.findGroupChatById(50L)).thenReturn(config());
        var sink = new FeishuChannelSink(turns(f, source), dao, api, f.json, imConfigs);

        sink.deliverReply(conversation(source), "hello", source.externalId());

        verify(api).replyForConfig(any(), eq("om_message"), eq("hello"), eq(source.externalId()));
    }

    private static void assertClassified(FeishuChannelSink sink, FeishuApiClient api,
            FeishuSourceContext source, RuntimeException failure, Class<? extends RuntimeException> type) {
        doThrow(failure).when(api).replyForConfig(any(), anyString(), anyString(), anyString());
        var thrown = assertThrows(type, () -> sink.deliverReply(conversation(source), "hello", source.externalId()));
        assertEquals(type, thrown.getClass());
    }

    private static void assertTargetChanged(FeishuChannelSink sink, AgentConversationDO conv,
            FeishuSourceContext source, String message) {
        var failure = assertThrows(ImDeliveryTargetChangedException.class,
                () -> sink.deliverReply(conv, "hello", source.externalId()));
        assertEquals(message, failure.getMessage());
    }

    private static AgentConversationTurnDao turns(FeishuTestSupport f, FeishuSourceContext source)
            throws Exception {
        var turns = mock(AgentConversationTurnDao.class);
        restub(turns, f, source);
        return turns;
    }

    private static void restub(AgentConversationTurnDao turns, FeishuTestSupport f,
            FeishuSourceContext source) throws Exception {
        var turn = new AgentConversationTurnDO();
        turn.setConversationId(10L);
        turn.setSourceContext(f.json.writeValueAsString(source));
        when(turns.findByExternalMsgId(1L, source.externalId())).thenReturn(turn);
    }

    private static FeishuSourceContext source(FeishuBinding binding, ImRouteSnapshot route) {
        return new FeishuSourceContext(binding.getId(), binding.getAppId(), "oc_chat", "om_message",
                "ou_user", "", route);
    }

    private static AgentConversationDO conversation(FeishuSourceContext source) {
        var conv = new AgentConversationDO();
        conv.setId(10L);
        conv.setTenantId(1L);
        conv.setAgentId(7L);
        conv.setChannelConversationId(source.conversationId());
        return conv;
    }

    private static ImRouteSnapshot snapshot(long bindingId, long bindingRoute, long configRoute) {
        return new ImRouteSnapshot("FEISHU", bindingId, "SHARED", 50L, bindingRoute, configRoute,
                1L, 7L, "oc_chat", 0L, System.currentTimeMillis());
    }

    private static FeishuBinding sharedBinding(FeishuTestSupport f) {
        f.jdbc.update("INSERT INTO feishu_robot_binding (tenant_id,binding_mode,platform_config_id,"
                        + "group_id,app_id,credential_ref,agent_id,route_version,status,"
                        + "creator_id,modifier_id) VALUES (1,'SHARED',50,'oc_chat','cli_shared',NULL,7,2,"
                        + "'ENABLED',9,9)");
        Long id = f.jdbc.queryForObject("SELECT id FROM feishu_robot_binding WHERE group_id='oc_chat'",
                Long.class);
        return f.dao.find(1L, id);
    }

    private static PlatformImChannelConfigDO config() {
        return config(4);
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
