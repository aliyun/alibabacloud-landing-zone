package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.agent.AgentDO;
import com.aliyun.autowonder.agent.AgentDao;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.dispatch.ExecutorSelector;
import com.aliyun.autowonder.workspace.WorkspaceService;
import com.aliyun.autowonder.conversation.dto.PlatformConversationVO;
import com.aliyun.autowonder.conversation.dto.PlatformShareVO;
import com.aliyun.autowonder.conversation.dto.PlatformTurnVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 平台管家对话业务入口。
 *
 * <p>可见性闸门用的是真实的 {@link PlatformConversationAccessService}（只 mock 两张表），
 * 这样每个用例都真的过一遍 Owner 校验，而不是把校验 stub 成永远放行。
 */
class PlatformConversationServiceTest {

    private static final long TENANT_ID = 10002L;
    private static final long CONVERSATION_ID = 22L;
    private static final long OWNER_ID = 7001L;
    private static final long GRANTEE_ID = 7002L;
    private static final long STRANGER_ID = 7003L;
    private static final long AGENT_ID = 40013L;
    private static final long EXECUTOR_ID = 900L;
    private static final long NEXT_EXECUTOR_ID = 901L;
    private static final long TURN_ID = 300L;
    private static final String CHANNEL_CONVERSATION_ID = "ccid-1";

    private AgentConversationDao conversationDao;
    private AgentConversationTurnDao turnDao;
    private ConversationShareDao shareDao;
    private AgentConversationService conversationService;
    private ConversationElicitationService elicitationService;
    private ConversationCommandsService commandsService;
    private ConversationRuntimePresence runtimePresence;
    private AgentDao agentDao;
    private ExecutorSelector executorSelector;
    private WorkspaceService workspaceService;
    private ConversationExecutorRouter executorRouter;
    private PlatformConversationService service;

    @BeforeEach
    void setUp() {
        conversationDao = mock(AgentConversationDao.class);
        turnDao = mock(AgentConversationTurnDao.class);
        shareDao = mock(ConversationShareDao.class);
        conversationService = mock(AgentConversationService.class);
        elicitationService = mock(ConversationElicitationService.class);
        commandsService = mock(ConversationCommandsService.class);
        runtimePresence = mock(ConversationRuntimePresence.class);
        agentDao = mock(AgentDao.class);
        executorSelector = mock(ExecutorSelector.class);
        workspaceService = mock(WorkspaceService.class);
        executorRouter = mock(ConversationExecutorRouter.class);
        when(executorRouter.select(anyLong(), anyLong(), anyLong(), nullable(Long.class)))
                .thenAnswer(invocation -> executorSelector.select(invocation.getArgument(1),
                        invocation.getArgument(3)));
        when(agentDao.findById(AGENT_ID)).thenReturn(onlineChief());
        service = new PlatformConversationService(conversationDao, turnDao, shareDao,
                new PlatformConversationAccessService(conversationDao, shareDao),
                conversationService, elicitationService, commandsService, runtimePresence,
                agentDao, workspaceService, executorRouter);
    }

    // ---------- create ----------

    @Test
    void createRecordsTheCallerAsTheImmutableOwner() {
        stubChief(onlineChief());
        when(executorSelector.select(AGENT_ID, null)).thenReturn(EXECUTOR_ID);
        when(conversationDao.insert(any(AgentConversationDO.class))).thenAnswer(invocation -> {
            AgentConversationDO row = invocation.getArgument(0);
            row.setId(CONVERSATION_ID);
            return 1;
        });

        PlatformConversationVO vo = service.create(TENANT_ID, OWNER_ID, AGENT_ID, "  发布计划  ");

        AgentConversationDO saved = captureInsertedConversation();
        assertEquals(OWNER_ID, saved.getOwnerUserId(), "创建者就是不可变更的 Owner");
        assertEquals(PlatformConversationChannel.CHANNEL, saved.getChannel());
        assertEquals(TENANT_ID, saved.getTenantId());
        assertEquals(AGENT_ID, saved.getAgentId());
        assertEquals("ACTIVE", saved.getStatus());
        assertEquals(EXECUTOR_ID, saved.getExecutorId());
        assertNotNull(saved.getChannelConversationId());
        assertEquals("发布计划", saved.getTitle(), "标题去掉首尾空白后落库");
        assertEquals("USER", saved.getTitleSource());
        assertEquals(CONVERSATION_ID, vo.getId());
        assertTrue(vo.isOwner());
        assertEquals(OWNER_ID, vo.getOwnerUserId());
    }

    @Test
    void everyNewConversationGetsItsOwnChannelConversationId() {
        stubChief(onlineChief());
        when(conversationDao.insert(any(AgentConversationDO.class))).thenReturn(1);

        service.create(TENANT_ID, OWNER_ID, AGENT_ID, null);
        service.create(TENANT_ID, OWNER_ID, AGENT_ID, null);

        ArgumentCaptor<AgentConversationDO> captor = ArgumentCaptor.forClass(AgentConversationDO.class);
        verify(conversationDao, times(2)).insert(captor.capture());
        List<AgentConversationDO> rows = captor.getAllValues();
        assertNotEquals(rows.get(0).getChannelConversationId(), rows.get(1).getChannelConversationId(),
                "两条会话共用一个渠道会话 id 会让轮次串到一起");
    }

    @Test
    void createWithoutATitleLeavesItToBeDerivedFromTheFirstMessage() {
        stubChief(onlineChief());
        when(conversationDao.insert(any(AgentConversationDO.class))).thenReturn(1);

        for (String blank : Arrays.asList(null, "", "   ")) {
            service.create(TENANT_ID, OWNER_ID, AGENT_ID, blank);
        }

        ArgumentCaptor<AgentConversationDO> captor = ArgumentCaptor.forClass(AgentConversationDO.class);
        verify(conversationDao, times(3)).insert(captor.capture());
        for (AgentConversationDO row : captor.getAllValues()) {
            assertNull(row.getTitle());
            assertNull(row.getTitleSource(), "没有用户标题就不能标成 USER，否则自动标题永远不会生效");
        }
    }

