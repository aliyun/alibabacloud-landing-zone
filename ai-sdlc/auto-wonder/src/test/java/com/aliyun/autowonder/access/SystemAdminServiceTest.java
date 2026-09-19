package com.aliyun.autowonder.access;

import com.aliyun.autowonder.access.dto.PlatformAdminCandidateVO;
import com.aliyun.autowonder.access.dto.PlatformAdminListVO;
import com.aliyun.autowonder.access.dto.PlatformAdminVO;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemAdminServiceTest {

    @Test
    void aDemotedFirstUserNeverRegainsThePlatformAdminRole() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.findById(10000L)).thenReturn(user(10000L, 0));
        SystemAdminService service = new SystemAdminService(userDao);

        // The first active user had the flag revoked: being first grants nothing anymore, and no
        // fallback read of findFirstActiveUserId exists to silently restore the privilege.
        assertFalse(service.isSystemAdmin(10000L));
        BizException error = assertThrows(BizException.class,
                () -> service.requireSystemAdmin(10000L, "修改平台配置"));
        assertTrue(error.getMessage().contains("仅平台管理员可以修改平台配置"));
        verify(userDao, never()).findFirstActiveUserId();
    }

    @Test
    void treatsTheIsAdminColumnAsPlatformAdminWhateverTheFirstActiveUserIs() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.findById(10001L)).thenReturn(user(10001L, 1));
        SystemAdminService service = new SystemAdminService(userDao);

        // D3: is_admin is the flag, so a later user promoted by an operator is an admin even
        // though they are not the first active user.
        assertTrue(service.isSystemAdmin(10001L));
        assertDoesNotThrow(() -> service.requireSystemAdmin(10001L, "查看工作空间回收站"));
        verify(userDao, never()).findFirstActiveUserId();
    }

    @Test
    void rejectsAPlatformAdminLookupForAnUnknownNullOrUnflaggedUser() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.findById(10002L)).thenReturn(user(10002L, 0));
        when(userDao.findById(10003L)).thenReturn(null);
        SystemAdminService service = new SystemAdminService(userDao);

        assertFalse(service.isSystemAdmin(null));
        assertFalse(service.isSystemAdmin(10002L));
        // An unreadable row simply means "not an admin" — there is no legacy rule to fall through to.
        assertFalse(service.isSystemAdmin(10003L));
        BizException error = assertThrows(BizException.class,
                () -> service.requireSystemAdmin(10002L, "查看工作空间回收站"));
        assertTrue(error.getMessage().contains("仅平台管理员可以查看工作空间回收站"));
    }

    @Test
    void registerInitLeavesAnExistingPlatformAdminAlone() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.countSystemAdmins()).thenReturn(1L);
        SystemAdminService service = new SystemAdminService(userDao);

        assertFalse(service.ensureSystemAdmin());

        // Reading the first active user at all would risk promoting a second admin over the one
        // the operator already chose.
        verify(userDao, never()).findFirstActiveUserId();
        verify(userDao, never()).markSystemAdmin(10000L);
    }

    @Test
    void registerInitDoesNothingOnAnEmptyPlatform() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.countSystemAdmins()).thenReturn(0L);
        when(userDao.findFirstActiveUserId()).thenReturn(null);
        SystemAdminService service = new SystemAdminService(userDao);

        assertFalse(service.ensureSystemAdmin());

        verify(userDao, never()).markSystemAdmin(any());
    }

    @Test
    void registerInitPromotesTheLowestActiveIdAndReportsThePromotion() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.countSystemAdmins()).thenReturn(0L);
        when(userDao.findFirstActiveUserId()).thenReturn(10000L);
        when(userDao.markSystemAdmin(10000L)).thenReturn(1);
        SystemAdminService service = new SystemAdminService(userDao);

        assertTrue(service.ensureSystemAdmin());

        verify(userDao).markSystemAdmin(10000L);
    }

    @Test
    void registerInitLosesARaceQuietlyBecauseTheGuardAlreadyRejectedTheWrite() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.countSystemAdmins()).thenReturn(0L);
        when(userDao.findFirstActiveUserId()).thenReturn(10000L);
        // markSystemAdmin carries an is_admin = 0 predicate, so concurrent registrations all
        // resolve the same id and exactly one of them flips it.
        when(userDao.markSystemAdmin(10000L)).thenReturn(0);
        SystemAdminService service = new SystemAdminService(userDao);

        assertFalse(service.ensureSystemAdmin());
    }

    @Test
    void initMigrationIsSkippedEntirelyWhenTheCompletionMarkerExists() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.isPlatformAdminInitDone()).thenReturn(true);
        SystemAdminService service = new SystemAdminService(userDao);

        assertFalse(service.ensurePlatformAdminInitialized());

        // The marker is the whole decision: no roster read, no promotion, no re-write of the marker.
        verify(userDao, never()).countSystemAdmins();
        verify(userDao, never()).findFirstActiveUserId();
        verify(userDao, never()).markSystemAdmin(any());
        verify(userDao, never()).markPlatformAdminInitDone();
    }

    @Test
    void initMigrationMarksCompletionWithoutPromotingWhenAnAdminAlreadyExists() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.isPlatformAdminInitDone()).thenReturn(false);
        when(userDao.countSystemAdmins()).thenReturn(1L);
        SystemAdminService service = new SystemAdminService(userDao);

        assertFalse(service.ensurePlatformAdminInitialized());

        // An operator-granted admin must win over the migration; the marker still gets written so
        // the migration never runs again.
        verify(userDao, never()).findFirstActiveUserId();
        verify(userDao, never()).markSystemAdmin(any());
        verify(userDao).markPlatformAdminInitDone();
    }

    @Test
    void initMigrationPromotesTheFirstActiveUserOnceAndWritesTheMarker() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.isPlatformAdminInitDone()).thenReturn(false);
        when(userDao.countSystemAdmins()).thenReturn(0L);
        when(userDao.findFirstActiveUserId()).thenReturn(10000L);
        when(userDao.markSystemAdmin(10000L)).thenReturn(1);
        SystemAdminService service = new SystemAdminService(userDao);

        assertTrue(service.ensurePlatformAdminInitialized());

        verify(userDao).markSystemAdmin(10000L);
        verify(userDao).markPlatformAdminInitDone();
    }

    @Test
    void initMigrationStillMarksCompletionWhenNobodyCanBePromoted() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.isPlatformAdminInitDone()).thenReturn(false);
        when(userDao.countSystemAdmins()).thenReturn(0L);
        when(userDao.findFirstActiveUserId()).thenReturn(null);
        SystemAdminService service = new SystemAdminService(userDao);

        assertFalse(service.ensurePlatformAdminInitialized());

        // No user to promote, but the completion marker is still written: later restarts must not
        // silently promote a user who registered after this run.
        verify(userDao, never()).markSystemAdmin(any());
        verify(userDao).markPlatformAdminInitDone();
    }

    @Test
    void initMigrationLosesThePromotionRaceQuietlyButStillMarksCompletion() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.isPlatformAdminInitDone()).thenReturn(false);
        when(userDao.countSystemAdmins()).thenReturn(0L);
        when(userDao.findFirstActiveUserId()).thenReturn(10000L);
        when(userDao.markSystemAdmin(10000L)).thenReturn(0);
        SystemAdminService service = new SystemAdminService(userDao);

        assertFalse(service.ensurePlatformAdminInitialized());

        verify(userDao).markPlatformAdminInitDone();
    }

    @Test
    void rosterMarksTheOperatorRowAsSelfAndEverybodyElseAsRemovable() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.findById(10000L)).thenReturn(user(10000L, "alice", 1, 0));
        when(userDao.listSystemAdmins()).thenReturn(List.of(
                user(10000L, "alice", 1, 0),
                user(10001L, "bob", 1, 0)));
        SystemAdminService service = new SystemAdminService(userDao);

        PlatformAdminListVO vo = service.listPlatformAdmins(10000L);

        assertTrue(vo.isCanManage());
        assertEquals(2, vo.getAdmins().size());

        PlatformAdminVO self = vo.getAdmins().get(0);
        assertTrue(self.isSelf());
        assertFalse(self.isRemovable());
        assertEquals("平台管理员不可移除自己", self.getRemoveDisabledReason());

        PlatformAdminVO other = vo.getAdmins().get(1);
        assertEquals("bob", other.getUsername());
        assertTrue(other.isActive());
        assertFalse(other.isSelf());
        assertTrue(other.isRemovable());
        assertNull(other.getRemoveDisabledReason());

        // Promotable users come from the keyword search alone; embedding them here would give the
        // panel two competing option lists.
        verify(userDao, never()).searchSystemAdminCandidates(any(), anyInt());
    }

    @Test
    void rosterExplainsTheLastAdminRuleWhenOnlyOneAdminRemains() {
        UserDao userDao = mock(UserDao.class);
        // A viewer without the flag still sees the roster read-only; the single admin in it is
        // exactly the row the "keep at least one admin" rule protects.
        when(userDao.findById(10000L)).thenReturn(user(10000L, "alice", 0, 0));
        when(userDao.listSystemAdmins()).thenReturn(List.of(user(10001L, "bob", 1, 0)));
        SystemAdminService service = new SystemAdminService(userDao);

        PlatformAdminListVO vo = service.listPlatformAdmins(10000L);

        assertFalse(vo.isCanManage());
        PlatformAdminVO only = vo.getAdmins().get(0);
        assertFalse(only.isSelf());
        assertFalse(only.isRemovable());
        assertEquals("平台管理员至少保留一名，无法移除最后一名", only.getRemoveDisabledReason());
    }

    @Test
    void rosterReportsADeactivatedAdminAsInactiveAndDeniesManagementToAViewerWhoIsNotAnAdmin() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.findById(10002L)).thenReturn(user(10002L, "carol", 0, 0));
        // The roster is not filtered on status, so an admin whose account was deactivated still
        // occupies a seat and still counts towards the "keep at least one admin" rule.
        when(userDao.listSystemAdmins()).thenReturn(List.of(user(10001L, "bob", 1, 1)));
        SystemAdminService service = new SystemAdminService(userDao);

        PlatformAdminListVO vo = service.listPlatformAdmins(10002L);

        assertFalse(vo.isCanManage());
        assertFalse(vo.getAdmins().get(0).isActive());
    }

    @Test
    void candidateSearchTrimsTheKeywordAndMapsTheRoster() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.searchSystemAdminCandidates("carol", 20))
                .thenReturn(List.of(user(10002L, "carol", 0, 0)));
        SystemAdminService service = new SystemAdminService(userDao);

        List<PlatformAdminCandidateVO> candidates = service.searchPlatformAdminCandidates("  carol  ");

        assertEquals(1, candidates.size());
        assertEquals(Long.valueOf(10002L), candidates.get(0).getUserId());
        assertEquals("carol", candidates.get(0).getUsername());
        verify(userDao).searchSystemAdminCandidates("carol", 20);
    }

    @Test
    void candidateSearchTreatsANullKeywordAsAnEmptyOne() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.searchSystemAdminCandidates("", 20)).thenReturn(List.of());
        SystemAdminService service = new SystemAdminService(userDao);

        assertTrue(service.searchPlatformAdminCandidates(null).isEmpty());

        verify(userDao).searchSystemAdminCandidates("", 20);
    }

    @Test
    void addPromotesAnActiveUserOnBehalfOfAPlatformAdmin() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.findById(10000L)).thenReturn(user(10000L, "alice", 1, 0));
        when(userDao.findById(10002L)).thenReturn(user(10002L, "carol", 0, 0));
        SystemAdminService service = new SystemAdminService(userDao);

        service.addPlatformAdmin(10000L, 10002L);

        verify(userDao).markSystemAdmin(10002L);
    }

    @Test
    void addIsIdempotentBecauseTheGuardAlreadyRejectedTheSecondPromotion() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.findById(10000L)).thenReturn(user(10000L, "alice", 1, 0));
        when(userDao.findById(10001L)).thenReturn(user(10001L, "bob", 1, 0));
        // markSystemAdmin carries an is_admin = 0 predicate, so re-promoting an admin writes nothing.
        when(userDao.markSystemAdmin(10001L)).thenReturn(0);
        SystemAdminService service = new SystemAdminService(userDao);

        assertDoesNotThrow(() -> service.addPlatformAdmin(10000L, 10001L));

        verify(userDao).markSystemAdmin(10001L);
    }

    @Test
    void addRejectsAMissingNullOrDeactivatedTarget() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.findById(10000L)).thenReturn(user(10000L, "alice", 1, 0));
        when(userDao.findById(10003L)).thenReturn(null);
        when(userDao.findById(10004L)).thenReturn(user(10004L, "dave", 0, 1));
        SystemAdminService service = new SystemAdminService(userDao);

        assertThrows(BizException.class, () -> service.addPlatformAdmin(10000L, null));
        assertThrows(BizException.class, () -> service.addPlatformAdmin(10000L, 10003L));
        assertThrows(BizException.class, () -> service.addPlatformAdmin(10000L, 10004L));

        verify(userDao, never()).markSystemAdmin(any());
    }

    @Test
    void addRejectsAnOperatorWhoIsNotAPlatformAdmin() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.findById(10002L)).thenReturn(user(10002L, "carol", 0, 0));
        SystemAdminService service = new SystemAdminService(userDao);

        BizException error = assertThrows(BizException.class,
                () -> service.addPlatformAdmin(10002L, 10003L));

        assertTrue(error.getMessage().contains("仅平台管理员可以添加平台管理员"));
        // Authorization runs before the target is even read.
        verify(userDao, never()).findById(10003L);
        verify(userDao, never()).markSystemAdmin(any());
    }

    @Test
    void removeDemotesAnotherAdminWhileMoreThanOneRemains() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.findById(10000L)).thenReturn(user(10000L, "alice", 1, 0));
        when(userDao.findById(10001L)).thenReturn(user(10001L, "bob", 1, 0));
        when(userDao.countSystemAdmins()).thenReturn(2L);
        SystemAdminService service = new SystemAdminService(userDao);

        service.removePlatformAdmin(10000L, 10001L);

        verify(userDao).revokeSystemAdmin(10001L);
    }

    @Test
    void removeRefusesToRemoveTheOperatorEvenWhenAnotherAdminExists() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.findById(10000L)).thenReturn(user(10000L, "alice", 1, 0));
        when(userDao.countSystemAdmins()).thenReturn(2L);
        SystemAdminService service = new SystemAdminService(userDao);

        BizException error = assertThrows(BizException.class,
                () -> service.removePlatformAdmin(10000L, 10000L));

        assertTrue(error.getMessage().contains("平台管理员不可移除自己"));
        verify(userDao, never()).revokeSystemAdmin(any());
    }

    @Test
    void removeRefusesToEmptyTheAdminRoster() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.findById(10000L)).thenReturn(user(10000L, "alice", 1, 0));
        when(userDao.findById(10001L)).thenReturn(user(10001L, "bob", 1, 0));
        when(userDao.countSystemAdmins()).thenReturn(1L);
        SystemAdminService service = new SystemAdminService(userDao);

        BizException error = assertThrows(BizException.class,
                () -> service.removePlatformAdmin(10000L, 10001L));

        assertTrue(error.getMessage().contains("平台管理员至少保留一名"));
        verify(userDao, never()).revokeSystemAdmin(any());
    }

    @Test
    void removeRefusesATargetWhoDoesNotHoldTheAdminFlag() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.findById(10000L)).thenReturn(user(10000L, "alice", 1, 0));
        when(userDao.findById(10002L)).thenReturn(user(10002L, "carol", 0, 0));
        when(userDao.findById(10003L)).thenReturn(null);
        when(userDao.countSystemAdmins()).thenReturn(2L);
        SystemAdminService service = new SystemAdminService(userDao);

        assertThrows(BizException.class, () -> service.removePlatformAdmin(10000L, 10002L));
        assertThrows(BizException.class, () -> service.removePlatformAdmin(10000L, 10003L));
        assertThrows(BizException.class, () -> service.removePlatformAdmin(10000L, null));

        verify(userDao, never()).revokeSystemAdmin(any());
    }

    @Test
    void removeRejectsAnOperatorWhoIsNotAPlatformAdmin() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.findById(10002L)).thenReturn(user(10002L, "carol", 0, 0));
        SystemAdminService service = new SystemAdminService(userDao);

        BizException error = assertThrows(BizException.class,
                () -> service.removePlatformAdmin(10002L, 10001L));

        assertTrue(error.getMessage().contains("仅平台管理员可以移除平台管理员"));
        verify(userDao, never()).revokeSystemAdmin(any());
    }

    private static UserDO user(long id, int isAdmin) {
        return user(id, "user-" + id, isAdmin, 0);
    }

    private static UserDO user(long id, String username, int isAdmin, int status) {
        UserDO user = new UserDO();
        user.setId(id);
        user.setUsername(username);
        user.setNickname(username);
        user.setEmail(username + "@example.com");
        user.setStatus(status);
        user.setIsAdmin(isAdmin);
        return user;
    }
}
