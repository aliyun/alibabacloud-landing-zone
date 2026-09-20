package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.conversation.dto.ElicitationReplyRequest;
import com.aliyun.autowonder.conversation.dto.PlatformConversationPatchRequest;
import com.aliyun.autowonder.conversation.dto.PlatformConversationRequest;
import com.aliyun.autowonder.conversation.dto.PlatformConversationVO;
import com.aliyun.autowonder.conversation.dto.PlatformShareRequest;
import com.aliyun.autowonder.conversation.dto.PlatformShareVO;
import com.aliyun.autowonder.conversation.dto.PlatformTurnRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 平台管家对话入口。
 *
 * <p>重点盯 PATCH：改名与归档必须由 service 的单个事务方法一次做完。
 * 入口自己拼两次调用时，前一个已提交、后一个抛异常，会话就停在半截状态。
 *
 * <p>其余端点逐个扫一遍：入口只做三件事 —— 取当前身份、抬写操作闸门、原样转发参数。
 * 归属判定一律下沉给 service，所以这里盯的是「身份有没有被换掉」「参数有没有被改写」
 * 「读闸门有没有先于取数据执行」，而不是业务规则本身。
 */
class PlatformConversationControllerTest {

    private static final long WORKSPACE_ID = 10002L;
    private static final long USER_ID = 7001L;
    private static final long CONVERSATION_ID = 22L;
    private static final long TURN_ID = 55L;
    private static final long GRANTEE_ID = 8002L;
    private static final long AGENT_ID = 40013L;

    private PlatformConversationService service;
    private ConversationTurnEventService turnEventService;
    private ConversationCommandsService commandsService;
    private PlatformConversationController controller;

    @BeforeEach
    void setUp() {
        service = mock(PlatformConversationService.class);
        turnEventService = mock(ConversationTurnEventService.class);
        commandsService = mock(ConversationCommandsService.class);
        controller = new PlatformConversationController(service, turnEventService, commandsService);
        AutoWonderContext.get().setCurrentWorkspaceId(WORKSPACE_ID);
        AutoWonderContext.get().setUserId(USER_ID);
    }

    @AfterEach
    void clearContext() {
        AutoWonderContext.destroy();
    }

    @Test
    void patchDelegatesBothFieldsToTheSingleTransactionalServiceCall() {
        PlatformConversationVO vo = PlatformConversationVO.builder().id(CONVERSATION_ID).build();
        when(service.patch(WORKSPACE_ID, CONVERSATION_ID, USER_ID, "新标题", true)).thenReturn(vo);
        PlatformConversationPatchRequest request = new PlatformConversationPatchRequest();
        request.setTitle("新标题");
        request.setArchived(true);

        Result<PlatformConversationVO> result = controller.patch(CONVERSATION_ID, request);

        assertSame(vo, result.getData());
        verify(service).patch(WORKSPACE_ID, CONVERSATION_ID, USER_ID, "新标题", true);
        // 入口不得再自己拼调用：两次独立事务会留下「改了名没归档」的半截状态。
        verify(service, never()).rename(anyLong(), anyLong(), anyLong(), anyString());
        verify(service, never()).archive(anyLong(), anyLong(), anyLong(), anyBoolean());
        verify(service, never()).get(anyLong(), anyLong(), anyLong());
    }

    @Test
    void patchPassesAnAbsentFieldThroughAsNullSoTheServiceCanTellNoChangeFromClear() {
        PlatformConversationPatchRequest titleOnly = new PlatformConversationPatchRequest();
        titleOnly.setTitle("新标题");
        controller.patch(CONVERSATION_ID, titleOnly);
        verify(service).patch(WORKSPACE_ID, CONVERSATION_ID, USER_ID, "新标题", null);

        PlatformConversationPatchRequest archiveOnly = new PlatformConversationPatchRequest();
        archiveOnly.setArchived(false);
        controller.patch(CONVERSATION_ID, archiveOnly);
        verify(service).patch(WORKSPACE_ID, CONVERSATION_ID, USER_ID, null, false);

        controller.patch(CONVERSATION_ID, new PlatformConversationPatchRequest());
        verify(service).patch(WORKSPACE_ID, CONVERSATION_ID, USER_ID, null, null);
    }

    /** 改名与归档都是写操作，工作空间管理员也不例外，必须要求 READ_WRITE。 */
    @Test
    void patchStillRequiresReadWriteAccess() throws NoSuchMethodException {
        RequireWorkspaceAccess access = PlatformConversationController.class
                .getMethod("patch", Long.class, PlatformConversationPatchRequest.class)
                .getAnnotation(RequireWorkspaceAccess.class);

        assertNotNull(access);
        assertEquals(WorkspaceAccessLevel.READ_WRITE, access.value());
    }

