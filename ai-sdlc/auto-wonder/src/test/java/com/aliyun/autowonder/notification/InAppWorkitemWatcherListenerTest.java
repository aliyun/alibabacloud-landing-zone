package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.dispatch.WorkitemAssignedEvent;
import com.aliyun.autowonder.im.notification.WorkitemHumanAssignedEvent;
import com.aliyun.autowonder.integration.event.WorkitemCommentCreatedEvent;
import com.aliyun.autowonder.integration.event.WorkitemStatusChangedEvent;
import com.aliyun.autowonder.statemachine.StatusNodeDO;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.workitem.WorkitemCommentMentionDO;
import com.aliyun.autowonder.workitem.WorkitemCommentMentionDao;
import com.aliyun.autowonder.workitem.WorkitemDO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import com.aliyun.autowonder.workitem.WorkitemWatcherService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 关注人站内通知监听器：收件人 = 有效关注人 − 操作者（真人）− 事件专属收件人。
 * 覆盖需求要求的去重口径（同时是负责人 + 被@ + 关注人只收到一条）与租户可见性。
 */
class InAppWorkitemWatcherListenerTest {

    private static final long TENANT = 1L;
    private static final long WORKITEM = 42L;
    private static final long COMMENT = 777L;

    private NotifyService notifyService;
    private WorkitemWatcherService watcherService;
    private WorkitemDao workitemDao;
    private UserDao userDao;
    private AgentDao agentDao;
    private StatusNodeDao statusNodeDao;
    private WorkitemCommentMentionDao commentMentionDao;
    private InAppWorkitemWatcherListener listener;

    @BeforeEach
    void setUp() {
        notifyService = mock(NotifyService.class);
        watcherService = mock(WorkitemWatcherService.class);
        workitemDao = mock(WorkitemDao.class);
        userDao = mock(UserDao.class);
        agentDao = mock(AgentDao.class);
        statusNodeDao = mock(StatusNodeDao.class);
        commentMentionDao = mock(WorkitemCommentMentionDao.class);
        listener = new InAppWorkitemWatcherListener(notifyService, watcherService, workitemDao, userDao,
                agentDao, statusNodeDao, commentMentionDao);
    }

    private void stubWorkitem(long tenantId) {
        WorkitemDO w = new WorkitemDO();
        w.setId(WORKITEM);
        w.setTenantId(tenantId);
        w.setTitle("Fix login bug");
        when(workitemDao.findById(WORKITEM)).thenReturn(w);
    }

    private void stubWatchers(Long... ids) {
        when(watcherService.watcherUserIds(TENANT, WORKITEM)).thenReturn(Arrays.asList(ids));
    }

    private void stubHuman(long id, String nickname) {
        UserDO u = new UserDO();
        u.setId(id);
        u.setNickname(nickname);
        u.setUsername("user" + id);
        when(userDao.findById(id)).thenReturn(u);
    }

    private WorkitemCommentMentionDO mention(long commentId, String targetType, Long targetRef) {
        WorkitemCommentMentionDO m = new WorkitemCommentMentionDO();
        m.setCommentId(commentId);
        m.setTargetType(targetType);
        m.setTargetRef(targetRef);
        return m;
    }

