package com.aliyun.autowonder.im.notification;

import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.branding.dto.PlatformBrandingVO;
import com.aliyun.autowonder.im.*;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.user.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.i18n.LocaleContextHolder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImNotificationRecipientLocaleTest {
    @Test
    void productionQueueCapturesSavedPreferenceOnceBeforeXaddRegardlessOfAmbientLocale() throws Exception {
        try (var context = context()) {
            var redis = context.getBean(RedisManager.class);
            var settings = context.getBean(UserSettingDao.class);
            when(settings.findLive(9L, "ui.locale")).thenReturn(setting("\"en-US\""));
            LocaleContextHolder.setLocale(Locale.CHINA);
            var original = new ImNotificationTask("1:FEISHU:9", 1, 7, 42, 9, "AGENT", 3, "数字人", "rid", "<b>raw</b>");
            context.getBean(RedisImNotificationQueue.class).enqueue(original);
            var fields = ArgumentCaptor.forClass(Map.class);
            verify(redis).xadd(eq("autowonder:im-notification:stream"), fields.capture(), eq(10000L));
            var tree = new ObjectMapper().readTree((String) fields.getValue().get("payload"));
            assertEquals("1:FEISHU:9", tree.path("notificationKey").asText());
            assertEquals("数字人", tree.path("actorDisplayName").asText());
            assertEquals("<b>raw</b>", tree.path("workitemTitle").asText());
            assertEquals("en-US", tree.path("recipientLocale").asText());
            verify(settings, times(1)).findLive(9L, "ui.locale");
            assertFalse(new ObjectMapper().valueToTree(original).has("recipientLocale"));
        } finally { LocaleContextHolder.resetLocaleContext(); }
    }

    @Test
    void actualDirectTestSuccessUsesRecipientPreferenceAndRawBrandForBothChannels() {
        for (String channel : List.of("DINGTALK", "FEISHU")) {
            try (var context = context()) {
                var identity = new UserImIdentityDO(); identity.setExternalUserId("fake-user");
                when(context.getBean(UserImIdentityDao.class).find(9L, channel)).thenReturn(identity);
                when(context.getBean(PlatformImChannelConfigService.class).isReady(channel)).thenReturn(true);
                var provider = context.getBean(ImProvider.class);
                when(context.getBean(ImProviderRegistry.class).require(channel)).thenReturn(provider);
                when(context.getBean(UserSettingDao.class).findLive(9L, "ui.locale")).thenReturn(setting("\"en-US\""));
                LocaleContextHolder.setLocale(Locale.CHINA);
                context.getBean(UserImIdentityService.class).sendTest(9L, channel);
                var command = ArgumentCaptor.forClass(ImSendCommand.class);
                verify(provider).send(command.capture());
                assertEquals(channel, command.getValue().provider());
                assertEquals("fake-user", command.getValue().externalUserId());
                assertEquals("<b>{brand}'s</b> collaboration notification test succeeded", command.getValue().title());
                assertEquals("## <b>{brand}'s</b> collaboration notification test succeeded\n\nYour IM collaboration notifications are configured correctly.", command.getValue().markdown());
                verify(context.getBean(UserSettingDao.class)).findLive(9L, "ui.locale");
            } finally { LocaleContextHolder.resetLocaleContext(); }
        }
    }

    @Test
    void explicitSnapshotIsPreservedOrDefaultsToChineseWithoutReadingPreferences() throws Exception {
        for (String tag : List.of("en-US", "zh-CN", "fr-FR", "")) {
            try (var c = context()) {
                var task = new ObjectMapper().readValue("{\"recipientUserId\":9,\"recipientLocale\":\"" + tag + "\"}", ImNotificationTask.class);
                c.getBean(RedisImNotificationQueue.class).enqueue(task);
                var fields = ArgumentCaptor.forClass(Map.class);
                verify(c.getBean(RedisManager.class)).xadd(eq("autowonder:im-notification:stream"), fields.capture(), eq(10000L));
                assertEquals("en-US".equals(tag) ? "en-US" : "zh-CN", new ObjectMapper()
                        .readTree((String) fields.getValue().get("payload")).path("recipientLocale").asText());
                verifyNoInteractions(c.getBean(UserSettingDao.class));
            }
        }
    }

    @Test
    void queuedEnglishSurvivesPreferenceChangesReadClaimRetryAndDlqForBothFakeChannels() throws Exception {
        for (String channel : List.of("DINGTALK", "FEISHU")) {
            try (var c = context()) {
                var redis = c.getBean(RedisManager.class); var settings = c.getBean(UserSettingDao.class);
                var queue = c.getBean(RedisImNotificationQueue.class);
                var properties = c.getBean(ImNotificationProperties.class); properties.setConsumer("worker-1");
                when(settings.findLive(9L, "ui.locale")).thenReturn(setting("\"en-US\""));
                var task = new ImNotificationTask("100:" + channel + ":9", 100, 7, 42, 9, "SYSTEM", 0,
                        "系统", "rid", "<b>{raw}</b>", "WORKITEM_ASSIGNED", null, null, null,
                        "SYSTEM_NAME_FALLBACK", null);
                queue.enqueue(task);
                var fields = ArgumentCaptor.forClass(Map.class);
                verify(redis).xadd(eq(properties.getStreamKey()), fields.capture(), eq(10000L));
                String payload = (String) fields.getValue().get("payload");
                when(settings.findLive(9L, "ui.locale")).thenReturn(setting("\"zh-CN\""));
                var entry = new redis.clients.jedis.StreamEntry(new redis.clients.jedis.StreamEntryID("1-0"), Map.of("payload", payload));
                when(redis.xreadGroup(properties.getStreamKey(), properties.getGroup(), "worker-1", 10, 0)).thenReturn(List.of(entry));
                when(redis.xautoClaim(properties.getStreamKey(), properties.getGroup(), "worker-1", 30000L, 10)).thenReturn(List.of(entry));
                when(redis.xpendingDeliveryCount(properties.getStreamKey(), properties.getGroup(), "1-0")).thenReturn(2L);
                var initial = queue.readNew("worker-1", 10).get(0);
                var claimed = queue.claimStale("worker-1", 10).get(0);
                assertEquals(initial.task(), claimed.task()); assertEquals(payload, claimed.payload()); assertEquals(2L, claimed.deliveryCount());
                var identities = mock(UserImIdentityService.class); var identity = new UserImIdentityDO(); identity.setExternalUserId("fake-user");
                when(identities.find(9L, channel)).thenReturn(identity);
                when(c.getBean(PlatformImChannelConfigService.class).isReady(channel)).thenReturn(true);
                var resolver = mock(ImNotificationMessageContextResolver.class);
                when(resolver.resolve(any())).thenReturn(new ImNotificationMessageContext("raw workspace", "原始状态", "https://example.com", 7));
                var provider = c.getBean(ImProvider.class); when(c.getBean(ImProviderRegistry.class).require(channel)).thenReturn(provider);
                doThrow(new ImDeliveryException(channel, true, "429", "fake-rid", null)).doNothing().when(provider).send(any());
                var worker = new ImNotificationWorker(queue, new ImNotificationFormatter(), identities,
                        c.getBean(PlatformImChannelConfigService.class), c.getBean(ImProviderRegistry.class), properties,
                        resolver, mock(ImNotificationPreferenceService.class));
                org.slf4j.MDC.put("requestId", "outer"); LocaleContextHolder.setLocale(Locale.CHINA);
                try {
                    worker.process(initial);
                    verify(redis, never()).xack(anyString(), anyString(), anyString());
                    verify(redis, never()).setIfAbsent(anyString(), anyString(), anyLong());
                    worker.process(claimed);
                    var commands = ArgumentCaptor.forClass(ImSendCommand.class); verify(provider, times(2)).send(commands.capture());
                    assertEquals(commands.getAllValues().get(0), commands.getAllValues().get(1));
                    assertEquals("Work item assignment notification", commands.getValue().title());
                    assertEquals("### Action required\n\n**Workspace**: raw workspace\n**Work item**: <b>{raw}</b>\n**Status**: 原始状态\n**Assigned by**: System\n\n[View Work item](https://example.com/workitems/42?workspaceId=7)", commands.getValue().markdown());
                    assertEquals("outer", org.slf4j.MDC.get("requestId"));
                    verify(redis).xack(properties.getStreamKey(), properties.getGroup(), "1-0");
                    verify(redis).setIfAbsent("autowonder:im-notification:delivered:100:" + channel + ":9", "1", 604800L);
                    when(redis.exists("autowonder:im-notification:delivered:100:" + channel + ":9")).thenReturn(true);
                    worker.process(claimed); verify(provider, times(2)).send(any());
                    verify(redis, times(2)).xack(properties.getStreamKey(), properties.getGroup(), "1-0");
                    queue.sendToDlq(claimed, "raw reason"); queue.sendToDlq(new ImNotificationEnvelope("2-0", claimed.task(), 4), "raw reason");
                    var dlq = ArgumentCaptor.forClass(Map.class); verify(redis, times(2)).xadd(eq(properties.getDlqStreamKey()), dlq.capture(), eq(10000L));
                    for (Map map : dlq.getAllValues()) { assertEquals(payload, map.get("payload")); assertEquals("raw reason", map.get("dropReason")); }
                    verify(settings, times(1)).findLive(9L, "ui.locale");
                } finally { worker.destroy(); org.slf4j.MDC.clear(); LocaleContextHolder.resetLocaleContext(); }
            }
        }
    }

    @Test
    void directSuccessDefaultsOnlyMissingOrUnsupportedPreferenceAndKeepsGuardsAheadOfPreferenceRead() {
        for (String value : Arrays.asList(null, "not-json", "{}", "42", "true", "\"fr-FR\"", "\"zh-CN\"")) {
            try (var c = context()) {
                var identity = new UserImIdentityDO(); identity.setExternalUserId("fake-user");
                when(c.getBean(UserImIdentityDao.class).find(9L, "FEISHU")).thenReturn(identity);
                when(c.getBean(PlatformImChannelConfigService.class).isReady("FEISHU")).thenReturn(true);
                when(c.getBean(ImProviderRegistry.class).require("FEISHU")).thenReturn(c.getBean(ImProvider.class));
                when(c.getBean(UserSettingDao.class).findLive(9L, "ui.locale")).thenReturn(setting(value));
                LocaleContextHolder.setLocale(Locale.US);
                c.getBean(UserImIdentityService.class).sendTest(9L, "FEISHU");
                var command = ArgumentCaptor.forClass(ImSendCommand.class); verify(c.getBean(ImProvider.class)).send(command.capture());
                assertEquals("<b>{brand}'s</b> 协作通知测试成功", command.getValue().title());
                assertEquals("## <b>{brand}'s</b> 协作通知测试成功\n\n你的 IM 协作通知配置可正常使用。", command.getValue().markdown());
            } finally { LocaleContextHolder.resetLocaleContext(); }
        }
        try (var c = context()) {
            var service = c.getBean(UserImIdentityService.class);
            var missing = assertThrows(com.aliyun.autowonder.common.error.BizException.class, () -> service.sendTest(9, "FEISHU"));
            assertEquals("28002", missing.getCode());
            verifyNoInteractions(c.getBean(UserSettingDao.class), c.getBean(PlatformBrandingService.class), c.getBean(ImProviderRegistry.class));
        }
    }

    @Test
    void creationPreferenceFailureCannotSilentlyCreateChinesePayloadAndReadsNeverFillLegacyMetadata() throws Exception {
        try (var c = context()) {
            var redis = c.getBean(RedisManager.class); var settings = c.getBean(UserSettingDao.class);
            var queue = c.getBean(RedisImNotificationQueue.class);
            var failure = new IllegalStateException("fake DAO failure");
            when(settings.findLive(9L, "ui.locale")).thenThrow(failure);
            var legacy = "{\"notificationKey\":\"1:DINGTALK:9\",\"recipientUserId\":9,\"actorDisplayName\":\"系统\",\"future\":{\"a\":true}}";
            var task = new ObjectMapper().readValue(legacy, ImNotificationTask.class);
            assertSame(failure, assertThrows(IllegalStateException.class, () -> queue.enqueue(task)));
            verify(redis, never()).xadd(anyString(), anyMap(), anyLong());
            when(redis.xreadGroup("autowonder:im-notification:stream", "autowonder-im-notification", "w", 1, 0))
                    .thenReturn(List.of(new redis.clients.jedis.StreamEntry(new redis.clients.jedis.StreamEntryID("1-0"), Map.of("payload", legacy))));
            var read = queue.readNew("w", 1).get(0);
            assertEquals(legacy, read.payload()); assertNull(read.task().recipientLocale());
            queue.sendToDlq(read, "raw");
            var fields = ArgumentCaptor.forClass(Map.class); verify(redis).xadd(eq("autowonder:im-notification:dlq"), fields.capture(), eq(10000L));
            assertEquals(legacy, fields.getValue().get("payload"));
            verify(settings, times(1)).findLive(9L, "ui.locale");
        }
    }

    static UserSettingDO setting(String value) {
        var row = new UserSettingDO(); row.setValueJson(value); return row;
    }
    static AnnotationConfigApplicationContext context() {
        var c = new AnnotationConfigApplicationContext();
        c.registerBean(RedisManager.class, () -> mock(RedisManager.class));
        c.registerBean(ImNotificationProperties.class);
        c.registerBean(UserSettingDao.class, () -> mock(UserSettingDao.class));
        c.registerBean(UserSettingService.class);
        c.registerBean(UserImIdentityDao.class, () -> mock(UserImIdentityDao.class));
        c.registerBean(PlatformImChannelConfigService.class, () -> mock(PlatformImChannelConfigService.class));
        c.registerBean(ImProviderRegistry.class, () -> mock(ImProviderRegistry.class));
        c.registerBean(ImProvider.class, () -> mock(ImProvider.class));
        c.registerBean(PlatformBrandingService.class, () -> {
            var b = mock(PlatformBrandingService.class);
            when(b.publicConfig()).thenReturn(new PlatformBrandingVO("<b>{brand}'s</b>", null, null, null, null, null, null, null, false, false));
            return b;
        });
        c.register(RedisImNotificationQueue.class, UserImIdentityService.class);
        c.refresh(); return c;
    }
}
