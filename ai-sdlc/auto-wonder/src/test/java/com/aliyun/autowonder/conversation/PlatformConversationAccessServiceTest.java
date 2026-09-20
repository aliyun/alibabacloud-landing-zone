package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * 平台管家对话可见性闸门。
 *
 * <p>工单里最硬的一条边界是「谁创建会话谁就是不可变更的 Owner」，这个类是它在服务端的唯一落点，
 * 所以这里逐条覆盖 Owner、被分享人、陌生人、管理员四类调用者在读/写两个方向上的结果。
 */
class PlatformConversationAccessServiceTest {

    private static final long TENANT_ID = 10002L;
    private static final long CONVERSATION_ID = 22L;
    /** 刻意取 127 以上，Long 缓存会让引用比较在这种情况下「碰巧」通过。 */
    private static final long OWNER_ID = 7001L;
    private static final long GRANTEE_ID = 7002L;
    private static final long STRANGER_ID = 7003L;
    private static final long WORKSPACE_ADMIN_ID = 7004L;

    private AgentConversationDao conversationDao;
    private ConversationShareDao shareDao;
    private PlatformConversationAccessService service;

    @BeforeEach
    void setUp() {
        conversationDao = mock(AgentConversationDao.class);
        shareDao = mock(ConversationShareDao.class);
        service = new PlatformConversationAccessService(conversationDao, shareDao);
    }

    @Test
    void theOwnerCanReadAndWriteTheirOwnConversation() {
        AgentConversationDO conversation = stubOwnedConversation();

        assertSame(conversation, service.requireBodyRead(TENANT_ID, CONVERSATION_ID, OWNER_ID));
        assertSame(conversation, service.requireBodyWrite(TENANT_ID, CONVERSATION_ID, OWNER_ID));
        // Owner 自己不需要走分享表，多查一次就是每次请求白跑一趟 DB。
        verifyNoInteractions(shareDao);
    }

    @Test
    void aReadGranteeCanReadTheBodyButNotWrite() {
        stubOwnedConversation();
        when(shareDao.findActive(TENANT_ID, CONVERSATION_ID, GRANTEE_ID))
                .thenReturn(share(GRANTEE_ID, "READ"));

        AgentConversationDO read = service.requireBodyRead(TENANT_ID, CONVERSATION_ID, GRANTEE_ID);
        assertEquals(OWNER_ID, read.getOwnerUserId(), "被分享人读到的仍然是 Owner 的会话，归属不变");

        BizException denied = assertThrows(BizException.class,
                () -> service.requireBodyWrite(TENANT_ID, CONVERSATION_ID, GRANTEE_ID));
        assertEquals(ErrorCode.PLATFORM_CONVERSATION_OWNER_ONLY.getCode(), denied.getCode());
    }

    @Test
    void aStrangerGetsTheSameOpaqueErrorForReadAndWrite() {
        stubOwnedConversation();
        when(shareDao.findActive(TENANT_ID, CONVERSATION_ID, STRANGER_ID)).thenReturn(null);

        // 读和写必须回同一个码：一旦不同，任何人拿会话 id 就能探测出这条会话是否真实存在。
        assertThrowsOpaque(() -> service.requireBodyRead(TENANT_ID, CONVERSATION_ID, STRANGER_ID));
        assertThrowsOpaque(() -> service.requireBodyWrite(TENANT_ID, CONVERSATION_ID, STRANGER_ID));
    }

    @Test
    void aWorkspaceAdminIsNotExemptFromTheOwnerBoundary() {
        stubOwnedConversation();
        when(shareDao.findActive(TENANT_ID, CONVERSATION_ID, WORKSPACE_ADMIN_ID)).thenReturn(null);

        assertThrowsOpaque(() -> service.requireBodyRead(TENANT_ID, CONVERSATION_ID, WORKSPACE_ADMIN_ID));
        assertThrowsOpaque(() -> service.requireBodyWrite(TENANT_ID, CONVERSATION_ID, WORKSPACE_ADMIN_ID));
    }