    @Test
    void createRejectsAMissingOwner() {
        for (Long owner : Arrays.asList(null, 0L, -1L)) {
            BizException exception = assertThrows(BizException.class,
                    () -> service.create(TENANT_ID, owner, AGENT_ID, null), "owner=" + owner);
            assertEquals(ErrorCode.UNAUTHORIZED.getCode(), exception.getCode());
        }
        verifyNoInteractions(conversationDao);
    }

    @Test
    void createRejectsAnUnknownCrossTenantOrDeletedChief() {
        AgentDO crossTenant = onlineChief();
        crossTenant.setTenantId(TENANT_ID + 1);
        AgentDO deleted = onlineChief();
        deleted.setIsDeleted(1);

        // agentId 为空时根本不去查库：查了也只是把 NPE 换成一次无意义的往返。
        assertAgentNotFound(() -> service.create(TENANT_ID, OWNER_ID, null, null));
        verifyNoInteractions(agentDao);

        when(agentDao.findById(AGENT_ID)).thenReturn(null);
        assertAgentNotFound(() -> service.create(TENANT_ID, OWNER_ID, AGENT_ID, null));
        when(agentDao.findById(AGENT_ID)).thenReturn(crossTenant);
        assertAgentNotFound(() -> service.create(TENANT_ID, OWNER_ID, AGENT_ID, null));
        when(agentDao.findById(AGENT_ID)).thenReturn(deleted);
        assertAgentNotFound(() -> service.create(TENANT_ID, OWNER_ID, AGENT_ID, null));

        verify(conversationDao, never()).insert(any(AgentConversationDO.class));
    }

    @Test
    void createRefusesToOpenAConversationBeforeTheChiefIsReady() {
        AgentDO chief = onlineChief();
        chief.setOnlineVersionId(null);
        stubChief(chief);

        // 工单要求入口在就绪前不接受新会话：建一个永远回不了话的空会话比直接拒绝更糟。
        BizException exception = assertThrows(BizException.class,
                () -> service.create(TENANT_ID, OWNER_ID, AGENT_ID, null));
        assertEquals(ErrorCode.PLATFORM_CONVERSATION_NOT_READY.getCode(), exception.getCode());
        verify(conversationDao, never()).insert(any(AgentConversationDO.class));
    }

    // ---------- list ----------

    @Test
    void listIsScopedToTheCallingOwnerInTheQuery() {
        when(conversationDao.listPlatformByOwner(eq(TENANT_ID), eq(OWNER_ID), any(), any(),
                anyInt(), anyInt())).thenReturn(List.of(ownedConversation()));

        List<PlatformConversationVO> page = service.list(TENANT_ID, OWNER_ID, null, null, null, null);

        assertEquals(1, page.size());
        assertTrue(page.get(0).isOwner());
        // 归属条件必须下沉到 SQL，靠调用方自觉过滤等于没有过滤。
        verify(conversationDao).listPlatformByOwner(TENANT_ID, OWNER_ID, null, null, 50, 0);
    }

    @Test
    void listNormalizesPagingAndKeywordBeforeQuerying() {
        service.list(TENANT_ID, OWNER_ID, Boolean.TRUE, "   ", 1000, 3);
        verify(conversationDao).listPlatformByOwner(TENANT_ID, OWNER_ID, Boolean.TRUE, null, 200, 400);

        service.list(TENANT_ID, OWNER_ID, Boolean.FALSE, "  发布计划  ", 20, 0);
        verify(conversationDao).listPlatformByOwner(TENANT_ID, OWNER_ID, Boolean.FALSE, "发布计划", 20, 0);

        service.list(TENANT_ID, OWNER_ID, null, "x", 0, -5);
        verify(conversationDao).listPlatformByOwner(TENANT_ID, OWNER_ID, null, "x", 50, 0);
    }

    @Test
    void anEmptyListDoesNotProbeTheRuntimeAtAll() {
        service.list(TENANT_ID, OWNER_ID, null, null, null, null);

        // 列表页逐会话探能力会把 N 次 Redis 往返摊到首屏上。
        verifyNoInteractions(runtimePresence);
    }

    @Test
    void aPageOfConversationsLooksTheChiefUpOnlyOnce() {
        AgentConversationDO second = ownedConversation();
        second.setId(CONVERSATION_ID + 1);
        second.setChannelConversationId("ccid-2");
        when(conversationDao.listPlatformByOwner(eq(TENANT_ID), eq(OWNER_ID), any(), any(),
                anyInt(), anyInt())).thenReturn(List.of(ownedConversation(), second));
        when(agentDao.findById(AGENT_ID)).thenReturn(onlineChief());

        List<PlatformConversationVO> page = service.list(TENANT_ID, OWNER_ID, null, null, null, null);

        assertEquals(2, page.size());
        assertEquals("平台管家", page.get(0).getAgentName());
        assertEquals("平台管家", page.get(1).getAgentName());
        // 一页里几乎都是同一个 Chief，逐行查 agentDao 是白跑 N 次。
        verify(agentDao, times(1)).findById(AGENT_ID);
    }

    @Test
    void anUnknownChiefIsMemoizedTooSoADeletedAgentIsNotRequeriedPerRow() {
        AgentConversationDO second = ownedConversation();
        second.setId(CONVERSATION_ID + 1);
        when(conversationDao.listPlatformByOwner(eq(TENANT_ID), eq(OWNER_ID), any(), any(),
                anyInt(), anyInt())).thenReturn(List.of(ownedConversation(), second));
        when(agentDao.findById(AGENT_ID)).thenReturn(null);

        List<PlatformConversationVO> page = service.list(TENANT_ID, OWNER_ID, null, null, null, null);

        assertNull(page.get(0).getAgentName());
        assertNull(page.get(1).getAgentName());
        verify(agentDao, times(1)).findById(AGENT_ID);
    }

    // ---------- get ----------

