package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.integration.event.WorkitemCommentCreatedEvent;
import com.aliyun.autowonder.im.notification.WorkitemCommentMentionedEvent;
import com.aliyun.autowonder.workspace.WorkspaceMemberDO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.statemachine.StatusNodeDao;
import com.aliyun.autowonder.statemachine.StatusTemplateDao;
import com.aliyun.autowonder.statemachine.StatusTransitionDao;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.workitem.dto.CommentVO;
import com.aliyun.autowonder.workitem.dto.ParticipantVO;
import com.aliyun.autowonder.workitem.dto.TimelineItemVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkitemCommentTest {

    WorkitemDao workitemDao;
    WorkitemCommentDao commentDao;
    WorkitemCommentMentionDao commentMentionDao;
    WorkitemEventDao eventDao;
    StatusTemplateDao templateDao;
    StatusNodeDao nodeDao;
    StatusTransitionDao transitionDao;
    AgentDao agentDao;
    UserDao userDao;
    WorkspaceMemberDao workspaceMemberDao;
    ApplicationEventPublisher eventPublisher;
    WorkitemService service;

    @BeforeEach
    void setUp() {
        workitemDao = mock(WorkitemDao.class);
        commentDao = mock(WorkitemCommentDao.class);
        commentMentionDao = mock(WorkitemCommentMentionDao.class);
        eventDao = mock(WorkitemEventDao.class);
        templateDao = mock(StatusTemplateDao.class);
        nodeDao = mock(StatusNodeDao.class);
        transitionDao = mock(StatusTransitionDao.class);
        agentDao = mock(AgentDao.class);
        userDao = mock(UserDao.class);
        workspaceMemberDao = mock(WorkspaceMemberDao.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new WorkitemService(workitemDao, commentDao, commentMentionDao, eventDao,
                templateDao, nodeDao, transitionDao,
                mock(com.aliyun.autowonder.sdlc.SdlcDao.class), mock(com.aliyun.autowonder.sdlc.SdlcStepDao.class),
                mock(com.aliyun.autowonder.dispatch.DispatchDao.class),
                mock(com.aliyun.autowonder.dispatch.DispatchRuntimeEventDao.class),
                agentDao,
                mock(com.aliyun.autowonder.dispatch.AgentSdlcResolver.class),
                mock(com.aliyun.autowonder.squad.SquadMemberDao.class),
                mock(com.aliyun.autowonder.executor.ExecutorDao.class),
                userDao,
                workspaceMemberDao,
                mock(com.aliyun.autowonder.guidance.GuidanceDao.class),
                mock(com.aliyun.autowonder.websocket.PresenceManager.class),
                mock(com.aliyun.autowonder.integration.common.ExternalWorkitemLinkDao.class),
                eventPublisher);
    }

    @Test
    void addCommentInsertsAndWritesCommentEvent() {
        WorkitemDO w = new WorkitemDO();
        w.setId(5L);
        w.setTenantId(100L);
        when(workitemDao.findById(5L)).thenReturn(w);

        CommentVO vo = service.addComment(5L, "looks good", 100L, 7L);

        assertEquals("looks good", vo.getContentMd());
        assertEquals("HUMAN", vo.getAuthorType());
        assertEquals(7L, vo.getAuthorRef());
        verify(commentDao).insert(argThat((WorkitemCommentDO c) ->
                c.getWorkitemId() == 5L && "HUMAN".equals(c.getAuthorType())
                        && c.getAuthorRef() == 7L && "looks good".equals(c.getContentMd())));
        verify(eventDao).insert(argThat((WorkitemEventDO e) -> "COMMENT".equals(e.getEventType())));
    }

    @Test
    void addCommentPersistsHumanMentions() {
        WorkitemDO w = new WorkitemDO();
        w.setId(5L);
        w.setTenantId(100L);
        when(workitemDao.findById(5L)).thenReturn(w);
        doAnswer(invocation -> {
            invocation.<WorkitemCommentDO>getArgument(0).setId(88L);
            return null;
        }).when(commentDao).insert(any(WorkitemCommentDO.class));
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setTenantId(100L);
        member.setUserId(9L);
        member.setStatus(0);
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 9L)).thenReturn(member);
        UserDO user = new UserDO();
        user.setId(9L);
        user.setNickname("李四");
        user.setUsername("lisi");
        when(userDao.findById(9L)).thenReturn(user);

        service.addComment(5L, "@李四 请确认", List.of(9L, 9L), 100L, 7L);

        verify(commentMentionDao).insert(argThat((WorkitemCommentMentionDO mention) ->
                mention.getTenantId() == 100L
                        && mention.getWorkitemId() == 5L
                        && mention.getCommentId() == 88L
                        && "HUMAN".equals(mention.getTargetType())
                        && mention.getTargetRef() == 9L
                        && "李四".equals(mention.getDisplayNameSnapshot())));
    }

    @Test
    void addCommentPublishesMentionEventForOtherHumanOnly() {
        WorkitemDO w = new WorkitemDO();
        w.setId(5L);
        w.setTenantId(100L);
        w.setTitle("处理线上问题");
        when(workitemDao.findById(5L)).thenReturn(w);
        doAnswer(invocation -> {
            invocation.<WorkitemCommentDO>getArgument(0).setId(88L);
            return null;
        }).when(commentDao).insert(any(WorkitemCommentDO.class));
        WorkspaceMemberDO selfMember = member(100L, 7L);
        WorkspaceMemberDO otherMember = member(100L, 9L);
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 7L)).thenReturn(selfMember);
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 9L)).thenReturn(otherMember);
        UserDO self = user(7L, "张三", "zhangsan");
        UserDO other = user(9L, "李四", "lisi");
        when(userDao.findById(7L)).thenReturn(self);
        when(userDao.findById(9L)).thenReturn(other);

        service.addComment(5L, "@张三 @李四 请确认", List.of(7L, 9L), 100L, 7L);

        verify(eventPublisher).publishEvent((Object) argThat(event -> {
            if (!(event instanceof WorkitemCommentMentionedEvent mentioned)) {
                return false;
            }
            return mentioned.tenantId() == 100L
                    && mentioned.workitemId() == 5L
                    && "处理线上问题".equals(mentioned.workitemTitle())
                    && mentioned.commentId() == 88L
                    && mentioned.recipientUserId() == 9L
                    && "HUMAN".equals(mentioned.actorType())
                    && mentioned.actorRef() == 7L
                    && "张三(7)".equals(mentioned.actorDisplayName())
                    && "@张三 @李四 请确认".equals(mentioned.commentContentMd());
        }));
        verify(eventPublisher, never()).publishEvent((Object) argThat(event ->
                event instanceof WorkitemCommentMentionedEvent mentioned && mentioned.recipientUserId() == 7L));
    }

    @Test
    void addCommentRejectsHumanMentionOutsideWorkspace() {
        WorkitemDO w = new WorkitemDO();
        w.setId(5L);
        w.setTenantId(100L);
        when(workitemDao.findById(5L)).thenReturn(w);
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 9L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.addComment(5L, "@李四 请确认", List.of(9L), 100L, 7L));

        assertEquals("11001", ex.getCode());
        verify(commentDao, never()).insert(any());
        verify(commentMentionDao, never()).insert(any());
    }

    @Test
    void addCommentRejectsCrossTenantWorkitem() {
        WorkitemDO w = new WorkitemDO();
        w.setId(5L);
        w.setTenantId(200L);
        when(workitemDao.findById(5L)).thenReturn(w);

        BizException ex = assertThrows(BizException.class,
                () -> service.addComment(5L, "x", 100L, 7L));

        assertEquals("13003", ex.getCode());
        verify(commentDao, never()).insert(any());
        verify(commentMentionDao, never()).insert(any());
    }

    @Test
    void addCommentParsesPlainTextHumanMentionAndPublishesMentionEvent() {
        WorkitemDO w = new WorkitemDO();
        w.setId(5L);
        w.setTenantId(100L);
        w.setTitle("处理线上问题");
        w.setCreatorId(7L);
        when(workitemDao.findById(5L)).thenReturn(w);
        doAnswer(invocation -> {
            invocation.<WorkitemCommentDO>getArgument(0).setId(88L);
            return null;
        }).when(commentDao).insert(any(WorkitemCommentDO.class));
        WorkspaceMemberDO member = member(100L, 9L);
        when(workspaceMemberDao.listByTenant(100L)).thenReturn(List.of(member));
        UserDO other = user(9L, "李四", "lisi");
        when(userDao.findById(9L)).thenReturn(other);
        when(agentDao.listByTenant(100L)).thenReturn(List.of());
        when(commentDao.listByWorkitem(5L)).thenReturn(List.of());
        when(commentMentionDao.listByWorkitem(100L, 5L)).thenReturn(List.of());

        service.addComment(5L, "@李四 请确认", 100L, 7L);

        verify(commentMentionDao).insert(argThat((WorkitemCommentMentionDO mention) ->
                mention.getCommentId() == 88L && mention.getTargetRef() == 9L));
        verify(eventPublisher).publishEvent((Object) argThat(event ->
                event instanceof WorkitemCommentMentionedEvent mentioned && mentioned.recipientUserId() == 9L));
    }

    @Test
    void addAgentCommentSetsAgentAuthor() {
        WorkitemDO w = new WorkitemDO();
        w.setId(500L);
        w.setTenantId(10000L);
        when(workitemDao.findById(500L)).thenReturn(w);

        CommentVO vo = service.addAgentComment(500L, "review done", 10000L, 10001L);

        assertEquals("AGENT", vo.getAuthorType());
        assertEquals(10001L, vo.getAuthorRef());
        verify(commentDao).insert(argThat((WorkitemCommentDO c) ->
                "AGENT".equals(c.getAuthorType()) && c.getAuthorRef() == 10001L
                        && c.getWorkitemId() == 500L && "review done".equals(c.getContentMd())));
        verify(eventDao).insert(argThat((WorkitemEventDO e) ->
                "COMMENT".equals(e.getEventType()) && "AGENT".equals(e.getActorType())));
    }

    @Test
    void addAgentCommentPublishesCommentCreatedEventForExternalWriteback() {
        WorkitemDO w = new WorkitemDO();
        w.setId(500L);
        w.setTenantId(10000L);
        when(workitemDao.findById(500L)).thenReturn(w);
        doAnswer(invocation -> {
            invocation.<WorkitemCommentDO>getArgument(0).setId(88001L);
            return null;
        }).when(commentDao).insert(any(WorkitemCommentDO.class));

        service.addAgentComment(500L, "关键结论：已完成", 10000L, 10001L);

        verify(eventPublisher).publishEvent((Object) argThat(event -> {
            if (!(event instanceof WorkitemCommentCreatedEvent created)) {
                return false;
            }
            return created.tenantId() == 10000L
                    && created.workitemId() == 500L
                    && created.commentId() == 88001L
                    && "AGENT".equals(created.actorType())
                    && created.actorRef() == 10001L
                    && "关键结论：已完成".equals(created.contentMd());
        }));
    }

    @Test
    void addAgentCommentParsesPlainTextHumanMentionAndPublishesMentionEvent() {
        WorkitemDO w = new WorkitemDO();
        w.setId(500L);
        w.setTenantId(10000L);
        w.setTitle("修复线上缺陷");
        w.setCreatorId(7L);
        when(workitemDao.findById(500L)).thenReturn(w);
        doAnswer(invocation -> {
            invocation.<WorkitemCommentDO>getArgument(0).setId(88001L);
            return null;
        }).when(commentDao).insert(any(WorkitemCommentDO.class));
        UserDO user = user(9L, "李四", "lisi");
        WorkspaceMemberDO member = member(10000L, 9L);
        when(workspaceMemberDao.listByTenant(10000L)).thenReturn(List.of(member));
        when(userDao.findById(9L)).thenReturn(user);
        AgentDO agent = new AgentDO();
        agent.setId(10001L);
        agent.setName("AW项目管理员");
        when(agentDao.findById(10001L)).thenReturn(agent);
        when(agentDao.listByTenant(10000L)).thenReturn(List.of());
        when(commentDao.listByWorkitem(500L)).thenReturn(List.of());
        when(commentMentionDao.listByWorkitem(10000L, 500L)).thenReturn(List.of());

        service.addAgentComment(500L, "@李四 请确认修复结果", 10000L, 10001L);

        verify(commentMentionDao).insert(argThat((WorkitemCommentMentionDO mention) ->
                mention.getTenantId() == 10000L
                        && mention.getWorkitemId() == 500L
                        && mention.getCommentId() == 88001L
                        && "HUMAN".equals(mention.getTargetType())
                        && mention.getTargetRef() == 9L));
        verify(eventPublisher).publishEvent((Object) argThat(event -> {
            if (!(event instanceof WorkitemCommentMentionedEvent mentioned)) {
                return false;
            }
            return mentioned.recipientUserId() == 9L
                    && "AGENT".equals(mentioned.actorType())
                    && mentioned.actorRef() == 10001L
                    && "AW项目管理员(10001)".equals(mentioned.actorDisplayName());
        }));
    }

    @Test
    void addAgentCommentSkipsMentionNotificationForInitiator() {
        WorkitemDO w = new WorkitemDO();
        w.setId(500L);
        w.setTenantId(10000L);
        w.setTitle("修复线上缺陷");
        when(workitemDao.findById(500L)).thenReturn(w);
        doAnswer(invocation -> {
            invocation.<WorkitemCommentDO>getArgument(0).setId(88001L);
            return null;
        }).when(commentDao).insert(any(WorkitemCommentDO.class));
        when(workspaceMemberDao.findByWorkspaceAndUser(10000L, 9L)).thenReturn(member(10000L, 9L));
        when(userDao.findById(9L)).thenReturn(user(9L, "李四", "lisi"));

        service.addAgentComment(500L, "@李四 请确认", List.of(9L), 10000L, 10001L, 9L);

        verify(commentMentionDao).insert(any(WorkitemCommentMentionDO.class));
        verify(eventPublisher, never()).publishEvent((Object) argThat(event ->
                event instanceof WorkitemCommentMentionedEvent mentioned && mentioned.recipientUserId() == 9L));
    }

    @Test
    void plainTextMentionIgnoresInactiveWorkspaceMember() {
        WorkitemDO w = new WorkitemDO();
        w.setId(500L);
        w.setTenantId(10000L);
        w.setTitle("修复线上缺陷");
        when(workitemDao.findById(500L)).thenReturn(w);
        doAnswer(invocation -> {
            invocation.<WorkitemCommentDO>getArgument(0).setId(88001L);
            return null;
        }).when(commentDao).insert(any(WorkitemCommentDO.class));
        WorkspaceMemberDO inactive = member(10000L, 9L);
        inactive.setStatus(1);
        when(workspaceMemberDao.listByTenant(10000L)).thenReturn(List.of(inactive));
        when(userDao.findById(9L)).thenReturn(user(9L, "李四", "lisi"));
        when(agentDao.listByTenant(10000L)).thenReturn(List.of());
        when(commentDao.listByWorkitem(500L)).thenReturn(List.of());
        when(commentMentionDao.listByWorkitem(10000L, 500L)).thenReturn(List.of());

        service.addAgentComment(500L, "@李四 请确认", 10000L, 10001L);

        verify(commentMentionDao, never()).insert(any(WorkitemCommentMentionDO.class));
        verify(eventPublisher, never()).publishEvent((Object) any(WorkitemCommentMentionedEvent.class));
    }

    @Test
    void plainTextMentionRequiresLeftBoundary() {
        WorkitemDO w = new WorkitemDO();
        w.setId(500L);
        w.setTenantId(10000L);
        when(workitemDao.findById(500L)).thenReturn(w);
        doAnswer(invocation -> {
            invocation.<WorkitemCommentDO>getArgument(0).setId(88001L);
            return null;
        }).when(commentDao).insert(any(WorkitemCommentDO.class));
        WorkspaceMemberDO member = member(10000L, 9L);
        when(workspaceMemberDao.listByTenant(10000L)).thenReturn(List.of(member));
        when(userDao.findById(9L)).thenReturn(user(9L, "李四", "lisi"));
        when(agentDao.listByTenant(10000L)).thenReturn(List.of());
        when(commentDao.listByWorkitem(500L)).thenReturn(List.of());
        when(commentMentionDao.listByWorkitem(10000L, 500L)).thenReturn(List.of());

        service.addAgentComment(500L, "mail@李四 请确认", 10000L, 10001L);

        verify(commentMentionDao, never()).insert(any(WorkitemCommentMentionDO.class));
        verify(eventPublisher, never()).publishEvent((Object) any(WorkitemCommentMentionedEvent.class));
    }

    @Test
    void addAgentCommentWorkitemNotFoundThrows() {
        when(workitemDao.findById(404L)).thenReturn(null);
        assertThrows(BizException.class,
                () -> service.addAgentComment(404L, "x", 10000L, 10001L));
        verify(commentDao, never()).insert(any());
    }

    private static WorkspaceMemberDO member(long tenantId, long userId) {
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setTenantId(tenantId);
        member.setUserId(userId);
        member.setStatus(0);
        return member;
    }

    private static UserDO user(long id, String nickname, String username) {
        UserDO user = new UserDO();
        user.setId(id);
        user.setNickname(nickname);
        user.setUsername(username);
        return user;
    }

    @Test
    void addCommentWorkitemNotFoundThrows13003() {
        when(workitemDao.findById(404L)).thenReturn(null);
        BizException ex = assertThrows(BizException.class,
                () -> service.addComment(404L, "x", 100L, 7L));
        assertEquals("13003", ex.getCode());
        verify(commentDao, never()).insert(any());
    }

    @Test
    void mentionCandidatesReturnHumansAndAgents() {
        WorkitemDO w = new WorkitemDO();
        w.setId(5L);
        w.setTenantId(100L);
        w.setCreatorId(7L);
        when(workitemDao.findById(5L)).thenReturn(w);
        when(commentDao.listByWorkitem(5L)).thenReturn(List.of());
        UserDO creator = new UserDO();
        creator.setId(7L);
        creator.setNickname("张三");
        creator.setUsername("zhangsan");
        UserDO reviewer = new UserDO();
        reviewer.setId(9L);
        reviewer.setNickname("李四");
        reviewer.setUsername("lisi");
        when(userDao.findById(7L)).thenReturn(creator);
        when(userDao.findById(9L)).thenReturn(reviewer);
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setTenantId(100L);
        member.setUserId(9L);
        member.setStatus(0);
        when(workspaceMemberDao.listByTenant(100L)).thenReturn(List.of(member));
        AgentDO agent = new AgentDO();
        agent.setId(12L);
        agent.setName("Coder-01");
        agent.setOnlineVersionId(99L);
        when(agentDao.listByTenant(100L)).thenReturn(List.of(agent));
        when(agentDao.findById(12L)).thenReturn(agent);

        List<ParticipantVO> candidates = service.getMentionCandidates(5L, 100L, null, 50);

        assertEquals(List.of("张三", "Coder-01", "李四"),
                candidates.stream().map(ParticipantVO::getName).toList());
        assertEquals(List.of("HUMAN", "AGENT", "HUMAN"),
                candidates.stream().map(ParticipantVO::getTargetType).toList());
    }

    @Test
    void listCommentsReturnsVOs() {
        WorkitemCommentDO c = new WorkitemCommentDO();
        c.setId(1L);
        c.setWorkitemId(5L);
        c.setAuthorType("HUMAN");
        c.setAuthorRef(7L);
        c.setContentMd("hi");
        when(commentDao.listByWorkitem(5L)).thenReturn(List.of(c));

        List<CommentVO> vos = service.listComments(5L);

        assertEquals(1, vos.size());
        assertEquals("hi", vos.get(0).getContentMd());
    }

    @Test
    void unifiedTimelineResolvesHumanCommentAuthorName() {
        WorkitemCommentDO c = new WorkitemCommentDO();
        c.setId(1L);
        c.setWorkitemId(5L);
        c.setAuthorType("HUMAN");
        c.setAuthorRef(7L);
        c.setContentMd("hi");
        when(commentDao.listByWorkitem(5L)).thenReturn(List.of(c));
        when(eventDao.listByWorkitem(5L)).thenReturn(List.of());
        UserDO user = new UserDO();
        user.setId(7L);
        user.setUsername("alice");
        user.setNickname("Alice");
        when(userDao.findById(7L)).thenReturn(user);

        List<TimelineItemVO> items = service.getUnifiedTimeline(5L);

        assertEquals(1, items.size());
        assertEquals("Alice(7)", items.get(0).getAuthorName());
    }

    @Test
    void unifiedTimelineReturnsNewestItemsFirst() {
        WorkitemCommentDO olderComment = new WorkitemCommentDO();
        olderComment.setId(1L);
        olderComment.setWorkitemId(5L);
        olderComment.setAuthorType("SYSTEM");
        olderComment.setContentMd("较早评论");
        olderComment.setGmtCreate(new Date(1_000L));
        WorkitemEventDO newerEvent = new WorkitemEventDO();
        newerEvent.setId(2L);
        newerEvent.setEventType("AONE_UPDATE");
        newerEvent.setActorType("SYSTEM");
        newerEvent.setGmtCreate(new Date(2_000L));
        when(commentDao.listByWorkitem(5L)).thenReturn(List.of(olderComment));
        when(eventDao.listByWorkitem(5L)).thenReturn(List.of(newerEvent));

        List<TimelineItemVO> items = service.getUnifiedTimeline(5L);

        assertEquals(List.of(2L, 1L), items.stream().map(TimelineItemVO::getId).toList());
        assertEquals("已从 Aone 工单同步更新", items.get(0).getContent());
        assertEquals("较早评论", items.get(1).getContent());
    }

    @Test
    void unifiedTimelineFormatsAssignEventWithHumanTargetDisplayName() {
        WorkitemEventDO e = new WorkitemEventDO();
        e.setId(2L);
        e.setEventType("ASSIGN");
        e.setFromVal("10001");
        e.setToVal("10000");
        e.setActorType("AGENT");
        e.setActorRef(10001L);
        e.setDetailJson("{\"fromType\":\"AGENT\",\"toType\":\"HUMAN\"}");
        when(commentDao.listByWorkitem(5L)).thenReturn(List.of());
        when(eventDao.listByWorkitem(5L)).thenReturn(List.of(e));

        AgentDO agent = new AgentDO();
        agent.setId(10001L);
        agent.setName("数字人");
        when(agentDao.findById(10001L)).thenReturn(agent);
        UserDO user = new UserDO();
        user.setId(10000L);
        user.setUsername("real-user");
        user.setNickname("真人");
        when(userDao.findById(10000L)).thenReturn(user);

        List<TimelineItemVO> items = service.getUnifiedTimeline(5L);

        assertEquals(1, items.size());
        assertEquals("数字人(10001)", items.get(0).getAuthorName());
        assertEquals("交付负责人已变更: 数字人(10001) → 真人(10000) （操作人：数字人(10001)）",
                items.get(0).getContent());
    }

    @Test
    void unifiedTimelineShowsHumanOperatorOnStatusChangeEvent() {
        WorkitemEventDO e = new WorkitemEventDO();
        e.setId(3L);
        e.setEventType("STATUS_CHANGE");
        e.setFromVal("verifying");
        e.setToVal("closed");
        e.setActorType("HUMAN");
        e.setActorRef(10000L);
        when(commentDao.listByWorkitem(5L)).thenReturn(List.of());
        when(eventDao.listByWorkitem(5L)).thenReturn(List.of(e));
        UserDO user = new UserDO();
        user.setId(10000L);
        user.setUsername("caihe");
        user.setNickname("蔡何");
        when(userDao.findById(10000L)).thenReturn(user);

        List<TimelineItemVO> items = service.getUnifiedTimeline(5L);

        assertEquals(1, items.size());
        assertEquals("工单状态已变更: verifying → closed （操作人：蔡何(10000)）", items.get(0).getContent());
    }

    @Test
    void unifiedTimelineOmitsOperatorForSystemActorAssignEvent() {
        WorkitemEventDO e = new WorkitemEventDO();
        e.setId(4L);
        e.setEventType("ASSIGN");
        e.setFromVal("40014");
        e.setToVal("40013");
        e.setActorType("SYSTEM");
        e.setActorRef(0L);
        e.setDetailJson("{\"fromType\":\"AGENT\",\"toType\":\"AGENT\"}");
        when(commentDao.listByWorkitem(5L)).thenReturn(List.of());
        when(eventDao.listByWorkitem(5L)).thenReturn(List.of(e));

        List<TimelineItemVO> items = service.getUnifiedTimeline(5L);

        assertEquals(1, items.size());
        assertEquals("交付负责人已变更: 40014 → 40013", items.get(0).getContent());
    }

    @Test
    void unifiedTimelineHidesExternalCommentTimestampAndShowsChineseAction() {
        WorkitemEventDO event = new WorkitemEventDO();
        event.setId(2L);
        event.setEventType("EXTERNAL_COMMENT_AUTHOR_CHANGE");
        event.setFromVal("126033914");
        event.setToVal("1785923204000");
        event.setActorType("SYSTEM");
        when(commentDao.listByWorkitem(5L)).thenReturn(List.of());
        when(eventDao.listByWorkitem(5L)).thenReturn(List.of(event));

        List<TimelineItemVO> items = service.getUnifiedTimeline(5L);

        assertEquals("外部评论作者身份已更新（评论 #126033914）", items.get(0).getContent());
    }
}