    @Test
    void theGateOnlyDependsOnConversationOwnershipAndShareRecords() {
        // 一旦有人给这个闸门加上工作空间角色依赖，管理员旁路就会悄悄出现：
        // 管理员能管工作空间，不等于能替别人和他的管家聊天、更不等于能替别人确认写操作。
        List<String> dependencies = Arrays.stream(
                        PlatformConversationAccessService.class.getConstructors())
                .flatMap(constructor -> Arrays.stream(constructor.getParameterTypes()))
                .map(Class::getSimpleName)
                .toList();
        assertEquals(List.of("AgentConversationDao", "ConversationShareDao"), dependencies);
    }

    @Test
    void anUnauthenticatedCallerIsRejectedEvenOnSomebodyElsesConversation() {
        stubOwnedConversation();

        assertThrowsOpaque(() -> service.requireBodyRead(TENANT_ID, CONVERSATION_ID, null));
        assertThrowsOpaque(() -> service.requireBodyWrite(TENANT_ID, CONVERSATION_ID, null));
        // 闸门是先加载会话再判归属，但没有 userId 就绝不去查分享表：不存在「匿名被分享人」。
        verifyNoInteractions(shareDao);
    }

    @Test
    void aMissingTenantOrConversationIdIsRejectedWithoutHittingTheDatabase() {
        assertThrowsOpaque(() -> service.requireBodyRead(null, CONVERSATION_ID, OWNER_ID));
        assertThrowsOpaque(() -> service.requireBodyRead(TENANT_ID, null, OWNER_ID));
        assertThrowsOpaque(() -> service.requireBodyWrite(null, CONVERSATION_ID, OWNER_ID));
        assertThrowsOpaque(() -> service.requireBodyWrite(TENANT_ID, null, OWNER_ID));
        verifyNoInteractions(conversationDao);
        verifyNoInteractions(shareDao);
    }

    @Test
    void anUnknownConversationIsRejectedWithoutConsultingTheShareTable() {
        when(conversationDao.findById(TENANT_ID, CONVERSATION_ID)).thenReturn(null);

        assertThrowsOpaque(() -> service.requireBodyRead(TENANT_ID, CONVERSATION_ID, OWNER_ID));
        assertThrowsOpaque(() -> service.requireBodyWrite(TENANT_ID, CONVERSATION_ID, OWNER_ID));
        verifyNoInteractions(shareDao);
    }

    @Test
    void theConversationIsAlwaysLookedUpInsideTheCallersOwnTenant() {
        stubOwnedConversation();

        service.requireBodyRead(TENANT_ID, CONVERSATION_ID, OWNER_ID);

        verify(conversationDao).findById(TENANT_ID, CONVERSATION_ID);
        verifyNoMoreInteractions(conversationDao);
    }

    @Test
    void aSoftDeletedConversationDisappearsEvenForItsOwner() {
        AgentConversationDO conversation = ownedConversation();
        conversation.setDeletedAt(new Date());
        when(conversationDao.findById(TENANT_ID, CONVERSATION_ID)).thenReturn(conversation);

        assertThrowsOpaque(() -> service.requireBodyRead(TENANT_ID, CONVERSATION_ID, OWNER_ID));
        assertThrowsOpaque(() -> service.requireBodyWrite(TENANT_ID, CONVERSATION_ID, OWNER_ID));
        verifyNoInteractions(shareDao);
    }

    @Test
    void legacyChannelConversationsAreUnreachableThroughThePlatformGate() {
        // 平台管家入口不能成为绕过工单权限读别人澄清记录的后门，反之亦然。
        for (String channel : List.of("WORKITEM_CLARIFICATION", "DINGTALK",
                PlatformIntelligenceChannelSink.CHANNEL)) {
            AgentConversationDO conversation = ownedConversation();
            conversation.setChannel(channel);
            when(conversationDao.findById(TENANT_ID, CONVERSATION_ID)).thenReturn(conversation);

            assertThrowsOpaque(() -> service.requireBodyRead(TENANT_ID, CONVERSATION_ID, OWNER_ID),
                    "channel=" + channel);
            assertThrowsOpaque(() -> service.requireBodyWrite(TENANT_ID, CONVERSATION_ID, OWNER_ID),
                    "channel=" + channel);
        }
        verifyNoInteractions(shareDao);
    }

