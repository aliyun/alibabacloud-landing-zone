package com.aliyun.autowonder.user;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.feature.PlatformFeatureSwitchService;
import com.aliyun.autowonder.user.dto.WorkspaceCreationRequestVO;
import com.aliyun.autowonder.user.event.WorkspaceCreationAccessRequestedEvent;
import com.aliyun.autowonder.user.event.WorkspaceCreationPermissionGrantedEvent;
import com.aliyun.autowonder.workspace.WorkspaceCreationDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

class WorkspaceCreationPermissionServiceTest {

    private final UserDao userDao = mock(UserDao.class);
    private final PlatformFeatureSwitchService switches = mock(PlatformFeatureSwitchService.class);
    private final WorkspaceCreationRequestDao requestDao = mock(WorkspaceCreationRequestDao.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final WorkspaceCreationPermissionService service =
            new WorkspaceCreationPermissionService(userDao, switches, requestDao, events);

    private static UserDO user(long id, Integer status, boolean admin, boolean granted) {
        UserDO user = new UserDO();
        user.setId(id);
        user.setStatus(status);
        user.setIsAdmin(admin ? 1 : 0);
        user.setWorkspaceCreationGranted(granted ? 1 : 0);
        user.setNickname("nick-" + id);
        user.setEmail("u" + id + "@example.com");
        return user;
    }

    @Test
    void effectivePermissionMatrix() {
        when(switches.workspaceCreationDefaultAllowed()).thenReturn(false);
        assertTrue(service.canCreate(user(1L, 0, true, false)), "平台管理员始终可创建");
        assertTrue(service.canCreate(user(2L, 0, false, true)), "个人授权可创建");
        assertFalse(service.canCreate(user(3L, 0, false, false)), "全局关闭且未授权不可创建");
        assertFalse(service.canCreate(user(4L, 2, false, true)), "待审账号不可创建");
        UserDO deleted = user(5L, 0, false, true);
        deleted.setIsDeleted(1);
        assertFalse(service.canCreate(deleted), "已删除账号不可创建");

        when(switches.workspaceCreationDefaultAllowed()).thenReturn(true);
        assertTrue(service.canCreate(user(6L, 0, false, false)), "全局开启时正常账号可创建");
        // status=1 且无密码哈希（纯 SSO/停用）与 AuthFilter 一样属于停用：即使全局开启或挂着
        // 平台管理员标记也不可创建，保证行内标志与「无权限」筛选同源。
        assertFalse(service.canCreate(user(7L, 1, false, false)), "停用且无凭据的账号不可创建");
        assertFalse(service.canCreate(user(8L, 1, true, false)), "停用的平台管理员也不可创建");
        UserDO legacyWithPassword = user(10L, 1, false, false);
        legacyWithPassword.setPasswordHash("$2a$10$realHash");
        assertTrue(service.canCreate(legacyWithPassword), "仍可正常登录的 legacy 账号按正常处理");
        assertFalse(service.canCreate(null));
    }

    @Test
    void assertCanCreateThrowsWithAvailableAdministratorContacts() {
        when(userDao.findById(9L)).thenReturn(user(9L, 0, false, false));
        when(switches.workspaceCreationDefaultAllowed()).thenReturn(false);
        when(userDao.listAvailablePlatformAdmins()).thenReturn(List.of(user(1L, 0, true, false)));

        WorkspaceCreationDeniedException error = assertThrows(
                WorkspaceCreationDeniedException.class, () -> service.assertCanCreate(9L));

        assertEquals("37015", error.getCode());
        assertEquals(1, error.getAdministratorContacts().size());
        assertEquals("nick-1", error.getAdministratorContacts().get(0).getNickname());
    }

    @Test
    void contactsExcludeInactiveAdminsAndFallBackToUsernameWhenNicknameIsBlank() {
        UserDO inactive = user(1L, 1, true, false);
        inactive.setPasswordHash("legacy-hash");
        UserDO unnamed = user(2L, 0, true, false);
        unnamed.setNickname(" ");
        unnamed.setEmail(null);
        unnamed.setUsername("active-admin");
        UserDO named = user(3L, 0, true, false);
        when(userDao.listAvailablePlatformAdmins()).thenReturn(List.of(inactive, unnamed, named));

        var contacts = service.availableAdministratorContacts();

        assertEquals(2, contacts.size());
        assertEquals("active-admin", contacts.get(0).getNickname());
        assertNull(contacts.get(0).getEmail());
        assertEquals(named.getNickname(), contacts.get(1).getNickname());
        assertEquals(named.getEmail(), contacts.get(1).getEmail());
    }

    @Test
    void submitRequestIsIdempotentAndPublishesOnlyForANewRequest() {
        when(userDao.findById(9L)).thenReturn(user(9L, 0, false, false));
        when(switches.workspaceCreationDefaultAllowed()).thenReturn(false);
        when(userDao.listAvailablePlatformAdmins()).thenReturn(List.of(user(1L, 0, true, false)));
        WorkspaceCreationRequestDO pending = new WorkspaceCreationRequestDO();
        pending.setId(50L);
        pending.setUserId(9L);
        pending.setStatus("PENDING");
        when(requestDao.findPendingByUser(9L)).thenReturn(null, pending);

        WorkspaceCreationRequestVO result = service.submitRequest(9L);

        assertTrue(result.isPending());
        assertEquals(50L, result.getRequestId());
        verify(events).publishEvent(any(WorkspaceCreationAccessRequestedEvent.class));

        // 已有待处理申请：直接返回，不再通知。
        service.submitRequest(9L);
        verify(events, times(1)).publishEvent(any(WorkspaceCreationAccessRequestedEvent.class));
    }

    @Test
    void concurrentDuplicateInsertFallsBackToTheExistingPendingRequest() {
        when(userDao.findById(9L)).thenReturn(user(9L, 0, false, false));
        when(switches.workspaceCreationDefaultAllowed()).thenReturn(false);
        WorkspaceCreationRequestDO pending = new WorkspaceCreationRequestDO();
        pending.setId(51L);
        pending.setStatus("PENDING");
        when(requestDao.findPendingByUser(9L)).thenReturn(null, pending);
        doThrow(new DuplicateKeyException("uk_user_pending")).when(requestDao).insertPending(9L);

        WorkspaceCreationRequestVO result = service.submitRequest(9L);

        assertEquals(51L, result.getRequestId());
        verify(events, never()).publishEvent(any(WorkspaceCreationAccessRequestedEvent.class));
    }

    @Test
    void grantRejectsGlobalOverrideAndNotifiesApplicantOnlyWhenAPendingRequestClosed() {
        when(userDao.findByIdIncludingDeleted(9L)).thenReturn(user(9L, 0, false, false));
        when(switches.workspaceCreationDefaultAllowed()).thenReturn(true);
        assertThrows(BizException.class, () -> service.updateGrant(9L, true),
                "全局开启时拒绝修改个人授权");

        when(switches.workspaceCreationDefaultAllowed()).thenReturn(false);
        when(userDao.updateWorkspaceCreationGranted(9L, 1)).thenReturn(1);
        when(requestDao.fulfillPendingByUser(9L)).thenReturn(1, 0);
        service.updateGrant(9L, true);
        verify(events).publishEvent(any(WorkspaceCreationPermissionGrantedEvent.class));

        // 重复保存授权：没有待处理申请被结束，不再通知。
        service.updateGrant(9L, true);
        verify(events, times(1)).publishEvent(any(WorkspaceCreationPermissionGrantedEvent.class));
    }

    @Test
    void administratorAccountsCannotBeGrantedIndividually() {
        when(userDao.findByIdIncludingDeleted(1L)).thenReturn(user(1L, 0, true, false));
        BizException error = assertThrows(BizException.class, () -> service.updateGrant(1L, false));
        assertEquals("37017", error.getCode());
        verify(userDao, never()).updateWorkspaceCreationGranted(anyLong(), anyInt());
    }

    @Test
    void contactsAreOnlyReturnedForAnExplicitDenialProbe() {
        when(userDao.findById(9L)).thenReturn(user(9L, 0, false, false));
        when(switches.workspaceCreationDefaultAllowed()).thenReturn(false);
        when(userDao.listAvailablePlatformAdmins()).thenReturn(List.of(user(1L, 0, true, false)));

        assertNull(service.requestStatus(9L, false).getAdministratorContacts());
        assertEquals(1, service.requestStatus(9L, true).getAdministratorContacts().size());
        verify(userDao, times(1)).listAvailablePlatformAdmins();
    }

    @Test
    void globalDefaultDelegatesToTheSwitchAndAllowedUsersPassTheGuard() {
        when(switches.workspaceCreationDefaultAllowed()).thenReturn(true);
        assertTrue(service.globalDefaultAllowed());
        when(userDao.findById(9L)).thenReturn(user(9L, 0, false, false));

        service.assertCanCreate(9L);
        verify(userDao, never()).listAvailablePlatformAdmins();
    }

    @Test
    void submittingWhenAlreadyAllowedIsRejectedAsAlreadyGranted() {
        when(userDao.findById(9L)).thenReturn(user(9L, 0, false, true));
        when(switches.workspaceCreationDefaultAllowed()).thenReturn(false);

        BizException error = assertThrows(BizException.class, () -> service.submitRequest(9L));

        assertEquals("37018", error.getCode());
        verify(requestDao, never()).insertPending(anyLong());
    }

    @Test
    void submittingForAnInactiveApplicantIsRejectedAsReadonly() {
        when(userDao.findById(9L)).thenReturn(user(9L, 2, false, false));
        when(switches.workspaceCreationDefaultAllowed()).thenReturn(false);

        BizException error = assertThrows(BizException.class, () -> service.submitRequest(9L));

        assertEquals("37017", error.getCode());
    }

    @Test
    void grantingAMissingUserOrAFailedWriteIsRejected() {
        when(userDao.findByIdIncludingDeleted(404L)).thenReturn(null);
        assertEquals("10404", assertThrows(BizException.class,
                () -> service.updateGrant(404L, true)).getCode());

        when(userDao.findByIdIncludingDeleted(9L)).thenReturn(user(9L, 0, false, false));
        when(switches.workspaceCreationDefaultAllowed()).thenReturn(false);
        when(userDao.updateWorkspaceCreationGranted(9L, 1)).thenReturn(0);

        assertEquals("37017", assertThrows(BizException.class,
                () -> service.updateGrant(9L, true)).getCode());
    }
}