    @Test
    void getReturnsTheBodyTurnsAndPendingCardsToTheOwner() {
        stubConversation(ownedConversation());
        when(turnDao.listTurnsByConversation(TENANT_ID, CONVERSATION_ID))
                .thenReturn(List.of(turn(301L, "IN", "在吗"), turn(302L, "OUT", "在")));
        AgentConversationElicitationDO elicitation = new AgentConversationElicitationDO();
        elicitation.setRequestId("req-1");
        elicitation.setTurnId(302L);
        elicitation.setMode("SINGLE");
        elicitation.setMessage("要发布到哪个环境？");
        elicitation.setSchemaJson("{}");
        elicitation.setStatus("PENDING");
        when(elicitationService.listPending(TENANT_ID, CONVERSATION_ID)).thenReturn(List.of(elicitation));
        when(shareDao.listActive(TENANT_ID, CONVERSATION_ID))
                .thenReturn(List.of(activeShare(GRANTEE_ID)));

        PlatformConversationVO vo = service.get(TENANT_ID, CONVERSATION_ID, OWNER_ID);

        List<PlatformTurnVO> turns = vo.getTurns();
        assertEquals(2, turns.size());
        assertEquals("IN", turns.get(0).getDirection());
        assertEquals("在吗", turns.get(0).getContent());
        assertEquals(1, vo.getPendingElicitations().size());
        assertEquals("req-1", vo.getPendingElicitations().get(0).getRequestId());
        assertEquals("要发布到哪个环境？", vo.getPendingElicitations().get(0).getMessage());
        assertEquals(List.of(GRANTEE_ID), vo.getShares().stream()
                .map(PlatformShareVO::getGranteeUserId).toList());
        verify(commandsService).snapshot(TENANT_ID, CONVERSATION_ID);
    }

    @Test
    void getHidesTheShareListFromAReadGrantee() {
        stubConversation(ownedConversation());
        when(shareDao.findActive(TENANT_ID, CONVERSATION_ID, GRANTEE_ID))
                .thenReturn(activeShare(GRANTEE_ID));

        PlatformConversationVO vo = service.get(TENANT_ID, CONVERSATION_ID, GRANTEE_ID);

        assertFalse(vo.isOwner());
        assertNull(vo.getShares(), "分享名单本身就是隐私，被分享人不能看到还有谁被分享了");
        verify(shareDao, never()).listActive(TENANT_ID, CONVERSATION_ID);
    }

    @Test
    void getReportsTheRunningTurnSoTheUiCanRestoreStreaming() {
        stubConversation(ownedConversation());
        when(turnDao.findProcessingInbound(TENANT_ID, CONVERSATION_ID))
                .thenReturn(turn(TURN_ID, "IN", "在吗"));

        PlatformConversationVO vo = service.get(TENANT_ID, CONVERSATION_ID, OWNER_ID);

        assertEquals(TURN_ID, vo.getProcessingTurnId());
        assertEquals("PROCESSING", vo.getProcessingStatus());
        verify(turnDao, never()).findNextQueuedInbound(TENANT_ID, CONVERSATION_ID);
    }

    @Test
    void getFallsBackToTheQueuedTurnWhenNothingIsProcessing() {
        stubConversation(ownedConversation());
        when(turnDao.findProcessingInbound(TENANT_ID, CONVERSATION_ID)).thenReturn(null);
        AgentConversationTurnDO queued = turn(TURN_ID, "IN", "排队中");
        queued.setStatus("QUEUED");
        when(turnDao.findNextQueuedInbound(TENANT_ID, CONVERSATION_ID)).thenReturn(queued);

        PlatformConversationVO vo = service.get(TENANT_ID, CONVERSATION_ID, OWNER_ID);

        assertEquals(TURN_ID, vo.getProcessingTurnId());
        assertEquals("QUEUED", vo.getProcessingStatus());
    }

    @Test
    void getLeavesTheProcessingFieldsEmptyWhenNoTurnIsInFlight() {
        stubConversation(ownedConversation());

        PlatformConversationVO vo = service.get(TENANT_ID, CONVERSATION_ID, OWNER_ID);

        assertNull(vo.getProcessingTurnId());
        assertNull(vo.getProcessingStatus());
    }

    @Test
    void getIsRefusedToAStranger() {
        stubConversation(ownedConversation());
        when(shareDao.findActive(TENANT_ID, CONVERSATION_ID, STRANGER_ID)).thenReturn(null);

        assertThrowsOpaque(() -> service.get(TENANT_ID, CONVERSATION_ID, STRANGER_ID));
        verifyNoInteractions(turnDao);
    }

    @Test
    void verifyReadableUsesTheSameGateAsGet() {
        stubConversation(ownedConversation());
        when(shareDao.findActive(TENANT_ID, CONVERSATION_ID, STRANGER_ID)).thenReturn(null);

        service.verifyReadable(TENANT_ID, CONVERSATION_ID, OWNER_ID);
        assertThrowsOpaque(() -> service.verifyReadable(TENANT_ID, CONVERSATION_ID, STRANGER_ID));
    }

    // ---------- patch / rename / archive / delete ----------

    @Test
    void renameMarksTheTitleAsUserOwnedAndPreservesTheArchiveState() {
        AgentConversationDO conversation = ownedConversation();
        Date archivedAt = new Date(1_700_000_000_000L);
        conversation.setArchivedAt(archivedAt);
        stubConversation(conversation);

        PlatformConversationVO vo = service.rename(TENANT_ID, CONVERSATION_ID, OWNER_ID, "  新标题  ");

        verify(conversationDao).updatePlatformMetadata(TENANT_ID, CONVERSATION_ID, OWNER_ID,
                "新标题", "USER", archivedAt);
        assertEquals("新标题", vo.getTitle());
        assertEquals("USER", vo.getTitleSource());
    }

    @Test
    void renameRejectsABlankOrOverlongTitle() {
        stubConversation(ownedConversation());

        for (String title : Arrays.asList(null, "", "   ")) {
            BizException exception = assertThrows(BizException.class,
                    () -> service.rename(TENANT_ID, CONVERSATION_ID, OWNER_ID, title), "title=" + title);
            assertEquals(ErrorCode.PLATFORM_CONVERSATION_TITLE_INVALID.getCode(), exception.getCode());
        }
        BizException tooLong = assertThrows(BizException.class,
                () -> service.rename(TENANT_ID, CONVERSATION_ID, OWNER_ID, "长".repeat(256)));
        assertEquals(ErrorCode.PLATFORM_CONVERSATION_TITLE_INVALID.getCode(), tooLong.getCode());

        verify(conversationDao, never()).updatePlatformMetadata(anyLong(), anyLong(), anyLong(),
                anyString(), anyString(), any());
    }