    private NotifyEvent captureNotify() {
        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService).notify(captor.capture());
        return captor.getValue();
    }

    @Test
    void commentNotifiesWatchersExcludingHumanActorAndMentioned() {
        stubWorkitem(TENANT);
        // 关注人 10/20/30：10 是评论作者，20 被@，只有 30 应收到关注通知
        stubWatchers(10L, 20L, 30L);
        stubHuman(10L, "Alice");
        when(commentMentionDao.listByWorkitem(TENANT, WORKITEM))
                .thenReturn(Collections.singletonList(mention(COMMENT, "HUMAN", 20L)));

        listener.onCommentCreated(new WorkitemCommentCreatedEvent(TENANT, WORKITEM, COMMENT, "HUMAN", 10L, "please review"));

        NotifyEvent sent = captureNotify();
        assertEquals("WATCHED_COMMENT", sent.getType());
        assertEquals("你关注的工单有新评论", sent.getTitle());
        assertEquals("/workitems/42", sent.getLink());
        assertEquals("WORKITEM", sent.getRefType());
        assertEquals(WORKITEM, sent.getRefId());
        assertEquals(Collections.singletonList(30L), sent.getRecipientIds());
        assertTrue(sent.getContent().contains("Alice"));
        assertTrue(sent.getContent().contains("please review"));
    }

    @Test
    void commentFromAgentActorDoesNotExcludeActorAsHuman() {
        stubWorkitem(TENANT);
        stubWatchers(30L, 40L);
        AgentDO agent = new AgentDO();
        agent.setName("功能增量分析员");
        when(agentDao.findById(40013L)).thenReturn(agent);
        when(commentMentionDao.listByWorkitem(TENANT, WORKITEM)).thenReturn(Collections.emptyList());

        listener.onCommentCreated(new WorkitemCommentCreatedEvent(TENANT, WORKITEM, COMMENT, "AGENT", 40013L, "分析完成"));

        NotifyEvent sent = captureNotify();
        assertEquals(Arrays.asList(30L, 40L), sent.getRecipientIds());
        assertTrue(sent.getContent().contains("功能增量分析员"));
    }

    @Test
    void commentIgnoredWhenWorkitemTenantMismatch() {
        stubWorkitem(TENANT + 1);

        listener.onCommentCreated(new WorkitemCommentCreatedEvent(TENANT, WORKITEM, COMMENT, "HUMAN", 10L, "hi"));

        verify(notifyService, never()).notify(any());
    }

    @Test
    void commentSendsNothingWhenNoWatchers() {
        stubWorkitem(TENANT);
        when(watcherService.watcherUserIds(TENANT, WORKITEM)).thenReturn(Collections.emptyList());
        when(commentMentionDao.listByWorkitem(TENANT, WORKITEM)).thenReturn(Collections.emptyList());

        listener.onCommentCreated(new WorkitemCommentCreatedEvent(TENANT, WORKITEM, COMMENT, "HUMAN", 10L, "hi"));

        verify(notifyService, never()).notify(any());
    }

    @Test
    void commentSwallowsDownstreamException() {
        stubWorkitem(TENANT);
        stubWatchers(30L);
        when(commentMentionDao.listByWorkitem(TENANT, WORKITEM)).thenReturn(Collections.emptyList());
        doThrow(new RuntimeException("db down")).when(notifyService).notify(any());

        assertDoesNotThrow(() -> listener.onCommentCreated(
                new WorkitemCommentCreatedEvent(TENANT, WORKITEM, COMMENT, "HUMAN", 10L, "hi")));
    }

    @Test
    void humanAssignmentExcludesNewOwnerEvenWhenAlsoWatcher() {
        // 关注人 50/60：50 正是新负责人，已通过指派通知获知，这里排除，只剩 60
        stubWatchers(50L, 60L);
        stubHuman(50L, "Bob");

        listener.onHumanAssigned(new WorkitemHumanAssignedEvent(
                TENANT, WORKITEM, "Fix login bug", 555L, 50L, "HUMAN", 70L, "Carol", "req-1"));

        NotifyEvent sent = captureNotify();
        assertEquals("WATCHED_ASSIGNED", sent.getType());
        assertEquals("你关注的工单变更了负责人", sent.getTitle());
        assertEquals(Collections.singletonList(60L), sent.getRecipientIds());
    }

    @Test
    void humanAssignmentExcludesHumanActor() {
        stubWatchers(70L, 60L);
        stubHuman(50L, "Bob");

        // 操作者 70 同时是关注人，排除后只剩 60
        listener.onHumanAssigned(new WorkitemHumanAssignedEvent(
                TENANT, WORKITEM, "Fix login bug", 555L, 50L, "HUMAN", 70L, "Carol", "req-1"));

        assertEquals(Collections.singletonList(60L), captureNotify().getRecipientIds());
    }

    @Test
    void agentAssignmentExcludesTriggeringUser() {
        stubWorkitem(TENANT);
        stubWatchers(80L, 90L);
        AgentDO agent = new AgentDO();
        agent.setName("全栈开发");
        when(agentDao.findById(40013L)).thenReturn(agent);

        // userId=80 触发指派且是关注人，排除后只剩 90
        listener.onAgentAssigned(new WorkitemAssignedEvent(TENANT, WORKITEM, null, 40013L, 1, 80L));

        NotifyEvent sent = captureNotify();
        assertEquals("WATCHED_ASSIGNED", sent.getType());
        assertEquals(Collections.singletonList(90L), sent.getRecipientIds());
        assertTrue(sent.getContent().contains("全栈开发"));
    }

    @Test
    void agentAssignmentIgnoredWhenWorkitemTenantMismatch() {
        stubWorkitem(TENANT + 1);

        listener.onAgentAssigned(new WorkitemAssignedEvent(TENANT, WORKITEM, null, 40013L, 1, 80L));

        verify(notifyService, never()).notify(any());
    }

    @Test
    void statusChangeInProgressUsesStatusChangedTypeAndExcludesHumanActor() {
        stubWorkitem(TENANT);
        stubWatchers(10L, 30L);
        StatusNodeDO node = new StatusNodeDO();
        node.setCategory("IN_PROGRESS");
        node.setName("开发中");
        when(statusNodeDao.findById(300L)).thenReturn(node);

        // 4 参构造默认真人操作者，userId=10 是关注人也是操作者，排除后只剩 30
        listener.onStatusChanged(new WorkitemStatusChangedEvent(TENANT, WORKITEM, 300L, 10L));

        NotifyEvent sent = captureNotify();
        assertEquals("WATCHED_STATUS_CHANGED", sent.getType());
        assertEquals("你关注的工单状态有更新", sent.getTitle());
        assertEquals(Collections.singletonList(30L), sent.getRecipientIds());
        assertTrue(sent.getContent().contains("开发中"));
    }

    @Test
    void statusChangeDoneCategoryUsesCompletedType() {
        stubWorkitem(TENANT);
        stubWatchers(30L);
        StatusNodeDO node = new StatusNodeDO();
        node.setCategory("DONE");
        node.setName("已完成");
        when(statusNodeDao.findById(301L)).thenReturn(node);

        listener.onStatusChanged(new WorkitemStatusChangedEvent(TENANT, WORKITEM, 301L, 10L));

        NotifyEvent sent = captureNotify();
        assertEquals("WATCHED_COMPLETED", sent.getType());
        assertEquals("你关注的工单已完成/关闭", sent.getTitle());
        assertEquals(Collections.singletonList(30L), sent.getRecipientIds());
    }

    @Test
    void statusChangeCanceledCategoryUsesCompletedType() {
        stubWorkitem(TENANT);
        stubWatchers(30L);
        StatusNodeDO node = new StatusNodeDO();
        node.setCategory("CANCELED");
        node.setName("已关闭");
        when(statusNodeDao.findById(302L)).thenReturn(node);

        listener.onStatusChanged(new WorkitemStatusChangedEvent(TENANT, WORKITEM, 302L, 10L));

        assertEquals("WATCHED_COMPLETED", captureNotify().getType());
    }

    @Test
    void statusChangeByAgentActorDoesNotExcludeAnyWatcher() {
        stubWorkitem(TENANT);
        stubWatchers(10L, 30L);
        StatusNodeDO node = new StatusNodeDO();
        node.setCategory("IN_PROGRESS");
        node.setName("开发中");
        when(statusNodeDao.findById(300L)).thenReturn(node);

        // 数字员工流转：userId=10 是 agent 关联用户，但操作者是 AGENT，不按真人排除
        listener.onStatusChanged(new WorkitemStatusChangedEvent(
                WorkitemStatusChangedEvent.ACTOR_AGENT, TENANT, WORKITEM, 300L, 10L));

        assertEquals(Arrays.asList(10L, 30L), captureNotify().getRecipientIds());
    }

    @Test
    void statusChangeSendsNothingWhenNoWatchersRemain() {
        stubWorkitem(TENANT);
        stubWatchers(10L);
        StatusNodeDO node = new StatusNodeDO();
        node.setCategory("IN_PROGRESS");
        when(statusNodeDao.findById(300L)).thenReturn(node);

        // 唯一关注人就是操作者本人，排除后无收件人
        listener.onStatusChanged(new WorkitemStatusChangedEvent(TENANT, WORKITEM, 300L, 10L));

        verify(notifyService, never()).notify(any());
    }

    @Test
    void notifiedOnceWhenUserIsOwnerMentionedAndWatcher() {
        // 用户 20 同时被@且是关注人：评论事件里作为@对象已单独通知，关注通知必须排除，避免重复
        stubWorkitem(TENANT);
        stubWatchers(20L, 30L);
        stubHuman(10L, "Alice");
        when(commentMentionDao.listByWorkitem(TENANT, WORKITEM))
                .thenReturn(Collections.singletonList(mention(COMMENT, "HUMAN", 20L)));

        listener.onCommentCreated(new WorkitemCommentCreatedEvent(TENANT, WORKITEM, COMMENT, "HUMAN", 10L, "cc @20"));

        List<Long> recipients = captureNotify().getRecipientIds();
        assertFalse(recipients.contains(20L));
        assertEquals(Collections.singletonList(30L), recipients);
    }

    // ---- NB-QA-1：onStatusChanged 的租户不匹配早退分支（4 个事件入口中此前唯一未覆盖处） ----

    @Test
    void statusChangeIgnoredWhenWorkitemTenantMismatch() {
        stubWorkitem(TENANT + 1);

        listener.onStatusChanged(new WorkitemStatusChangedEvent(TENANT, WORKITEM, 300L, 10L));

        verify(notifyService, never()).notify(any());
    }

    // ---- NB-QA-2：名称回退分支（humanName / agentName / statusName） ----

    @Test
    void commentActorNameFallsBackToUserIdWhenUserMissing() {
        stubWorkitem(TENANT);
        stubWatchers(30L);
        // 不 stub userDao.findById(10) → 返回 null，humanName 回退为字符串形式的 userId
        when(commentMentionDao.listByWorkitem(TENANT, WORKITEM)).thenReturn(Collections.emptyList());

        listener.onCommentCreated(new WorkitemCommentCreatedEvent(TENANT, WORKITEM, COMMENT, "HUMAN", 10L, "hello"));

        assertTrue(captureNotify().getContent().startsWith("10 在「"));
    }

    @Test
    void agentAssignmentNameFallsBackToPlaceholderWhenAgentIdNull() {
        stubWorkitem(TENANT);
        stubWatchers(90L);

        // agentId=null → agentName 回退 "数字员工"；模板本身已含一次，故断言连出两次以定位回退分支
        listener.onAgentAssigned(new WorkitemAssignedEvent(TENANT, WORKITEM, null, null, 1, 80L));

        NotifyEvent sent = captureNotify();
        assertTrue(sent.getContent().contains("数字员工 数字员工"));
        assertEquals(Collections.singletonList(90L), sent.getRecipientIds());
    }

    @Test
    void agentAssignmentNameFallsBackToIdWhenAgentMissing() {
        stubWorkitem(TENANT);
        stubWatchers(90L);
        // 不 stub agentDao.findById(40013) → 返回 null，agentName 回退为 id 字符串

        listener.onAgentAssigned(new WorkitemAssignedEvent(TENANT, WORKITEM, null, 40013L, 1, 80L));

        assertTrue(captureNotify().getContent().contains("40013"));
    }

    @Test
    void statusNameFallsBackToPlaceholderWhenNodeMissing() {
        stubWorkitem(TENANT);
        stubWatchers(30L);
        // 不 stub statusNodeDao.findById(300) → node=null，非终态且 statusName 回退 "未知状态"

        listener.onStatusChanged(new WorkitemStatusChangedEvent(TENANT, WORKITEM, 300L, 10L));

        NotifyEvent sent = captureNotify();
        assertEquals("WATCHED_STATUS_CHANGED", sent.getType());
        assertTrue(sent.getContent().contains("未知状态"));
    }

    @Test
    void statusNameFallsBackToCodeWhenNameBlank() {
        stubWorkitem(TENANT);
        stubWatchers(30L);
        StatusNodeDO node = new StatusNodeDO();
        node.setCategory("IN_PROGRESS");
        node.setCode("DEV_NODE");
        // name 留空 → statusName 回退到 code
        when(statusNodeDao.findById(300L)).thenReturn(node);

        listener.onStatusChanged(new WorkitemStatusChangedEvent(TENANT, WORKITEM, 300L, 10L));

        assertTrue(captureNotify().getContent().contains("DEV_NODE"));
    }
}