    /** 类级闸门是 READ_ONLY：读取放行给被分享人，写操作逐方法抬到 READ_WRITE。 */
    @Test
    void theClassLevelGateStaysReadOnlySoGranteesCanStillRead() {
        RequireWorkspaceAccess access = PlatformConversationController.class
                .getAnnotation(RequireWorkspaceAccess.class);

        assertNotNull(access);
        assertEquals(WorkspaceAccessLevel.READ_ONLY, access.value());
        verify(service, never()).patch(anyLong(), anyLong(), anyLong(), any(), any());
    }

    /** 列表把调用者身份一并下沉给 service，归属过滤不依赖调用方自觉。 */
    @Test
    void listForwardsTheCallerIdentityAndEveryFilterUnchanged() {
        PlatformConversationVO vo = PlatformConversationVO.builder().id(CONVERSATION_ID).build();
        when(service.list(WORKSPACE_ID, USER_ID, true, "管家", 20, 2)).thenReturn(List.of(vo));

        Result<List<PlatformConversationVO>> result = controller.list(true, "管家", 20, 2);

        assertSame(vo, result.getData().get(0));
        verify(service).list(WORKSPACE_ID, USER_ID, true, "管家", 20, 2);
    }

    /** 过滤项缺省时原样传 null：分页与关键字的默认值只能在 service 里定，入口不自己造。 */
    @Test
    void listPassesAbsentFiltersThroughAsNull() {
        controller.list(null, null, null, null);

        verify(service).list(WORKSPACE_ID, USER_ID, null, null, null, null);
    }

    @Test
    void createDelegatesAgentAndTitleUnderTheCallerIdentity() {
        PlatformConversationVO vo = PlatformConversationVO.builder().id(CONVERSATION_ID).build();
        when(service.create(WORKSPACE_ID, USER_ID, AGENT_ID, "平台管家")).thenReturn(vo);
        PlatformConversationRequest request = new PlatformConversationRequest();
        request.setAgentId(AGENT_ID);
        request.setTitle("平台管家");

        assertSame(vo, controller.create(request).getData());
        verify(service).create(WORKSPACE_ID, USER_ID, AGENT_ID, "平台管家");
    }

    /** 标题可缺省，由服务端按首条消息生成；入口不得替它填一个空串。 */
    @Test
    void createPassesAnAbsentTitleThroughAsNull() {
        PlatformConversationRequest request = new PlatformConversationRequest();
        request.setAgentId(AGENT_ID);

        controller.create(request);

        verify(service).create(WORKSPACE_ID, USER_ID, AGENT_ID, null);
    }

    @Test
    void getReadsTheConversationScopedToTheCaller() {
        PlatformConversationVO vo = PlatformConversationVO.builder().id(CONVERSATION_ID).build();
        when(service.get(WORKSPACE_ID, CONVERSATION_ID, USER_ID)).thenReturn(vo);

        assertSame(vo, controller.get(CONVERSATION_ID).getData());
        verify(service).get(WORKSPACE_ID, CONVERSATION_ID, USER_ID);
    }

    @Test
    void deleteReturnsAnEmptySuccessfulBody() {
        Result<Void> result = controller.delete(CONVERSATION_ID);

        assertTrue(result.isSuccess());
        assertNull(result.getData());
        verify(service).delete(WORKSPACE_ID, CONVERSATION_ID, USER_ID);
    }

    /** 分享名单是被分享人也要能读的，所以它不抬闸门，只吃类级 READ_ONLY。 */
    @Test
    void listSharesStaysUnderTheClassLevelReadOnlyGate() throws NoSuchMethodException {
        assertNull(PlatformConversationController.class
                .getMethod("listShares", Long.class)
                .getAnnotation(RequireWorkspaceAccess.class));

        PlatformShareVO share = PlatformShareVO.builder().granteeUserId(GRANTEE_ID).build();
        when(service.listShares(WORKSPACE_ID, CONVERSATION_ID, USER_ID)).thenReturn(List.of(share));

        assertEquals(GRANTEE_ID, controller.listShares(CONVERSATION_ID).getData().get(0).getGranteeUserId());
    }

    @Test
    void shareAndRevokeShareDelegateTheGrantee() {
        PlatformShareVO share = PlatformShareVO.builder().granteeUserId(GRANTEE_ID).build();
        when(service.share(WORKSPACE_ID, CONVERSATION_ID, USER_ID, GRANTEE_ID)).thenReturn(List.of(share));
        when(service.revokeShare(WORKSPACE_ID, CONVERSATION_ID, USER_ID, GRANTEE_ID)).thenReturn(List.of());
        PlatformShareRequest request = new PlatformShareRequest();
        request.setGranteeUserId(GRANTEE_ID);

        assertSame(share, controller.share(CONVERSATION_ID, request).getData().get(0));
        assertTrue(controller.revokeShare(CONVERSATION_ID, GRANTEE_ID).getData().isEmpty());
    }

