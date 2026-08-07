package com.aliyun.autowonder.user;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.org.OrgMemberDao;
import com.aliyun.autowonder.user.dto.DeactivationRequest;
import com.aliyun.autowonder.user.dto.DeactivationStatusVO;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AccountDeactivationServiceTest {

    private AccountDeactivationService newService(UserDao userDao, WorkitemDao workitemDao,
                                                   OrgMemberDao orgMemberDao) {
        return new AccountDeactivationService(userDao, workitemDao, orgMemberDao);
    }

    private UserDO makeUser(Long id, String username) {
        UserDO user = new UserDO();
        user.setId(id);
        user.setUsername(username);
        user.setStatus(0);
        return user;
    }

    @Test
    void initiateDeactivationSuccess() {
        UserDao userDao = mock(UserDao.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        OrgMemberDao orgMemberDao = mock(OrgMemberDao.class);

        UserDO user = makeUser(1L, "testuser");
        when(userDao.findById(1L)).thenReturn(user);
        when(workitemDao.countActiveByAssignee("HUMAN", 1L)).thenReturn(0);
        when(orgMemberDao.isSoleAdminOfAnyOrg(1L)).thenReturn(false);
        when(userDao.updateDeactivation(eq(1L), any(Date.class), any(Date.class))).thenReturn(1);

        AccountDeactivationService svc = newService(userDao, workitemDao, orgMemberDao);
        DeactivationRequest req = new DeactivationRequest();
        req.setConfirmUsername("testuser");

        assertDoesNotThrow(() -> svc.initiateDeactivation(1L, req));
        verify(userDao).updateDeactivation(eq(1L), any(Date.class), any(Date.class));
    }

    @Test
    void initiateDeactivationAlreadyPending() {
        UserDao userDao = mock(UserDao.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        OrgMemberDao orgMemberDao = mock(OrgMemberDao.class);

        UserDO user = makeUser(1L, "testuser");
        user.setDeactivatedAt(new Date());
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, 5);
        user.setCoolingOffExpiresAt(cal.getTime());
        when(userDao.findById(1L)).thenReturn(user);

        AccountDeactivationService svc = newService(userDao, workitemDao, orgMemberDao);
        DeactivationRequest req = new DeactivationRequest();
        req.setConfirmUsername("testuser");

        BizException ex = assertThrows(BizException.class,
                () -> svc.initiateDeactivation(1L, req));
        assertEquals("29001", ex.getCode());
    }

    @Test
    void initiateDeactivationBlockedByWorkitems() {
        UserDao userDao = mock(UserDao.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        OrgMemberDao orgMemberDao = mock(OrgMemberDao.class);

        UserDO user = makeUser(1L, "testuser");
        when(userDao.findById(1L)).thenReturn(user);
        when(workitemDao.countActiveByAssignee("HUMAN", 1L)).thenReturn(3);

        AccountDeactivationService svc = newService(userDao, workitemDao, orgMemberDao);
        DeactivationRequest req = new DeactivationRequest();
        req.setConfirmUsername("testuser");

        BizException ex = assertThrows(BizException.class,
                () -> svc.initiateDeactivation(1L, req));
        assertEquals("29002", ex.getCode());
    }

    @Test
    void initiateDeactivationBlockedBySoleAdmin() {
        UserDao userDao = mock(UserDao.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        OrgMemberDao orgMemberDao = mock(OrgMemberDao.class);

        UserDO user = makeUser(1L, "testuser");
        when(userDao.findById(1L)).thenReturn(user);
        when(workitemDao.countActiveByAssignee("HUMAN", 1L)).thenReturn(0);
        when(orgMemberDao.isSoleAdminOfAnyOrg(1L)).thenReturn(true);

        AccountDeactivationService svc = newService(userDao, workitemDao, orgMemberDao);
        DeactivationRequest req = new DeactivationRequest();
        req.setConfirmUsername("testuser");

        BizException ex = assertThrows(BizException.class,
                () -> svc.initiateDeactivation(1L, req));
        assertEquals("29003", ex.getCode());
    }

    @Test
    void initiateDeactivationUsernameMismatch() {
        UserDao userDao = mock(UserDao.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        OrgMemberDao orgMemberDao = mock(OrgMemberDao.class);

        UserDO user = makeUser(1L, "testuser");
        when(userDao.findById(1L)).thenReturn(user);

        AccountDeactivationService svc = newService(userDao, workitemDao, orgMemberDao);
        DeactivationRequest req = new DeactivationRequest();
        req.setConfirmUsername("wronguser");

        BizException ex = assertThrows(BizException.class,
                () -> svc.initiateDeactivation(1L, req));
        assertEquals("29006", ex.getCode());
    }

    @Test
    void revokeDeactivationSuccess() {
        UserDao userDao = mock(UserDao.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        OrgMemberDao orgMemberDao = mock(OrgMemberDao.class);

        UserDO user = makeUser(1L, "testuser");
        user.setDeactivatedAt(new Date());
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, 5);
        user.setCoolingOffExpiresAt(cal.getTime());
        when(userDao.findById(1L)).thenReturn(user);
        when(userDao.revokeDeactivation(eq(1L), any(Date.class))).thenReturn(1);

        AccountDeactivationService svc = newService(userDao, workitemDao, orgMemberDao);

        assertDoesNotThrow(() -> svc.revokeDeactivation(1L));
        verify(userDao).revokeDeactivation(eq(1L), any(Date.class));
    }

    @Test
    void revokeDeactivationNotPending() {
        UserDao userDao = mock(UserDao.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        OrgMemberDao orgMemberDao = mock(OrgMemberDao.class);

        UserDO user = makeUser(1L, "testuser");
        when(userDao.findById(1L)).thenReturn(user);

        AccountDeactivationService svc = newService(userDao, workitemDao, orgMemberDao);

        BizException ex = assertThrows(BizException.class,
                () -> svc.revokeDeactivation(1L));
        assertEquals("29004", ex.getCode());
    }

    @Test
    void getDeactivationStatusWhenNotPending() {
        UserDao userDao = mock(UserDao.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        OrgMemberDao orgMemberDao = mock(OrgMemberDao.class);

        UserDO user = makeUser(1L, "testuser");
        when(userDao.findById(1L)).thenReturn(user);

        AccountDeactivationService svc = newService(userDao, workitemDao, orgMemberDao);
        DeactivationStatusVO status = svc.getDeactivationStatus(1L);

        assertFalse(status.isPending());
        assertNull(status.getDeactivatedAt());
    }

    @Test
    void getDeactivationStatusWhenPending() {
        UserDao userDao = mock(UserDao.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        OrgMemberDao orgMemberDao = mock(OrgMemberDao.class);

        UserDO user = makeUser(1L, "testuser");
        user.setDeactivatedAt(new Date());
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DAY_OF_MONTH, 5);
        user.setCoolingOffExpiresAt(cal.getTime());
        when(userDao.findById(1L)).thenReturn(user);

        AccountDeactivationService svc = newService(userDao, workitemDao, orgMemberDao);
        DeactivationStatusVO status = svc.getDeactivationStatus(1L);

        assertTrue(status.isPending());
        assertNotNull(status.getDeactivatedAt());
        assertNotNull(status.getCoolingOffExpiresAt());
    }

    @Test
    void processExpiredDeactivations() {
        UserDao userDao = mock(UserDao.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        OrgMemberDao orgMemberDao = mock(OrgMemberDao.class);

        UserDO expiredUser = makeUser(1L, "expired");
        when(userDao.listExpiredDeactivations(100)).thenReturn(Collections.singletonList(expiredUser));
        when(userDao.anonymizeUser(1L)).thenReturn(1);

        AccountDeactivationService svc = newService(userDao, workitemDao, orgMemberDao);
        int processed = svc.processExpiredDeactivations();

        assertEquals(1, processed);
        verify(userDao).anonymizeUser(1L);
    }

    @Test
    void processExpiredDeactivationsHandlesErrors() {
        UserDao userDao = mock(UserDao.class);
        WorkitemDao workitemDao = mock(WorkitemDao.class);
        OrgMemberDao orgMemberDao = mock(OrgMemberDao.class);

        UserDO user1 = makeUser(1L, "user1");
        UserDO user2 = makeUser(2L, "user2");
        when(userDao.listExpiredDeactivations(100)).thenReturn(java.util.List.of(user1, user2));
        when(userDao.anonymizeUser(1L)).thenThrow(new RuntimeException("db error"));
        when(userDao.anonymizeUser(2L)).thenReturn(1);

        AccountDeactivationService svc = newService(userDao, workitemDao, orgMemberDao);
        int processed = svc.processExpiredDeactivations();

        assertEquals(1, processed);
        verify(userDao).anonymizeUser(1L);
        verify(userDao).anonymizeUser(2L);
    }
}