    @Test
    void renameAndArchiveAreRefusedToAReadGrantee() {
        stubConversation(ownedConversation());
        when(shareDao.findActive(TENANT_ID, CONVERSATION_ID, GRANTEE_ID))
                .thenReturn(activeShare(GRANTEE_ID));

        assertOwnerOnly(() -> service.rename(TENANT_ID, CONVERSATION_ID, GRANTEE_ID, "x"));
        assertOwnerOnly(() -> service.archive(TENANT_ID, CONVERSATION_ID, GRANTEE_ID, true));
        verify(conversationDao, never()).updatePlatformMetadata(anyLong(), anyLong(), anyLong(),
                any(), any(), any());
    }

    @Test
    void archiveStampsTheTimeAndKeepsTheExistingTitle() {
        AgentConversationDO conversation = ownedConversation();
        conversation.setTitle("发布计划");
        conversation.setTitleSource("USER");
        stubConversation(conversation);

        PlatformConversationVO vo = service.archive(TENANT_ID, CONVERSATION_ID, OWNER_ID, true);

        // 这条 update 同时写 title / title_source / archived_at，漏传就会把用户的标题抹掉。
        verify(conversationDao).updatePlatformMetadata(eq(TENANT_ID), eq(CONVERSATION_ID), eq(OWNER_ID),
                isNull(), eq("USER"), any(Date.class));
        assertNotNull(vo.getArchivedAt());
    }

    @Test
    void unarchiveClearsTheStampWithoutTouchingTheTitle() {
        AgentConversationDO conversation = ownedConversation();
        conversation.setArchivedAt(new Date());
        conversation.setTitleSource("AUTO");
        stubConversation(conversation);

        PlatformConversationVO vo = service.archive(TENANT_ID, CONVERSATION_ID, OWNER_ID, false);

        verify(conversationDao).updatePlatformMetadata(TENANT_ID, CONVERSATION_ID, OWNER_ID,
                null, "AUTO", null);
        assertNull(vo.getArchivedAt());
    }

    @Test
    void patchAppliesRenameAndArchiveInASingleUpdate() {
        AgentConversationDO conversation = ownedConversation();
        conversation.setTitle("旧标题");
        conversation.setTitleSource("AUTO");
        stubConversation(conversation);

        PlatformConversationVO vo = service.patch(TENANT_ID, CONVERSATION_ID, OWNER_ID, "  新标题  ", true);

        // 一次 PATCH 只能落一条 update：拆成 rename + archive 两次调用时，
        // 第二条失败会留下「改了名没归档」的半截状态，而客户端看到的是失败响应。
        ArgumentCaptor<Date> archivedAt = ArgumentCaptor.forClass(Date.class);
        verify(conversationDao, times(1)).updatePlatformMetadata(eq(TENANT_ID), eq(CONVERSATION_ID),
                eq(OWNER_ID), eq("新标题"), eq("USER"), archivedAt.capture());
        assertNotNull(archivedAt.getValue());
        assertEquals("新标题", vo.getTitle());
        assertEquals("USER", vo.getTitleSource());
        assertNotNull(vo.getArchivedAt());
    }

    @Test
    void patchKeepsTheArchiveStampWhenOnlyTheTitleIsSupplied() {
        AgentConversationDO conversation = ownedConversation();
        Date archivedAt = new Date(1_700_000_000_000L);
        conversation.setArchivedAt(archivedAt);
        conversation.setTitleSource("AUTO");
        stubConversation(conversation);

        PlatformConversationVO vo = service.patch(TENANT_ID, CONVERSATION_ID, OWNER_ID, "新标题", null);

        verify(conversationDao).updatePlatformMetadata(TENANT_ID, CONVERSATION_ID, OWNER_ID,
                "新标题", "USER", archivedAt);
        assertEquals(archivedAt, vo.getArchivedAt(), "不传 archived 就不能动归档状态");
    }

    @Test
    void patchKeepsTheTitleWhenOnlyTheArchiveFlagIsSupplied() {
        AgentConversationDO conversation = ownedConversation();
        conversation.setTitle("发布计划");
        conversation.setTitleSource("USER");
        stubConversation(conversation);

        PlatformConversationVO vo = service.patch(TENANT_ID, CONVERSATION_ID, OWNER_ID, null, true);

        verify(conversationDao).updatePlatformMetadata(eq(TENANT_ID), eq(CONVERSATION_ID), eq(OWNER_ID),
                isNull(), eq("USER"), any(Date.class));
        assertEquals("发布计划", vo.getTitle(), "title 传 null 表示本次不改名，不能把标题抹掉");
        assertNotNull(vo.getArchivedAt());
    }

    @Test
    void patchUnarchivesWithoutTouchingTheTitle() {
        AgentConversationDO conversation = ownedConversation();
        conversation.setArchivedAt(new Date());
        conversation.setTitle("发布计划");
        conversation.setTitleSource("USER");
        stubConversation(conversation);

        PlatformConversationVO vo = service.patch(TENANT_ID, CONVERSATION_ID, OWNER_ID, null, false);

        verify(conversationDao).updatePlatformMetadata(TENANT_ID, CONVERSATION_ID, OWNER_ID,
                null, "USER", null);
        assertNull(vo.getArchivedAt());
        assertEquals("发布计划", vo.getTitle());
    }

    @Test
    void anEmptyPatchDoesNotWriteAnything() {
        AgentConversationDO conversation = ownedConversation();
        conversation.setTitle("发布计划");
        conversation.setTitleSource("USER");
        stubConversation(conversation);

        PlatformConversationVO vo = service.patch(TENANT_ID, CONVERSATION_ID, OWNER_ID, null, null);

        // 两个字段都没传就什么都不该写：白写一次会把 version 与 gmt_modified 顶上去。
        verify(conversationDao, never()).updatePlatformMetadata(anyLong(), anyLong(), anyLong(),
                any(), any(), any());
        assertEquals("发布计划", vo.getTitle());
        assertNull(vo.getArchivedAt());
    }

