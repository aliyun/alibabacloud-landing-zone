package com.aliyun.autowonder.notification;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.notification.dto.NotificationPageVO;
import com.aliyun.autowonder.notification.dto.NotificationVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 锁死通知中心页面的列表契约：items 与 total 必须同源同筛选条件，租户与用户取自
 * AutoWonderContext 而非常量，分页参数越界要被收敛，删除要带上下文归属。
 */
class NotificationControllerTest {

    private static final long WORKSPACE_ID = 10002L;
    private static final long USER_ID = 10018L;

    private NotificationDao notificationDao;
    private NotifyService notifyService;
    private NotificationController controller;

    @BeforeEach
    void setUp() {
        notificationDao = mock(NotificationDao.class);
        notifyService = mock(NotifyService.class);
        controller = new NotificationController(notificationDao, mock(NotifyPrefDao.class), notifyService, mock(com.aliyun.autowonder.im.PlatformImChannelConfigService.class));
        AutoWonderContext.get().setCurrentWorkspaceId(WORKSPACE_ID);
        AutoWonderContext.get().setUserId(USER_ID);
    }

    @AfterEach
    void cleanup() {
        AutoWonderContext.destroy();
    }

    @Test
    void listReturnsItemsAndTotalScopedToTheCurrentContext() {
        when(notificationDao.listByRecipient(WORKSPACE_ID, USER_ID, null, 0, 10))
                .thenReturn(List.of(notification(1L, "UNREAD"), notification(2L, "READ")));
        when(notificationDao.countByRecipient(WORKSPACE_ID, USER_ID, null)).thenReturn(45);

        Result<NotificationPageVO> result = controller.list(null, 1, 10);

        assertTrue(result.isSuccess());
        NotificationPageVO page = result.getData();
        assertNotNull(page);
        assertEquals(2, page.getItems().size());
        // total 可以远大于当前页条数，前端分页器靠它渲染总页数
        assertEquals(45L, page.getTotal());
        assertEquals(1L, page.getItems().get(0).getId());
        verify(notificationDao).listByRecipient(WORKSPACE_ID, USER_ID, null, 0, 10);
        verify(notificationDao).countByRecipient(WORKSPACE_ID, USER_ID, null);
    }

    @Test
    void listAppliesTheSameStatusFilterToItemsAndTotal() {
        when(notificationDao.listByRecipient(WORKSPACE_ID, USER_ID, "UNREAD", 0, 10)).thenReturn(List.of());
        when(notificationDao.countByRecipient(WORKSPACE_ID, USER_ID, "UNREAD")).thenReturn(7);

        NotificationPageVO page = controller.list("UNREAD", 1, 10).getData();

        assertEquals(7L, page.getTotal());
        assertTrue(page.getItems().isEmpty());
        verify(notificationDao).countByRecipient(WORKSPACE_ID, USER_ID, "UNREAD");
    }

    @Test
    void listMapsEveryVoFieldTheDrawerNeeds() {
        NotificationDO record = notification(9L, "UNREAD");
        record.setType("WORKITEM_ASSIGNED");
        record.setTitle("有新工单指派给你");
        record.setContent("Fix login bug");
        record.setLink("/workitems/42");
        record.setRefType("WORKITEM");
        record.setRefId(42L);
        when(notificationDao.listByRecipient(WORKSPACE_ID, USER_ID, null, 0, 10)).thenReturn(List.of(record));
        when(notificationDao.countByRecipient(WORKSPACE_ID, USER_ID, null)).thenReturn(1);

        NotificationVO vo = controller.list(null, 1, 10).getData().getItems().get(0);

        assertEquals(9L, vo.getId());
        assertEquals("WORKITEM_ASSIGNED", vo.getType());
        assertEquals("有新工单指派给你", vo.getTitle());
        assertEquals("Fix login bug", vo.getContent());
        assertEquals("/workitems/42", vo.getLink());
        assertEquals("WORKITEM", vo.getRefType());
        assertEquals(42L, vo.getRefId());
        assertEquals("UNREAD", vo.getStatus());
        assertNotNull(vo.getGmtCreate());
    }

    @Test
    void listComputesOffsetFromTheRequestedPage() {
        when(notificationDao.listByRecipient(WORKSPACE_ID, USER_ID, null, 20, 10)).thenReturn(List.of());
        when(notificationDao.countByRecipient(WORKSPACE_ID, USER_ID, null)).thenReturn(25);

        controller.list(null, 3, 10);

        verify(notificationDao).listByRecipient(WORKSPACE_ID, USER_ID, null, 20, 10);
    }

    @Test
    void listClampsOutOfRangePageAndSize() {
        when(notificationDao.listByRecipient(WORKSPACE_ID, USER_ID, null, 0, 100)).thenReturn(List.of());
        when(notificationDao.countByRecipient(WORKSPACE_ID, USER_ID, null)).thenReturn(0);

        // page=0 会算出负 offset，size=1000 会一次拉爆内存，两者都必须在控制器收敛
        controller.list(null, 0, 1000);

        verify(notificationDao).listByRecipient(WORKSPACE_ID, USER_ID, null, 0, 100);
    }

