package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.dispatch.DispatchRequestService.HumanRequestCreated;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.im.ImProviderRegistry;
import com.aliyun.autowonder.im.UserImIdentityService;
import com.aliyun.autowonder.im.notification.*;
import com.aliyun.autowonder.setting.SystemSettingService;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.user.UserSettingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchRequestNotificationListenerTest {
    @Test void requestsReachTheNotificationCenterAndReliableImQueueWithFullStepNames() {
        var dao = mock(NotificationDao.class);
        doAnswer(i -> { ((NotificationDO) i.getArgument(0)).setId(42L); return null; }).when(dao).insert(any());
        var queue = mock(ImNotificationQueue.class);
        var configs = mock(PlatformImChannelConfigService.class);
        when(configs.selectedProvider()).thenReturn("DINGTALK");
        var channel = new PlatformImNotifyChannel(configs, mock(UserImIdentityService.class), mock(ImProviderRegistry.class),
                mock(SystemSettingService.class), queue);
        var notifications = new NotifyService(dao, mock(NotifyPrefDao.class), List.of(channel));
        var listener = new DispatchRequestNotificationListener(notifications);
        for (String kind : List.of("APPROVAL", "INPUT")) {
            listener.onCreated(new HumanRequestCreated(1, 10, 7, "测试工单", kind,
                    "编写验收摘要并等待批准", "请选择环境", null, List.of(9L, 10L)));
        }
        var rows = ArgumentCaptor.forClass(NotificationDO.class);
        verify(dao, times(4)).insert(rows.capture());
        assertEquals(List.of(9L, 10L, 9L, 10L), rows.getAllValues().stream().map(NotificationDO::getRecipientId).toList());
        assertTrue(rows.getAllValues().stream().allMatch(n -> n.getTitle().contains("编写验收摘要并等待批准") && "UNREAD".equals(n.getStatus())));
        var queued = ArgumentCaptor.forClass(ImNotificationTask.class);
        verify(queue, times(4)).enqueue(queued.capture());
        for (var task : queued.getAllValues()) {
            String markdown = new ImNotificationFormatter().format(new ImNotificationMessageContext("测试空间", "执行中", "https://test.example", 1), task);
            assertTrue(markdown.contains("编写验收摘要并等待批准"));
            assertTrue(markdown.contains("测试工单"));
            assertTrue(markdown.contains("/workitems/10?workspaceId=1"));
        }
    }

    @ParameterizedTest
    @CsvSource({"APPROVAL,en-US", "APPROVAL,zh-CN", "INPUT,en-US", "INPUT,zh-CN"})
    void rendersEachRecipientBeforePersistenceAndKeepsTheSameLocaleInTheQueue(String kind, String requestLocale) throws Exception {
        var dao = mock(NotificationDao.class);
        var settings = mock(UserSettingService.class);
        when(settings.recipientLocale(9L)).thenReturn(Locale.US);
        when(settings.recipientLocale(10L)).thenReturn(Locale.CHINA);
        doAnswer(i -> {
            NotificationDO notification = i.getArgument(0);
            notification.setId(notification.getRecipientId());
            // A preference change after rendering must not produce a card in two languages.
            when(settings.recipientLocale(notification.getRecipientId())).thenReturn(Locale.CHINA);
            return null;
        }).when(dao).insert(any());
        var redis = mock(RedisManager.class);
        var queue = new RedisImNotificationQueue(redis, new ImNotificationProperties(), settings);
        var configs = mock(PlatformImChannelConfigService.class);
        when(configs.selectedProvider()).thenReturn("DINGTALK");
        var channel = new PlatformImNotifyChannel(configs, mock(UserImIdentityService.class), mock(ImProviderRegistry.class),
                mock(SystemSettingService.class), queue);
        var listener = new DispatchRequestNotificationListener(new NotifyService(dao, mock(NotifyPrefDao.class), List.of(channel), settings));
        String title = "原始工单 O'Reilly {0}", step = "原始步骤 {1}", question = "模型原文 don't change {2}";
        try {
            LocaleContextHolder.setLocale(Locale.forLanguageTag(requestLocale));
            listener.onCreated(new HumanRequestCreated(1, 10, 7, title, kind, step, question, null, List.of(9L, 10L)));
        } finally { LocaleContextHolder.resetLocaleContext(); }
        var rows = ArgumentCaptor.forClass(NotificationDO.class);
        verify(dao, times(2)).insert(rows.capture());
        boolean approval = "APPROVAL".equals(kind);
        var english = rows.getAllValues().get(0);
        var chinese = rows.getAllValues().get(1);
        assertEquals((approval ? "Approval required: " : "Answer required: ") + step, english.getTitle());
        assertEquals("Work item: " + title + "\nStep: " + step + "\n" + (approval
                ? "This step is complete. Review the artifacts, then approve to continue or request changes with a note."
                : question), english.getContent());
        assertEquals((approval ? "待审批：" : "待回答：") + step, chinese.getTitle());
        assertEquals("工单「" + title + "」\n步骤「" + step + "」\n" + (approval
                ? "本步骤已完成，请检查产物后批准继续，或填写意见要求修改。" : question), chinese.getContent());
        ArgumentCaptor<Map<String, String>> fields = ArgumentCaptor.forClass(Map.class);
        verify(redis, times(2)).xadd(anyString(), fields.capture(), anyLong());
        var mapper = new ObjectMapper();
        var context = new ImNotificationMessageContext("原始空间", null, "https://test.example", 1);
        for (int i = 0; i < 2; i++) {
            var row = rows.getAllValues().get(i);
            var task = mapper.readValue(fields.getAllValues().get(i).get("payload"), ImNotificationTask.class);
            assertEquals(i == 0 ? "en-US" : "zh-CN", task.recipientLocale());
            assertEquals(row.getTitle(), task.workitemTitle());
            assertEquals(row.getContent(), task.commentContentMd());
            String markdown = new ImNotificationFormatter().format(context, task);
            assertTrue(markdown.contains(row.getTitle()));
            assertTrue(markdown.contains(row.getContent()));
            assertTrue(markdown.contains(i == 0 ? "**Workspace**: 原始空间" : "**工作空间**：原始空间"));
            assertFalse(mapper.valueToTree(row).has("recipientLocale"));
        }
        verify(settings).recipientLocale(9L);
        verify(settings).recipientLocale(10L);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"CONFIRMATION_REQUIRED", "unknown"})
    void translatesOnlyExplicitRuntimeHeadingsAndPreservesRawOrLegacyText(String messageKind) {
        var dao = mock(NotificationDao.class);
        var settings = mock(UserSettingService.class);
        when(settings.recipientLocale(9L)).thenReturn(Locale.US);
        var listener = new DispatchRequestNotificationListener(new NotifyService(dao, mock(NotifyPrefDao.class), List.of(), settings));
        listener.onCreated(new HumanRequestCreated(1, 10, 7, "Raw title", "INPUT", "Raw step",
                "需要你的确认", messageKind, List.of(9L)));
        var row = ArgumentCaptor.forClass(NotificationDO.class);
        verify(dao).insert(row.capture());
        assertEquals("Work item: Raw title\nStep: Raw step\n" + ("CONFIRMATION_REQUIRED".equals(messageKind)
                ? "Your confirmation is needed" : "需要你的确认"), row.getValue().getContent());
    }
}