    @Test
    void patchRejectsAnInvalidTitleBeforeWritingTheArchiveFlag() {
        stubConversation(ownedConversation());

        for (String title : Arrays.asList("", "   ")) {
            BizException exception = assertThrows(BizException.class,
                    () -> service.patch(TENANT_ID, CONVERSATION_ID, OWNER_ID, title, true),
                    "title=" + title);
            assertEquals(ErrorCode.PLATFORM_CONVERSATION_TITLE_INVALID.getCode(), exception.getCode());
        }
        BizException tooLong = assertThrows(BizException.class,
                () -> service.patch(TENANT_ID, CONVERSATION_ID, OWNER_ID, "长".repeat(256), true));
        assertEquals(ErrorCode.PLATFORM_CONVERSATION_TITLE_INVALID.getCode(), tooLong.getCode());

        // 归档标记本身合法也不能先落库：改名失败时整条 PATCH 必须一起失败。
        verify(conversationDao, never()).updatePlatformMetadata(anyLong(), anyLong(), anyLong(),
                any(), any(), any());
    }

    @Test
    void patchIsRefusedToAReadGrantee() {
        stubConversation(ownedConversation());
        when(shareDao.findActive(TENANT_ID, CONVERSATION_ID, GRANTEE_ID))
                .thenReturn(activeShare(GRANTEE_ID));

        assertOwnerOnly(() -> service.patch(TENANT_ID, CONVERSATION_ID, GRANTEE_ID, "x", true));
        verify(conversationDao, never()).updatePlatformMetadata(anyLong(), anyLong(), anyLong(),
                any(), any(), any());
    }

    @Test
    void deleteSoftDeletesWithTheRecordedOwner() {
        stubConversation(ownedConversation());

        service.delete(TENANT_ID, CONVERSATION_ID, OWNER_ID);

        verify(conversationDao).markPlatformDeleted(eq(TENANT_ID), eq(CONVERSATION_ID), eq(OWNER_ID),
                any(Date.class));
    }

    @Test
    void deleteIsRefusedToAReadGrantee() {
        stubConversation(ownedConversation());
        when(shareDao.findActive(TENANT_ID, CONVERSATION_ID, GRANTEE_ID))
                .thenReturn(activeShare(GRANTEE_ID));

        assertOwnerOnly(() -> service.delete(TENANT_ID, CONVERSATION_ID, GRANTEE_ID));
        verify(conversationDao, never()).markPlatformDeleted(anyLong(), anyLong(), anyLong(), any());
    }

    // ---------- share ----------

    @Test
    void shareGrantsReadOnlyAccessToAFellowWorkspaceMember() {
        stubConversation(ownedConversation());
        when(workspaceService.activeAccessLevel(TENANT_ID, GRANTEE_ID))
                .thenReturn(WorkspaceAccessLevel.READ_ONLY);
        when(shareDao.listActive(TENANT_ID, CONVERSATION_ID))
                .thenReturn(List.of(activeShare(GRANTEE_ID)));

        List<PlatformShareVO> shares = service.share(TENANT_ID, CONVERSATION_ID, OWNER_ID, GRANTEE_ID);

        // 被分享人必须是同工作空间的在职成员，否则等于把会话内容泄露到工作空间外。
        verify(workspaceService).activeAccessLevel(TENANT_ID, GRANTEE_ID);
        verify(shareDao).upsertReadShare(TENANT_ID, CONVERSATION_ID, GRANTEE_ID, OWNER_ID);
        assertEquals(List.of(GRANTEE_ID), shares.stream()
                .map(PlatformShareVO::getGranteeUserId).toList());
    }

