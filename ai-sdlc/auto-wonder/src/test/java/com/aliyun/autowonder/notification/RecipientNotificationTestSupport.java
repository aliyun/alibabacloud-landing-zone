package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.user.*;
import java.util.*;
import static org.mockito.Mockito.*;

/** Real recipient boundary with only persistence/settings/transport doubled; no external sends. */
public final class RecipientNotificationTestSupport {
    public final NotificationDao dao = mock(NotificationDao.class);
    public final UserSettingDao settings = mock(UserSettingDao.class);
    public final List<NotificationDO> rows = new ArrayList<>();
    public final List<NotificationDO> delivered = new ArrayList<>();
    public final NotifyService service;
    public RecipientNotificationTestSupport(com.aliyun.autowonder.notification.NotifyChannel... additionalChannels) {
        doAnswer(inv -> { NotificationDO row = inv.getArgument(0); row.setId((long) rows.size() + 1); rows.add(row); return null; }).when(dao).insert(any());
        NotifyChannel channel = new NotifyChannel() {
            public String name() { return "inApp"; }
            public boolean deliver(NotificationDO row) { delivered.add(row); return true; }
        };
        var channels = new ArrayList<NotifyChannel>(); channels.add(channel); channels.addAll(List.of(additionalChannels));
        service = new NotifyService(dao, mock(NotifyPrefDao.class), channels, new UserSettingService(settings));
    }
    public void preference(long userId, String json) {
        UserSettingDO row = new UserSettingDO(); row.setUserId(userId); row.setValueJson(json);
        when(settings.findLive(userId, "ui.locale")).thenReturn(row);
    }
    public static NotificationDO watchedAssignment(com.aliyun.autowonder.im.notification.WorkitemHumanAssignedEvent event, String localeJson) {
        var f = new RecipientNotificationTestSupport(); f.preference(666L, localeJson);
        var watchers = mock(com.aliyun.autowonder.workitem.WorkitemWatcherService.class);
        when(watchers.watcherUserIds(event.tenantId(), event.workitemId())).thenReturn(List.of(666L));
        var users = mock(UserDao.class); var recipient = new UserDO(); recipient.setNickname("raw recipient");
        when(users.findByIdIncludingDeleted(event.recipientUserId())).thenReturn(recipient);
        new InAppWorkitemWatcherListener(f.service, watchers, mock(com.aliyun.autowonder.workitem.WorkitemDao.class),
                users, mock(com.aliyun.autowonder.agent.AgentDao.class), mock(com.aliyun.autowonder.statemachine.StatusNodeDao.class),
                mock(com.aliyun.autowonder.workitem.WorkitemCommentMentionDao.class)).onHumanAssigned(event);
        return f.rows.get(0);
    }

}