    /** 时间线读取先过归属闸门再取事件；分页上限固定在入口，免得一次把整条会话拉全。 */
    @Test
    void eventsVerifiesReadabilityBeforeReturningTheTimeline() {
        AgentConversationTurnEventDO event = new AgentConversationTurnEventDO();
        event.setId(9L);
        when(turnEventService.listEventsAfter(WORKSPACE_ID, CONVERSATION_ID, 0L, 200))
                .thenReturn(List.of(event));

        List<AgentConversationTurnEventDO> events = controller.events(CONVERSATION_ID, 0L).getData();

        InOrder order = inOrder(service, turnEventService);
        order.verify(service).verifyReadable(WORKSPACE_ID, CONVERSATION_ID, USER_ID);
        order.verify(turnEventService).listEventsAfter(WORKSPACE_ID, CONVERSATION_ID, 0L, 200);
        assertEquals(9L, events.get(0).getId());

        // 轮询游标原样透传：入口若把它重置成 0，客户端会反复重读同一批事件。
        controller.events(CONVERSATION_ID, 57L);
        verify(turnEventService).listEventsAfter(WORKSPACE_ID, CONVERSATION_ID, 57L, 200);
    }

    /** 读不到会话的人不能拿到别人的时间线：闸门抛出后不得再碰事件查询。 */
    @Test
    void eventsStopsBeforeTheTimelineWhenTheCallerCannotReadTheConversation() {
        doThrow(new BizException(ErrorCode.PLATFORM_CONVERSATION_NOT_FOUND_OR_NO_PERMISSION))
                .when(service).verifyReadable(WORKSPACE_ID, CONVERSATION_ID, USER_ID);

        assertThrows(BizException.class, () -> controller.events(CONVERSATION_ID, 0L));
        verify(turnEventService, never()).listEventsAfter(anyLong(), anyLong(), anyLong(), anyInt());
    }

    @Test
    void turnEventsVerifiesReadabilityThenReturnsThatTurnOnly() {
        AgentConversationTurnEventDO event = new AgentConversationTurnEventDO();
        event.setTurnId(TURN_ID);
        when(turnEventService.listEventsByTurn(WORKSPACE_ID, CONVERSATION_ID, TURN_ID))
                .thenReturn(List.of(event));

        List<AgentConversationTurnEventDO> events =
                controller.turnEvents(CONVERSATION_ID, TURN_ID).getData();

        InOrder order = inOrder(service, turnEventService);
        order.verify(service).verifyReadable(WORKSPACE_ID, CONVERSATION_ID, USER_ID);
        order.verify(turnEventService).listEventsByTurn(WORKSPACE_ID, CONVERSATION_ID, TURN_ID);
        assertEquals(TURN_ID, events.get(0).getTurnId());
    }

    @Test
    void turnEventsStopsBeforeTheTimelineWhenTheCallerCannotReadTheConversation() {
        doThrow(new BizException(ErrorCode.PLATFORM_CONVERSATION_NOT_FOUND_OR_NO_PERMISSION))
                .when(service).verifyReadable(WORKSPACE_ID, CONVERSATION_ID, USER_ID);

        assertThrows(BizException.class, () -> controller.turnEvents(CONVERSATION_ID, TURN_ID));
        verify(turnEventService, never()).listEventsByTurn(anyLong(), anyLong(), anyLong());
    }

    /** 幂等键必须原样送到 service：刷新重发时服务端靠它去重，入口改了就会重复入队。 */
    @Test
    void submitTurnForwardsContentAndTheClientIdempotencyKey() {
        PlatformTurnRequest request = new PlatformTurnRequest();
        request.setContent("帮我看一下今天的发布计划");
        request.setClientMessageId("cli-msg-0001");

        assertTrue(controller.submitTurn(CONVERSATION_ID, request).isSuccess());
        verify(service).submitTurn(WORKSPACE_ID, CONVERSATION_ID, USER_ID,
                "帮我看一下今天的发布计划", "cli-msg-0001");
    }

    @Test
    void cancelTurnDelegatesTheTurnIdUnderTheCallerIdentity() {
        assertTrue(controller.cancelTurn(CONVERSATION_ID, TURN_ID).isSuccess());
        verify(service).cancelTurn(WORKSPACE_ID, CONVERSATION_ID, USER_ID, TURN_ID);
    }