    @Test
    void listWithoutWorkspaceContextQueriesOnlyPlatformScope() {
        AutoWonderContext.get().setCurrentWorkspaceId(null);
        when(notificationDao.listByRecipient(null, USER_ID, null, 0, 10)).thenReturn(List.of());
        when(notificationDao.countByRecipient(null, USER_ID, null)).thenReturn(0);

        controller.list(null, 1, 10);

        // 无空间时不下发跨租户查询，只按本人可见的平台范围查询（DAO 内部过滤 scope）。
        verify(notificationDao).listByRecipient(null, USER_ID, null, 0, 10);
        verify(notificationDao).countByRecipient(null, USER_ID, null);
    }

    @Test
    void listWithoutUserContextRejects() {
        AutoWonderContext.get().setUserId(null);

        BizException ex = assertThrows(BizException.class,
                () -> controller.list(null, 1, 10));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
    }

    @Test
    void deleteDelegatesToServiceWithCurrentContext() {
        controller.delete(77L);

        verify(notifyService).delete(77L, WORKSPACE_ID, USER_ID);
    }

    @Test
    void deleteWithoutWorkspaceContextStillScopesToTheRecipient() {
        AutoWonderContext.get().setCurrentWorkspaceId(null);

        controller.delete(77L);

        // 归属条件始终包含 recipient_id；tenantId 为空时只能命中本人可见的平台通知。
        verify(notifyService).delete(77L, null, USER_ID);
    }

    @Test
    void detailReturnsTheNotificationOwnedByTheCurrentRecipient() {
        NotificationDO record = notification(31L, "UNREAD");
        record.setTitle("工单有新评论");
        record.setContent("请查看评论");
        when(notificationDao.findByIdAndRecipient(31L, WORKSPACE_ID, USER_ID)).thenReturn(record);

        NotificationVO vo = controller.detail(31L).getData();

        assertEquals(31L, vo.getId());
        assertEquals("工单有新评论", vo.getTitle());
        assertEquals("请查看评论", vo.getContent());
        verify(notificationDao).findByIdAndRecipient(31L, WORKSPACE_ID, USER_ID);
    }

    @Test
    void detailMapsRelatedLinksFromPersistedJson() {
        NotificationDO record = notification(31L, "UNREAD");
        record.setRelatedLinksJson(
                "[{\"kind\":\"SUBMITTED\",\"title\":\"新工单\",\"url\":\"/workitems/55?workspaceId=10002\"}]");
        when(notificationDao.findByIdAndRecipient(31L, WORKSPACE_ID, USER_ID)).thenReturn(record);

        NotificationVO vo = controller.detail(31L).getData();

        assertEquals(List.of(new NotificationLink("SUBMITTED", "新工单", "/workitems/55?workspaceId=10002")),
                vo.getRelatedLinks());
    }

    @Test
    void listMapsRelatedLinksForDuplicateNotifications() {
        NotificationDO record = notification(32L, "UNREAD");
        record.setType("DUPLICATE_INTENT");
        record.setRelatedLinksJson("[{\"kind\":\"SUBMITTED\",\"title\":\"新\",\"url\":\"/workitems/55?workspaceId=10002\"},"
                + "{\"kind\":\"DUPLICATE\",\"title\":\"旧\",\"url\":\"/workitems/2?workspaceId=10002\"}]");
        when(notificationDao.listByRecipient(WORKSPACE_ID, USER_ID, null, 0, 10)).thenReturn(List.of(record));
        when(notificationDao.countByRecipient(WORKSPACE_ID, USER_ID, null)).thenReturn(1);

        NotificationVO vo = controller.list(null, 1, 10).getData().getItems().get(0);

        assertEquals(2, vo.getRelatedLinks().size());
        assertEquals("DUPLICATE", vo.getRelatedLinks().get(1).kind());
    }

    @Test
    void legacyNotificationsWithoutASnapshotExposeNoLinks() {
        for (String stored : new String[]{null, "   "}) {
            NotificationDO record = notification(33L, "UNREAD");
            record.setRelatedLinksJson(stored);
            when(notificationDao.findByIdAndRecipient(33L, WORKSPACE_ID, USER_ID)).thenReturn(record);

            assertNull(controller.detail(33L).getData().getRelatedLinks());
        }
    }

    @Test
    void detailHidesDeletedForeignOrUnknownNotificationsBehindNotFound() {
        when(notificationDao.findByIdAndRecipient(404L, WORKSPACE_ID, USER_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> controller.detail(404L));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void detailRejectsNonPositiveIdWithoutQueryingTheDao() {
        BizException ex = assertThrows(BizException.class, () -> controller.detail(0L));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
        verify(notificationDao, never()).findByIdAndRecipient(anyLong(), anyLong(), anyLong());
    }

    @Test
    void detailWithoutUserContextRejects() {
        AutoWonderContext.get().setUserId(null);

        BizException ex = assertThrows(BizException.class, () -> controller.detail(1L));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
    }

    @Test
    void detailWithoutWorkspaceContextOnlyReadsPlatformScopeAndHidesExistence() {
        AutoWonderContext.get().setCurrentWorkspaceId(null);
        when(notificationDao.findByIdAndRecipient(1L, null, USER_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> controller.detail(1L));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
        verify(notificationDao).findByIdAndRecipient(1L, null, USER_ID);
    }

    private NotificationDO notification(long id, String status) {
        NotificationDO record = new NotificationDO();
        record.setId(id);
        record.setTenantId(WORKSPACE_ID);
        record.setRecipientId(USER_ID);
        record.setStatus(status);
        record.setGmtCreate(new Date(1789117547513L));
        return record;
    }
}