    @Test
    void shareRejectsSharingWithYourselfOrAnInvalidGrantee() {
        stubConversation(ownedConversation());

        for (Long grantee : Arrays.asList(null, 0L, -1L, OWNER_ID)) {
            BizException exception = assertThrows(BizException.class,
                    () -> service.share(TENANT_ID, CONVERSATION_ID, OWNER_ID, grantee),
                    "grantee=" + grantee);
            assertEquals(ErrorCode.PLATFORM_CONVERSATION_SHARE_INVALID.getCode(), exception.getCode());
        }
        verifyNoInteractions(workspaceService);
        verify(shareDao, never()).upsertReadShare(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void shareRefusesAGranteeWhoIsNotInThisWorkspace() {
        stubConversation(ownedConversation());
        when(workspaceService.activeAccessLevel(TENANT_ID, GRANTEE_ID))
                .thenThrow(new BizException(ErrorCode.WORKSPACE_NOT_MEMBER));

        BizException exception = assertThrows(BizException.class,
                () -> service.share(TENANT_ID, CONVERSATION_ID, OWNER_ID, GRANTEE_ID));
        assertEquals(ErrorCode.WORKSPACE_NOT_MEMBER.getCode(), exception.getCode());
        verify(shareDao, never()).upsertReadShare(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void revokeShareRecordsTheOwnerAsTheOperator() {
        stubConversation(ownedConversation());

        service.revokeShare(TENANT_ID, CONVERSATION_ID, OWNER_ID, GRANTEE_ID);

        verify(shareDao).revoke(eq(TENANT_ID), eq(CONVERSATION_ID), eq(GRANTEE_ID), eq(OWNER_ID),
                any(Date.class));
    }

    @Test
    void listingSharesIsOwnerOnly() {
        stubConversation(ownedConversation());
        when(shareDao.listActive(TENANT_ID, CONVERSATION_ID))
                .thenReturn(List.of(activeShare(GRANTEE_ID)));
        // 被分享人确实持有生效中的只读分享，所以拿到的是「Owner 专属」而不是「不存在」。
        when(shareDao.findActive(TENANT_ID, CONVERSATION_ID, GRANTEE_ID))
                .thenReturn(activeShare(GRANTEE_ID));

        assertEquals(List.of(GRANTEE_ID), service.listShares(TENANT_ID, CONVERSATION_ID, OWNER_ID)
                .stream().map(PlatformShareVO::getGranteeUserId).toList());
        assertOwnerOnly(() -> service.listShares(TENANT_ID, CONVERSATION_ID, GRANTEE_ID));
    }

    // ---------- submitTurn ----------

    @Test
    void submitTurnHandsTheMessageToThePipelineWithAPlatformExternalId() {
        AgentConversationDO conversation = ownedConversation();
        stubConversation(conversation);
        when(executorSelector.select(AGENT_ID, EXECUTOR_ID)).thenReturn(EXECUTOR_ID);

        service.submitTurn(TENANT_ID, CONVERSATION_ID, OWNER_ID, "在吗", "client-1");

        // 幂等键自带会话身份：uk_external_msg 只按租户唯一，不带会话就会跨会话撞键。
        verify(conversationService).submitTurn(TENANT_ID, AGENT_ID,
                PlatformConversationChannel.CHANNEL, CHANNEL_CONVERSATION_ID, "在吗",
                "web-platform:" + CONVERSATION_ID + ":client-1");
        verify(conversationDao, never()).updateExecutor(anyLong(), anyLong(), anyLong());
    }

    @Test
    void submitTurnGeneratesAnExternalMessageIdWhenTheClientOmitsOne() {
        stubConversation(ownedConversation());
        when(executorSelector.select(AGENT_ID, EXECUTOR_ID)).thenReturn(EXECUTOR_ID);

        service.submitTurn(TENANT_ID, CONVERSATION_ID, OWNER_ID, "在吗", null);
        service.submitTurn(TENANT_ID, CONVERSATION_ID, OWNER_ID, "在吗", "  ");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(conversationService, times(2)).submitTurn(eq(TENANT_ID), eq(AGENT_ID),
                eq(PlatformConversationChannel.CHANNEL), eq(CHANNEL_CONVERSATION_ID), eq("在吗"),
                captor.capture());
        String scopedPrefix = "web-platform:" + CONVERSATION_ID + ":";
        for (String externalMsgId : captor.getAllValues()) {
            assertTrue(externalMsgId.startsWith(scopedPrefix), externalMsgId);
            assertTrue(externalMsgId.length() > scopedPrefix.length());
        }
        assertNotEquals(captor.getAllValues().get(0), captor.getAllValues().get(1),
                "两次发送共用一个外部消息 id 会被幂等去重吃掉一条");
    }

    @Test
    void theSameClientMessageIdInTwoConversationsDoesNotCollide() {
        long otherConversationId = CONVERSATION_ID + 1;
        AgentConversationDO other = ownedConversation();
        other.setId(otherConversationId);
        other.setChannelConversationId("ccid-2");
        stubConversation(ownedConversation());
        when(conversationDao.findById(TENANT_ID, otherConversationId)).thenReturn(other);
        when(executorSelector.select(AGENT_ID, EXECUTOR_ID)).thenReturn(EXECUTOR_ID);

        service.submitTurn(TENANT_ID, CONVERSATION_ID, OWNER_ID, "在吗", "client-1");
        service.submitTurn(TENANT_ID, otherConversationId, OWNER_ID, "在吗", "client-1");

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(conversationService, times(2)).submitTurn(eq(TENANT_ID), eq(AGENT_ID),
                eq(PlatformConversationChannel.CHANNEL), anyString(), eq("在吗"), captor.capture());
        assertNotEquals(captor.getAllValues().get(0), captor.getAllValues().get(1),
                "两个会话复用同一个 clientMessageId 时，后到的消息会被幂等去重静默丢弃");
    }

    @Test
    void submitTurnIsRefusedOnAnArchivedConversation() {
        AgentConversationDO conversation = ownedConversation();
        conversation.setArchivedAt(new Date());
        stubConversation(conversation);

        BizException exception = assertThrows(BizException.class,
                () -> service.submitTurn(TENANT_ID, CONVERSATION_ID, OWNER_ID, "在吗", null));
        assertEquals(ErrorCode.PLATFORM_CONVERSATION_ARCHIVED.getCode(), exception.getCode());
        verifyNoInteractions(conversationService);
    }

    @Test
    void submitTurnIsRefusedOnANonActiveConversation() {
        AgentConversationDO conversation = ownedConversation();
        conversation.setStatus("CLOSED");
        stubConversation(conversation);

        BizException exception = assertThrows(BizException.class,
                () -> service.submitTurn(TENANT_ID, CONVERSATION_ID, OWNER_ID, "在吗", null));
        assertEquals(ErrorCode.PLATFORM_CONVERSATION_DELETED.getCode(), exception.getCode());
        verifyNoInteractions(conversationService);
    }

    @Test
    void submitTurnRejectsBlankContent() {
        stubConversation(ownedConversation());

        for (String content : Arrays.asList(null, "", "   ")) {
            BizException exception = assertThrows(BizException.class,
                    () -> service.submitTurn(TENANT_ID, CONVERSATION_ID, OWNER_ID, content, null),
                    "content=" + content);
            assertEquals(ErrorCode.PARAM_INVALID.getCode(), exception.getCode());
        }
        verifyNoInteractions(conversationService);
    }

    @Test
    void submitTurnFailsLoudlyWhenNoRuntimeIsAvailable() {
        stubConversation(ownedConversation());
        when(executorSelector.select(AGENT_ID, EXECUTOR_ID)).thenReturn(null);

        // 静默丢消息比报错更糟：用户会一直等一个永远不会来的回复。
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> service.submitTurn(TENANT_ID, CONVERSATION_ID, OWNER_ID, "在吗", null));
        assertEquals("RUNTIME_OFFLINE", exception.getMessage());
        verifyNoInteractions(conversationService);
    }

    @Test
    void submitTurnRebindsTheExecutorWhenTheBoundOneIsGone() {
        stubConversation(ownedConversation());
        when(executorSelector.select(AGENT_ID, EXECUTOR_ID)).thenReturn(NEXT_EXECUTOR_ID);

        service.submitTurn(TENANT_ID, CONVERSATION_ID, OWNER_ID, "在吗", null);

        verify(executorRouter).select(TENANT_ID, AGENT_ID, 88L, EXECUTOR_ID);
        verify(conversationDao).updateExecutor(TENANT_ID, CONVERSATION_ID, NEXT_EXECUTOR_ID);
    }

    @Test
    void submitTurnBindsAnExecutorToAConversationThatNeverHadOne() {
        AgentConversationDO conversation = ownedConversation();
        conversation.setExecutorId(null);
        stubConversation(conversation);
        when(executorSelector.select(AGENT_ID, null)).thenReturn(EXECUTOR_ID);

        service.submitTurn(TENANT_ID, CONVERSATION_ID, OWNER_ID, "在吗", null);

        verify(conversationDao).updateExecutor(TENANT_ID, CONVERSATION_ID, EXECUTOR_ID);
    }

    @Test
    void theFirstMessageBecomesTheConversationTitle() {
        stubConversation(ownedConversation());
        when(executorSelector.select(AGENT_ID, EXECUTOR_ID)).thenReturn(EXECUTOR_ID);

        service.submitTurn(TENANT_ID, CONVERSATION_ID, OWNER_ID, "  帮我看看\n\n  发布计划  ", null);

        verify(conversationDao).updatePlatformMetadata(TENANT_ID, CONVERSATION_ID, OWNER_ID,
                "帮我看看 发布计划", "AUTO", null);
    }

    @Test
    void aLongFirstMessageIsTruncatedIntoAReadableTitle() {
        stubConversation(ownedConversation());
        when(executorSelector.select(AGENT_ID, EXECUTOR_ID)).thenReturn(EXECUTOR_ID);
        String content = "一二三四五六七八九十".repeat(6);

        service.submitTurn(TENANT_ID, CONVERSATION_ID, OWNER_ID, content, null);

        String expected = content.substring(0, 30) + "…";
        verify(conversationDao).updatePlatformMetadata(TENANT_ID, CONVERSATION_ID, OWNER_ID,
                expected, "AUTO", null);
    }

    @Test
    void aUserChosenTitleIsNeverOverwrittenByTheAutoTitle() {
        AgentConversationDO conversation = ownedConversation();
        conversation.setTitle("我自己起的名字");
        conversation.setTitleSource("USER");
        stubConversation(conversation);
        when(executorSelector.select(AGENT_ID, EXECUTOR_ID)).thenReturn(EXECUTOR_ID);

        service.submitTurn(TENANT_ID, CONVERSATION_ID, OWNER_ID, "在吗", null);

        verify(conversationDao, never()).updatePlatformMetadata(anyLong(), anyLong(), anyLong(),
                any(), any(), any());
    }

    @Test
    void anExistingAutoTitleIsNotRecomputedOnEveryTurn() {
        AgentConversationDO conversation = ownedConversation();
        conversation.setTitle("帮我看看发布计划");
        conversation.setTitleSource("AUTO");
        stubConversation(conversation);
        when(executorSelector.select(AGENT_ID, EXECUTOR_ID)).thenReturn(EXECUTOR_ID);

        service.submitTurn(TENANT_ID, CONVERSATION_ID, OWNER_ID, "第二条消息", null);

        verify(conversationDao, never()).updatePlatformMetadata(anyLong(), anyLong(), anyLong(),
                any(), any(), any());
    }

    @Test
    void submitTurnIsRefusedToAReadGrantee() {
        stubConversation(ownedConversation());
        when(shareDao.findActive(TENANT_ID, CONVERSATION_ID, GRANTEE_ID))
                .thenReturn(activeShare(GRANTEE_ID));

        assertOwnerOnly(() -> service.submitTurn(TENANT_ID, CONVERSATION_ID, GRANTEE_ID, "在吗", null));
        verifyNoInteractions(conversationService);
    }

    // ---------- cancel / elicitation ----------

    @Test
    void cancelTurnAsksThePipelineToStopTheOwnersTurn() {
        stubConversation(ownedConversation());
        when(turnDao.findByConversationTurn(TENANT_ID, CONVERSATION_ID, TURN_ID))
                .thenReturn(turn(TURN_ID, "IN", "在吗"));

        service.cancelTurn(TENANT_ID, CONVERSATION_ID, OWNER_ID, TURN_ID);

        verify(conversationService).requestTurnCancel(TENANT_ID, CONVERSATION_ID, TURN_ID);
    }

    @Test
    void cancelTurnIsRefusedWhenTheTurnDoesNotBelongToTheConversation() {
        stubConversation(ownedConversation());
        when(turnDao.findByConversationTurn(TENANT_ID, CONVERSATION_ID, TURN_ID)).thenReturn(null);

        BizException exception = assertThrows(BizException.class,
                () -> service.cancelTurn(TENANT_ID, CONVERSATION_ID, OWNER_ID, TURN_ID));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), exception.getCode());
        verifyNoInteractions(conversationService);
    }

    @Test
    void cancelTurnIsRefusedToAReadGrantee() {
        stubConversation(ownedConversation());
        when(shareDao.findActive(TENANT_ID, CONVERSATION_ID, GRANTEE_ID))
                .thenReturn(activeShare(GRANTEE_ID));

        assertOwnerOnly(() -> service.cancelTurn(TENANT_ID, CONVERSATION_ID, GRANTEE_ID, TURN_ID));
        verifyNoInteractions(turnDao);
    }

    @Test
    void replyElicitationIsOwnerOnly() {
        stubConversation(ownedConversation());
        when(shareDao.findActive(TENANT_ID, CONVERSATION_ID, GRANTEE_ID))
                .thenReturn(activeShare(GRANTEE_ID));

        service.replyElicitation(TENANT_ID, CONVERSATION_ID, OWNER_ID, "req-1", "ACCEPT", "{}");
        verify(elicitationService).reply(TENANT_ID, CONVERSATION_ID, "req-1", "ACCEPT", "{}");

        // 回答问题卡片会驱动挂起的 Agent 继续本轮，是写操作，被分享人不能代答。
        assertOwnerOnly(() -> service.replyElicitation(TENANT_ID, CONVERSATION_ID, GRANTEE_ID,
                "req-1", "ACCEPT", "{}"));
    }

    // ---------- capability advertisement ----------

    @Test
    void capabilitiesAreOnlyAdvertisedWhenTheExecutorIsOnline() {
        AgentConversationDO conversation = ownedConversation();
        stubConversation(conversation);
        when(runtimePresence.isExecutorOnline(EXECUTOR_ID)).thenReturn(false);

        PlatformConversationVO vo = service.get(TENANT_ID, CONVERSATION_ID, OWNER_ID);

        assertFalse(vo.isExecutorOnline());
        assertFalse(vo.isStreamingSupported());
        assertFalse(vo.isCancelSupported());
        assertFalse(vo.isAcpInteractionSupported());
        assertFalse(vo.isAttachmentManifestSupported());
        assertFalse(vo.isArtifactOutputSupported());
        assertFalse(vo.isActionPlanSupported());
        // 执行器都不在线，逐项探能力是白跑六次 Redis。
        verify(runtimePresence).isExecutorOnline(EXECUTOR_ID);
        verify(runtimePresence, never()).supportsProtocolFeature(anyLong(), anyString());
    }

    @Test
    void eachProtocolCapabilityIsProbedIndependently() {
        stubConversation(ownedConversation());
        when(runtimePresence.isExecutorOnline(EXECUTOR_ID)).thenReturn(true);
        when(runtimePresence.supportsProtocolFeature(EXECUTOR_ID,
                ConversationProtocolFeatures.ACTION_PLAN_V1)).thenReturn(true);

        PlatformConversationVO vo = service.get(TENANT_ID, CONVERSATION_ID, OWNER_ID);

        assertTrue(vo.isExecutorOnline());
        // 老执行器没有行动计划通路时，前端必须把写操作入口整个隐藏，而不是让用户点了再报错。
        assertTrue(vo.isActionPlanSupported());
        assertFalse(vo.isStreamingSupported());
        assertFalse(vo.isCancelSupported());
        assertFalse(vo.isAcpInteractionSupported());
        assertFalse(vo.isAttachmentManifestSupported());
        assertFalse(vo.isArtifactOutputSupported());
        verify(runtimePresence).supportsProtocolFeature(EXECUTOR_ID,
                ConversationProtocolFeatures.TURN_EVENT);
        verify(runtimePresence).supportsProtocolFeature(EXECUTOR_ID,
                ConversationProtocolFeatures.TURN_CANCEL);
        verify(runtimePresence).supportsProtocolFeature(EXECUTOR_ID,
                ConversationProtocolFeatures.ACP_INTERACTION);
        verify(runtimePresence).supportsProtocolFeature(EXECUTOR_ID,
                ConversationProtocolFeatures.ATTACHMENT_MANIFEST_V1);
        verify(runtimePresence).supportsProtocolFeature(EXECUTOR_ID,
                ConversationProtocolFeatures.ARTIFACT_OUTPUT_V1);
        verify(runtimePresence).supportsProtocolFeature(EXECUTOR_ID,
                ConversationProtocolFeatures.ACTION_PLAN_V1);
    }

    @Test
    void aConversationWithoutAnExecutorReportsNoCapabilities() {
        AgentConversationDO conversation = ownedConversation();
        conversation.setExecutorId(null);
        stubConversation(conversation);

        PlatformConversationVO vo = service.get(TENANT_ID, CONVERSATION_ID, OWNER_ID);

        assertFalse(vo.isExecutorOnline());
        assertFalse(vo.isActionPlanSupported());
        verifyNoInteractions(runtimePresence);
    }

    @Test
    void theVoCarriesTheAgentNameForTheConversationList() {
        AgentConversationDO conversation = ownedConversation();
        stubConversation(conversation);
        AgentDO chief = onlineChief();
        chief.setName("平台管家");
        when(agentDao.findById(AGENT_ID)).thenReturn(chief);

        PlatformConversationVO vo = service.get(TENANT_ID, CONVERSATION_ID, OWNER_ID);

        assertEquals("平台管家", vo.getAgentName());
        assertEquals(CHANNEL_CONVERSATION_ID, vo.getChannelConversationId());
        assertEquals("ACTIVE", vo.getStatus());
    }

    // ---------- fixtures ----------

    private void stubChief(AgentDO chief) {
        when(agentDao.findById(AGENT_ID)).thenReturn(chief);
    }

    private static AgentDO onlineChief() {
        AgentDO chief = new AgentDO();
        chief.setId(AGENT_ID);
        chief.setTenantId(TENANT_ID);
        chief.setOnlineVersionId(88L);
        chief.setIsDeleted(0);
        chief.setName("平台管家");
        return chief;
    }

    private void stubConversation(AgentConversationDO conversation) {
        when(conversationDao.findById(TENANT_ID, CONVERSATION_ID)).thenReturn(conversation);
    }

    private static AgentConversationDO ownedConversation() {
        AgentConversationDO conversation = new AgentConversationDO();
        conversation.setId(CONVERSATION_ID);
        conversation.setTenantId(TENANT_ID);
        conversation.setChannel(PlatformConversationChannel.CHANNEL);
        conversation.setChannelConversationId(CHANNEL_CONVERSATION_ID);
        conversation.setOwnerUserId(OWNER_ID);
        conversation.setAgentId(AGENT_ID);
        conversation.setExecutorId(EXECUTOR_ID);
        conversation.setStatus("ACTIVE");
        return conversation;
    }

    private static AgentConversationTurnDO turn(long id, String direction, String content) {
        AgentConversationTurnDO turn = new AgentConversationTurnDO();
        turn.setId(id);
        turn.setTenantId(TENANT_ID);
        turn.setConversationId(CONVERSATION_ID);
        turn.setDirection(direction);
        turn.setContent(content);
        turn.setStatus("PROCESSING");
        turn.setGmtCreate(new Date());
        return turn;
    }

    private static ConversationShareDO activeShare(long granteeUserId) {
        ConversationShareDO share = new ConversationShareDO();
        share.setTenantId(TENANT_ID);
        share.setConversationId(CONVERSATION_ID);
        share.setGranteeUserId(granteeUserId);
        share.setPermission("READ");
        share.setCreatedBy(OWNER_ID);
        share.setGmtCreate(new Date());
        return share;
    }

    private AgentConversationDO captureInsertedConversation() {
        ArgumentCaptor<AgentConversationDO> captor = ArgumentCaptor.forClass(AgentConversationDO.class);
        verify(conversationDao).insert(captor.capture());
        return captor.getValue();
    }

    private static void assertAgentNotFound(Executable executable) {
        BizException exception = assertThrows(BizException.class, executable);
        assertEquals(ErrorCode.AGENT_NOT_FOUND.getCode(), exception.getCode());
    }

    private static void assertOwnerOnly(Executable executable) {
        BizException exception = assertThrows(BizException.class, executable);
        assertEquals(ErrorCode.PLATFORM_CONVERSATION_OWNER_ONLY.getCode(), exception.getCode());
    }

    private static void assertThrowsOpaque(Executable executable) {
        BizException exception = assertThrows(BizException.class, executable);
        assertEquals(ErrorCode.PLATFORM_CONVERSATION_NOT_FOUND_OR_NO_PERMISSION.getCode(),
                exception.getCode());
    }
}