    /** 答案 JSON 原样透传：结构由 Agent 的 requestedSchema 决定，服务端不解释也不重排。 */
    @Test
    void replyElicitationPassesActionAndRawAnswerThroughUnchanged() {
        ElicitationReplyRequest request = new ElicitationReplyRequest();
        request.setAction("accept");
        request.setContent("{\"q0\":\"A\",\"q1\":[1,2]}");

        controller.replyElicitation(CONVERSATION_ID, "req-1", request);

        verify(service).replyElicitation(WORKSPACE_ID, CONVERSATION_ID, USER_ID,
                "req-1", "accept", "{\"q0\":\"A\",\"q1\":[1,2]}");
    }

    /** 用户点「跳过」时没有答案也要照发，decline 的语义由 service 判，入口不替它补内容。 */
    @Test
    void replyElicitationForwardsADeclineWithoutAnAnswer() {
        ElicitationReplyRequest request = new ElicitationReplyRequest();
        request.setAction("decline");

        controller.replyElicitation(CONVERSATION_ID, "req-2", request);

        verify(service).replyElicitation(WORKSPACE_ID, CONVERSATION_ID, USER_ID,
                "req-2", "decline", null);
    }

    /** 命令探针是只读能力：先过归属闸门，且不得顺带改动会话状态。 */
    @Test
    void refreshCommandsIsAReadOnlyProbeBehindTheOwnershipGate() {
        controller.refreshCommands(CONVERSATION_ID);

        InOrder order = inOrder(service, commandsService);
        order.verify(service).verifyReadable(WORKSPACE_ID, CONVERSATION_ID, USER_ID);
        order.verify(commandsService).refresh(WORKSPACE_ID, CONVERSATION_ID);
        verify(service, never()).submitTurn(anyLong(), anyLong(), anyLong(), anyString(), anyString());
        verify(service, never()).cancelTurn(anyLong(), anyLong(), anyLong(), anyLong());
        verify(service, never()).delete(anyLong(), anyLong(), anyLong());
        verify(service, never()).replyElicitation(anyLong(), anyLong(), anyLong(), anyString(),
                anyString(), anyString());
    }

    @Test
    void refreshCommandsDoesNotProbeWhenTheCallerCannotReadTheConversation() {
        doThrow(new BizException(ErrorCode.PLATFORM_CONVERSATION_NOT_FOUND_OR_NO_PERMISSION))
                .when(service).verifyReadable(WORKSPACE_ID, CONVERSATION_ID, USER_ID);

        assertThrows(BizException.class, () -> controller.refreshCommands(CONVERSATION_ID));
        verify(commandsService, never()).refresh(anyLong(), anyLong());
    }

    /** 每个写端点都要自己抬到 READ_WRITE：类级闸门是 READ_ONLY，被分享人只该能读。 */
    @Test
    void everyMutatingEndpointElevatesTheGateToReadWrite() throws NoSuchMethodException {
        assertReadWrite("create", PlatformConversationRequest.class);
        assertReadWrite("patch", Long.class, PlatformConversationPatchRequest.class);
        assertReadWrite("delete", Long.class);
        assertReadWrite("share", Long.class, PlatformShareRequest.class);
        assertReadWrite("revokeShare", Long.class, Long.class);
        assertReadWrite("submitTurn", Long.class, PlatformTurnRequest.class);
        assertReadWrite("cancelTurn", Long.class, Long.class);
        assertReadWrite("replyElicitation", Long.class, String.class, ElicitationReplyRequest.class);
    }

    /** 未认证的请求必须在入口就被拒：带着 null 身份落到 service，归属过滤就形同虚设。 */
    @Test
    void aCallerWithoutAWorkspaceIsRejectedBeforeAnyServiceCall() {
        AutoWonderContext.get().setCurrentWorkspaceId(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> controller.list(null, null, null, null));

        assertEquals("not authenticated", error.getMessage());
        verifyNoInteractions(service, turnEventService, commandsService);
    }

    @Test
    void aCallerWithoutAUserIdIsRejectedBeforeAnyServiceCall() {
        AutoWonderContext.get().setUserId(null);

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> controller.get(CONVERSATION_ID));

        assertEquals("not authenticated", error.getMessage());
        verifyNoInteractions(service, turnEventService, commandsService);
    }

    private static void assertReadWrite(String method, Class<?>... parameterTypes)
            throws NoSuchMethodException {
        RequireWorkspaceAccess access = PlatformConversationController.class
                .getMethod(method, parameterTypes)
                .getAnnotation(RequireWorkspaceAccess.class);

        assertNotNull(access, method + " 缺少工作空间闸门");
        assertEquals(WorkspaceAccessLevel.READ_WRITE, access.value(),
                method + " 是写操作，必须抬到 READ_WRITE");
    }
}
