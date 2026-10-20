package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.im.notification.WorkitemHumanAssignedEvent;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.user.*;
import com.aliyun.autowonder.workitem.*;
import com.aliyun.autowonder.workspace.*;
import com.aliyun.autowonder.workspace.event.WorkspaceAccessRequestedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.context.i18n.LocaleContextHolder;

import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NotifyServiceLocaleTest {
    @Configuration(proxyBeanMethods = false)
    @Import({NotifyService.class, UserSettingService.class})
    static class Beans {
        @Bean NotificationDao rows() { return mock(NotificationDao.class); }
        @Bean NotifyPrefDao prefs() { return mock(NotifyPrefDao.class); }
        @Bean UserSettingDao settings() { return mock(UserSettingDao.class); }
        @Bean CaptureChannel channel() { return new CaptureChannel(); }
    }
    static class CaptureChannel implements NotifyChannel {
        final List<NotificationDO> delivered = new ArrayList<>();
        public String name() { return "inApp"; }
        public boolean deliver(NotificationDO row) { delivered.add(row); return true; }
    }
    @AfterEach void clearLocale() { LocaleContextHolder.resetLocaleContext(); }
    static void preference(UserSettingDao dao, long id, String value) {
        UserSettingDO row = new UserSettingDO(); row.setUserId(id); row.setSettingKey("ui.locale"); row.setValueJson(value);
        when(dao.findLive(id, "ui.locale")).thenReturn(row);
    }
    static WorkspaceMemberDO admin(long id) {
        WorkspaceMemberDO m = new WorkspaceMemberDO(); m.setUserId(id); m.setAccessLevel("ADMIN"); return m;
    }
    @Test void requestedListenerUsesEachStoredRecipientLanguageBeforeInsertAndDelivery() {
        try (var context = new AnnotationConfigApplicationContext(Beans.class)) {
            var settings = context.getBean(UserSettingDao.class);
            preference(settings, 2, "\"zh-CN\""); preference(settings, 3, "\"en-US\"");
            LocaleContextHolder.setLocale(Locale.US);
            var members = mock(WorkspaceMemberDao.class);
            when(members.listByTenant(8L)).thenReturn(List.of(admin(1), admin(2), admin(3)));
            new InAppWorkspaceAccessRequestedListener(context.getBean(NotifyService.class), members)
                    .onWorkspaceAccessRequested(new WorkspaceAccessRequestedEvent(8, 99, 1, "<b>A'{0}</b>", "READ_ONLY", "未命名工作空间"));
            var rows = context.getBean(CaptureChannel.class).delivered;
            assertEquals(2, rows.size());
            assertEquals("有新的权限申请", rows.get(0).getTitle());
            assertEquals("<b>A'{0}</b> 申请加入「未命名工作空间」，申请角色：只读者", rows.get(0).getContent());
            assertEquals("/settings/members?tab=requests&workspaceId=8", rows.get(1).getLink());
            assertEquals(99L, rows.get(1).getRefId());
            assertEquals("WORKSPACE_ACCESS_REQUEST", rows.get(1).getType());
            assertEquals("UNREAD", rows.get(1).getStatus());
            assertEquals("New workspace access request", rows.get(1).getTitle());
            assertEquals("<b>A'{0}</b> requested to join workspace “未命名工作空间” with the Viewer role.", rows.get(1).getContent());
        }
    }
    @Test void sharedWatcherEventRendersOppositePreferencesWithoutChangingRawActorOrTitle() {
        try (var context = new AnnotationConfigApplicationContext(Beans.class)) {
            preference(context.getBean(UserSettingDao.class), 2, "\"zh-CN\"");
            preference(context.getBean(UserSettingDao.class), 3, "\"en-US\"");
            LocaleContextHolder.resetLocaleContext();
            var watchers = mock(WorkitemWatcherService.class);
            when(watchers.watcherUserIds(8, 42)).thenReturn(List.of(5L, 2L, 3L));
            var users = mock(UserDao.class); var u = new UserDO(); u.setNickname("用户");
            when(users.findByIdIncludingDeleted(5L)).thenReturn(u);
            new InAppWorkitemWatcherListener(context.getBean(NotifyService.class), watchers, mock(WorkitemDao.class),
                    users, mock(AgentDao.class), mock(StatusNodeDao.class), mock(WorkitemCommentMentionDao.class))
                    .onHumanAssigned(new WorkitemHumanAssignedEvent(8, 42, "{0} <raw>", 9, 5, "AGENT", 7, "数字人", null));
            var rows = context.getBean(CaptureChannel.class).delivered;
            assertEquals(2, rows.size()); assertEquals("数字人 将「{0} <raw>」指派给了 用户", rows.get(0).getContent());
            assertEquals("/workitems/42", rows.get(1).getLink());
            assertEquals("数字人 assigned work item “{0} <raw>” to 用户.", rows.get(1).getContent());
        }
    }
    @Test void metadataCopiesArgumentsSharedEventStaysRawAndHistoricalRowsDoNotFollowPreferenceChanges() {
        var f = new RecipientNotificationTestSupport(); f.preference(2, "\"zh-CN\""); f.preference(3, "\"en-US\"");
        var event = new NotifyEvent(); event.setTenantId(8); event.setType("RAW_TYPE");
        event.setTitle("raw title"); event.setTitleKey("notification.scheduled_run.title_fallback");
        event.setContent("raw body"); event.setContentKey("notification.mention.content");
        event.setLink("/raw?x={0}"); event.setRefId(42L); event.setRefType("RAW_REF"); event.setRecipientIds(List.of(2L, 3L));
        Object[] title = {"1234"}; Object[] content = {"<b>A'{0}</b>", "未知状态", "raw {1} \"quoted\""};
        event.setTitleArgs(title); event.setContentArgs(content); title[0] = "mutated"; content[0] = "mutated";
        event.getTitleArgs()[0] = "mutated again"; event.getContentArgs()[0] = "mutated again";
        LocaleContextHolder.setLocale(Locale.US); f.service.notify(event);
        assertEquals("定时任务运行 #1234", f.rows.get(0).getTitle());
        assertEquals("Scheduled task run #1234", f.rows.get(1).getTitle());
        assertEquals("<b>A'{0}</b> mentioned you in work item “未知状态”: raw {1} \"quoted\"", f.rows.get(1).getContent());
        assertEquals("raw title", event.getTitle()); assertEquals("raw body", event.getContent());
        assertEquals("<b>A'{0}</b>", event.getContentArgs()[0]);
        assertSame(f.rows.get(0), f.delivered.get(0)); assertSame(f.rows.get(1), f.delivered.get(1));
        verify(f.settings, times(1)).findLive(2L, "ui.locale"); verify(f.settings, times(1)).findLive(3L, "ui.locale");
        f.preference(3, "\"zh-CN\""); clearInvocations(f.settings);
        when(f.dao.listByRecipient(8L, 3L, null, 0, 20)).thenReturn(List.of(f.rows.get(1)));
        var ctx = com.aliyun.autowonder.context.AutoWonderContext.get(); ctx.setUserId(3L); ctx.setCurrentWorkspaceId(8L);
        try {
            var history = new NotificationController(f.dao, mock(NotifyPrefDao.class), f.service,
                    mock(com.aliyun.autowonder.im.PlatformImChannelConfigService.class)).list(null, 1, 20).getData().getItems().get(0);
            assertEquals("Scheduled task run #1234", history.getTitle());
            assertEquals(f.rows.get(1).getContent(), history.getContent()); assertEquals("/raw?x={0}", history.getLink());
            verifyNoInteractions(f.settings);
        } finally { com.aliyun.autowonder.context.AutoWonderContext.destroy(); }
    }
    @Test void legacyAndUnknownMetadataRemainExactEvenWhenRawTextMatchesAnOwnedLabel() {
        var f = new RecipientNotificationTestSupport(); f.preference(3, "\"en-US\"");
        var e = new NotifyEvent(); e.setRecipientIds(List.of(3L)); e.setTitle("有新的权限申请"); e.setContent("OWNER_INACTIVE");
        f.service.notify(e); e.setTitleKey("unknown.title"); e.setContentKey("unknown.content");
        f.service.notify(e);
        for (var row : f.rows) { assertEquals("有新的权限申请", row.getTitle()); assertEquals("OWNER_INACTIVE", row.getContent()); }
        var legacy = new NotifyService(f.dao, mock(NotifyPrefDao.class), List.of());
        e.setTitleKey("notification.assigned.title"); legacy.notify(e);
        assertEquals("有新工单指派给你", f.rows.get(2).getTitle());
    }
    @Test void settingsFailurePropagatesBeforePersistenceAndDeletionKeepsRawDiagnostic() {
        var f = new RecipientNotificationTestSupport(); var error = new IllegalStateException("storage failed");
        when(f.settings.findLive(3L, "ui.locale")).thenThrow(error);
        var e = new NotifyEvent(); e.setRecipientIds(List.of(3L));
        assertSame(error, assertThrows(IllegalStateException.class, () -> f.service.notify(e)));
        verifyNoInteractions(f.dao); assertTrue(f.delivered.isEmpty());
        LocaleContextHolder.setLocale(Locale.US);
        var absent = assertThrows(com.aliyun.autowonder.common.error.BizException.class, () -> f.service.delete(1, 8, 3));
        assertEquals("通知不存在", absent.getMessage());
        assertEquals("Notification not found", com.aliyun.autowonder.common.result.Result.fail(absent).getMessage());
    }
    @Test void assignmentMentionReviewAndCancellationBodiesUseRecipientLanguageWithRawValues() {
        var f = new RecipientNotificationTestSupport(); f.preference(3, "\"en-US\"");
        new InAppWorkitemAssignedListener(f.service).onAssigned(new WorkitemHumanAssignedEvent(8,42,"raw <b> {0}",9,3,"HUMAN",1,"用户",null));
        new InAppCommentMentionedListener(f.service).onMentioned(new com.aliyun.autowonder.im.notification.WorkitemCommentMentionedEvent(
                8,42,"raw <b> {0}",7,3,"HUMAN",1,"用户",null,"x".repeat(101)));
        var reviewed = new InAppWorkspaceAccessReviewedListener(f.service);
        reviewed.onWorkspaceAccessReviewed(new com.aliyun.autowonder.workspace.event.WorkspaceAccessReviewedEvent(8,99,3,1,"用户",null,"READ_WRITE","APPROVED",null));
        reviewed.onWorkspaceAccessReviewed(new com.aliyun.autowonder.workspace.event.WorkspaceAccessReviewedEvent(8,99,3,1,"用户","未命名工作空间","RAW","REJECTED","  raw '{0} <b>  "));
        reviewed.onWorkspaceAccessReviewed(new com.aliyun.autowonder.workspace.event.WorkspaceAccessReviewedEvent(8,99,3,1,"用户",null,"RAW","REJECTED",null));
        var members = mock(WorkspaceMemberDao.class); when(members.listByTenant(8L)).thenReturn(List.of(admin(3)));
        new InAppWorkspaceAccessCancelledListener(f.service,members).onWorkspaceAccessCancelled(
                new com.aliyun.autowonder.workspace.event.WorkspaceAccessCancelledEvent(8,99,1,"用户","ADMIN",null));
        assertEquals(6,f.rows.size());
        assertEquals("A work item was assigned to you",f.rows.get(0).getTitle()); assertEquals("raw <b> {0}",f.rows.get(0).getContent());
        assertEquals("用户 mentioned you in work item “raw <b> {0}”: "+"x".repeat(100)+"...",f.rows.get(1).getContent());
        assertEquals("Your request to join workspace “Unnamed workspace” was approved by 用户, assigning you the Collaborator role.",f.rows.get(2).getContent());
        assertEquals("Your request to join workspace “未命名工作空间” was rejected by 用户. Reason: raw '{0} <b>",f.rows.get(3).getContent());
        assertEquals("Your request to join workspace “Unnamed workspace” was rejected by 用户.",f.rows.get(4).getContent());
        assertTrue(f.rows.get(5).getContent().matches("用户 canceled the request to join workspace “Unnamed workspace” at \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}\\. The request record will be deleted\\."));
        assertEquals("WORKSPACE_ACCESS_REQUEST",f.rows.get(5).getType());
    }
    @Test void watcherNullSourcesTranslateButAuthoredAndNumericValuesStayRaw() {
        var f = new RecipientNotificationTestSupport(); f.preference(2,"\"zh-CN\""); f.preference(3,"\"en-US\"");
        var watchers=mock(WorkitemWatcherService.class); when(watchers.watcherUserIds(8,42)).thenReturn(List.of(2L,3L));
        var items=mock(WorkitemDao.class); var w=new WorkitemDO();w.setTenantId(8L);w.setTitle("数字员工 {0}");when(items.findById(42L)).thenReturn(w);
        var nodes=mock(StatusNodeDao.class); var agents=mock(AgentDao.class);
        var listener=new InAppWorkitemWatcherListener(f.service,watchers,items,mock(UserDao.class),agents,nodes,mock(WorkitemCommentMentionDao.class));
        listener.onAgentAssigned(new com.aliyun.autowonder.dispatch.WorkitemAssignedEvent(8,42,null,null,1,1));
        var rawAgent=new com.aliyun.autowonder.agent.AgentDO();rawAgent.setName("数字员工");when(agents.findById(7L)).thenReturn(rawAgent);
        listener.onAgentAssigned(new com.aliyun.autowonder.dispatch.WorkitemAssignedEvent(8,42,null,7L,1,1));
        listener.onStatusChanged(new com.aliyun.autowonder.integration.event.WorkitemStatusChangedEvent(8,42,9,1));
        var node=new com.aliyun.autowonder.statemachine.StatusNodeDO();node.setName("未知状态");node.setCategory("DONE");when(nodes.findById(9L)).thenReturn(node);
        listener.onStatusChanged(new com.aliyun.autowonder.integration.event.WorkitemStatusChangedEvent(8,42,9,1));
        assertEquals(8,f.rows.size());
        assertEquals("「数字员工 {0}」已指派给数字员工 数字员工",f.rows.get(0).getContent());
        assertEquals("Work item “数字员工 {0}” was assigned to Agent Agent.",f.rows.get(1).getContent());
        assertEquals("Work item “数字员工 {0}” was assigned to Agent 数字员工.",f.rows.get(3).getContent());
        assertEquals("The status of work item “数字员工 {0}” changed to Unknown status.",f.rows.get(5).getContent());
        assertEquals("The status of work item “数字员工 {0}” changed to 未知状态.",f.rows.get(7).getContent());
        assertEquals("WATCHED_COMPLETED",f.rows.get(7).getType());
        verify(agents,times(1)).findById(7L);verify(nodes,times(2)).findById(9L);
    }

    @Test void literalOldEventJsonAndUnknownMarkersPreserveRawActorAndScheduledTitle() throws Exception {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        String old="{\"tenantId\":8,\"workitemId\":42,\"workitemTitle\":\"定时任务运行 #42\",\"commentId\":7,\"recipientUserId\":3,\"actorType\":\"AGENT\",\"actorRef\":9,\"actorDisplayName\":\"数字人\",\"requestId\":null,\"commentContentMd\":null,\"sourceType\":\"SCHEDULED_TASK_RUN\"}";
        var event=mapper.readValue(old,com.aliyun.autowonder.im.notification.WorkitemCommentMentionedEvent.class);
        var oldActor=mapper.readValue("{\"type\":\"SYSTEM\",\"ref\":0,\"displayName\":\"系统\"}",AssignmentActor.class);
        assertNull(oldActor.actorNameKind());assertEquals("系统",AssignmentActor.recipientDisplayName(oldActor.displayName(),oldActor.actorNameKind(),Locale.US));
        var oldAssignment=mapper.readValue("{\"tenantId\":8,\"workitemId\":42,\"workitemTitle\":\"raw\",\"workitemEventId\":9,\"recipientUserId\":5,\"actorType\":\"HUMAN\",\"actorRef\":7,\"actorDisplayName\":\"用户\",\"requestId\":null}",WorkitemHumanAssignedEvent.class);
        assertNull(oldAssignment.actorNameKind());assertEquals("用户 assigned work item “raw” to raw recipient.",RecipientNotificationTestSupport.watchedAssignment(oldAssignment,"\"en-US\"").getContent());
        assertNull(event.workitemTitleKind());
        assertEquals("定时任务运行 #42",event.recipientTitle(Locale.US));
        var f=new RecipientNotificationTestSupport();f.preference(3,"\"en-US\"");
        new InAppCommentMentionedListener(f.service).onMentioned(event);
        assertEquals("数字人 mentioned you in a run comment for Scheduled task “定时任务运行 #42”: ",f.rows.get(0).getContent());
        var unknown=new WorkitemHumanAssignedEvent(8,42,"raw",9,5,"SYSTEM",0,"系统",null,"UNKNOWN");
        assertEquals("系统 assigned work item “raw” to raw recipient.",RecipientNotificationTestSupport.watchedAssignment(unknown,"\"en-US\"").getContent());
        assertFalse(mapper.valueToTree(new WorkitemHumanAssignedEvent(8,42,"raw",9,5,"SYSTEM",0,"系统",null)).has("actorNameKind"));
        assertFalse(mapper.valueToTree(event).has("workitemTitleKind"));
    }

    @Test void watcherCommentSnapshotsRawArgumentsAndKeepsMentionAndHumanExclusions() {
        var f=new RecipientNotificationTestSupport(); f.preference(2,"\"zh-CN\""); f.preference(3,"\"en-US\"");
        var watchers=mock(WorkitemWatcherService.class); when(watchers.watcherUserIds(8,42)).thenReturn(List.of(1L,2L,3L,4L));
        var items=mock(WorkitemDao.class); var w=new WorkitemDO();w.setTenantId(8L);w.setTitle("original {0} <b>");when(items.findById(42L)).thenReturn(w);
        var users=mock(UserDao.class);var author=new UserDO();author.setNickname("用户");when(users.findByIdIncludingDeleted(1L)).thenReturn(author);
        var mentions=mock(WorkitemCommentMentionDao.class);var mention=new WorkitemCommentMentionDO();mention.setCommentId(7L);mention.setTargetType("HUMAN");mention.setTargetRef(4L);
        when(mentions.listByWorkitem(8L,42L)).thenReturn(List.of(mention));
        when(f.settings.findLive(3L,"ui.locale")).thenAnswer(inv->{w.setTitle("changed during fanout");var pref=new UserSettingDO();pref.setValueJson("\"en-US\"");return pref;});
        new InAppWorkitemWatcherListener(f.service,watchers,items,users,mock(AgentDao.class),mock(StatusNodeDao.class),mentions)
                .onCommentCreated(new com.aliyun.autowonder.integration.event.WorkitemCommentCreatedEvent(8,42,7,"HUMAN",1,"x".repeat(101)));
        assertEquals(2,f.rows.size());
        assertEquals("用户 在「original {0} <b>」发表了新评论："+"x".repeat(100)+"...",f.rows.get(0).getContent());
        assertEquals("用户 posted a new comment on work item “original {0} <b>”: "+"x".repeat(100)+"...",f.rows.get(1).getContent());
        assertEquals("WATCHED_COMMENT",f.rows.get(1).getType());assertEquals("/workitems/42",f.rows.get(1).getLink());
        verify(users,times(1)).findByIdIncludingDeleted(1L); verifyNoMoreInteractions(users);
    }

}