    @Test
    void aConversationWhoseOwnerColumnIsMissingHasNobodyWhoCanWrite() {
        AgentConversationDO conversation = ownedConversation();
        conversation.setOwnerUserId(null);
        when(conversationDao.findById(TENANT_ID, CONVERSATION_ID)).thenReturn(conversation);
        when(shareDao.findActive(TENANT_ID, CONVERSATION_ID, OWNER_ID)).thenReturn(null);

        assertFalse(service.isOwner(conversation, OWNER_ID));
        assertThrowsOpaque(() -> service.requireBodyWrite(TENANT_ID, CONVERSATION_ID, OWNER_ID));
    }

    @Test
    void onlyAnActiveReadOnlyGrantCountsAsAShare() {
        stubOwnedConversation();
        when(shareDao.findActive(TENANT_ID, CONVERSATION_ID, GRANTEE_ID))
                .thenReturn(share(GRANTEE_ID, "WRITE"));

        // 分享只发只读权限；出现别的权限值说明数据被改过，一律不放行。
        assertThrowsOpaque(() -> service.requireBodyRead(TENANT_ID, CONVERSATION_ID, GRANTEE_ID));
        assertThrowsOpaque(() -> service.requireBodyWrite(TENANT_ID, CONVERSATION_ID, GRANTEE_ID));
    }

    @Test
    void aRevokedShareGrantsNothing() {
        stubOwnedConversation();
        when(shareDao.findActive(TENANT_ID, CONVERSATION_ID, GRANTEE_ID)).thenReturn(null);

        assertThrowsOpaque(() -> service.requireBodyRead(TENANT_ID, CONVERSATION_ID, GRANTEE_ID));
        verify(shareDao).findActive(TENANT_ID, CONVERSATION_ID, GRANTEE_ID);
        verifyNoMoreInteractions(shareDao);
    }

    @Test
    void isOwnerComparesIdsByValueNotByReference() {
        AgentConversationDO conversation = ownedConversation();
        conversation.setOwnerUserId(Long.valueOf(OWNER_ID));

        assertTrue(service.isOwner(conversation, Long.valueOf(OWNER_ID)));
        assertFalse(service.isOwner(conversation, GRANTEE_ID));
        assertFalse(service.isOwner(conversation, null));
        assertFalse(service.isOwner(null, OWNER_ID));
    }

    private AgentConversationDO stubOwnedConversation() {
        AgentConversationDO conversation = ownedConversation();
        when(conversationDao.findById(TENANT_ID, CONVERSATION_ID)).thenReturn(conversation);
        return conversation;
    }

    private static AgentConversationDO ownedConversation() {
        AgentConversationDO conversation = new AgentConversationDO();
        conversation.setId(CONVERSATION_ID);
        conversation.setTenantId(TENANT_ID);
        conversation.setChannel(PlatformConversationChannel.CHANNEL);
        conversation.setOwnerUserId(OWNER_ID);
        conversation.setStatus("ACTIVE");
        return conversation;
    }

    private static ConversationShareDO share(long granteeUserId, String permission) {
        ConversationShareDO share = new ConversationShareDO();
        share.setTenantId(TENANT_ID);
        share.setConversationId(CONVERSATION_ID);
        share.setGranteeUserId(granteeUserId);
        share.setPermission(permission);
        share.setCreatedBy(OWNER_ID);
        return share;
    }

    /** 「不存在」和「无权访问」必须是同一个响应，连消息都不能有差别。 */
    private static void assertThrowsOpaque(Executable executable) {
        assertThrowsOpaque(executable, null);
    }

    private static void assertThrowsOpaque(Executable executable, String message) {
        BizException exception = assertThrows(BizException.class, executable, message);
        assertEquals(ErrorCode.PLATFORM_CONVERSATION_NOT_FOUND_OR_NO_PERMISSION.getCode(),
                exception.getCode(), message);
        assertEquals(ErrorCode.PLATFORM_CONVERSATION_NOT_FOUND_OR_NO_PERMISSION.getMessage(),
                exception.getMessage(), message);
    }
}
