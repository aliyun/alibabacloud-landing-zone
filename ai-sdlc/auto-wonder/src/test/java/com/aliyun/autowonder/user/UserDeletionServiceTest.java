package com.aliyun.autowonder.user;

import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.im.UserImIdentityDao;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workitem.WorkitemDao;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DuplicateKeyException;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The single delete/restore implementation shared by self-service account cancellation and the
 * platform user-management console. The guards matter more than the happy path: every one of them
 * exists to turn a destructive call into a typed rejection before any row is touched.
 */
class UserDeletionServiceTest {

    private final UserDao userDao = mock(UserDao.class);
    private final WorkitemDao workitemDao = mock(WorkitemDao.class);
    private final WorkspaceDao workspaceDao = mock(WorkspaceDao.class);
    private final WorkspaceMemberDao workspaceMemberDao = mock(WorkspaceMemberDao.class);
    private final UserImIdentityDao userImIdentityDao = mock(UserImIdentityDao.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final UserDeletionService service = new UserDeletionService(userDao, workitemDao,
            workspaceDao, workspaceMemberDao, userImIdentityDao, auditLogService);

    // ---- delete ----

    @Test
    void selfServiceDeleteSoftDeletesUserMembershipsAndIdentitiesInOneCall() {
        UserDO user = activeUser(7L, "alice");
        when(userDao.findById(7L)).thenReturn(user);
        when(workitemDao.countActiveByAssignee("HUMAN", 7L)).thenReturn(0);
        when(workspaceMemberDao.findEarliestActiveWorkspaceId(7L)).thenReturn(100L);
        when(userDao.logicalDeleteUser(eq(7L), any(Date.class), eq(7L))).thenReturn(1);

        service.delete(7L, 7L, "alice", false);

        verify(userDao).logicalDeleteUser(eq(7L), any(Date.class), eq(7L));
        verify(workspaceMemberDao).softDeleteAllByUser(7L, 7L);
        verify(userImIdentityDao).softDeleteAllByUser(7L, 7L);
        AuditLogRecord record = recordedAudit();
        assertEquals("USER_DELETE", record.getAction());
        assertEquals(100L, record.getTenantId());
        assertEquals(7L, record.getActorId());
        assertEquals(7L, record.getTargetId());
        assertEquals("alice", record.getDetail().get("targetName"));
    }

    @Test
    void adminDeleteUsesTheSameImplementationWithTheAdminFlag() {
        UserDO user = activeUser(7L, "alice");
        when(userDao.findById(7L)).thenReturn(user);
        when(workitemDao.countActiveByAssignee("HUMAN", 7L)).thenReturn(0);
        when(workspaceMemberDao.findEarliestActiveWorkspaceId(7L)).thenReturn(100L);
        when(userDao.logicalDeleteUser(eq(7L), any(Date.class), eq(9L))).thenReturn(1);

        service.delete(7L, 9L, "alice", true);

        verify(userDao).logicalDeleteUser(eq(7L), any(Date.class), eq(9L));
        assertEquals(9L, recordedAudit().getActorId());
    }

    @Test
    void theAuditTenantIsResolvedBeforeTheMembershipRowsAreSoftDeleted() {
        when(userDao.findById(7L)).thenReturn(activeUser(7L, "alice"));
        when(workitemDao.countActiveByAssignee("HUMAN", 7L)).thenReturn(0);
        when(workspaceMemberDao.findEarliestActiveWorkspaceId(7L)).thenReturn(100L);
        when(userDao.logicalDeleteUser(eq(7L), any(Date.class), eq(9L))).thenReturn(1);

        service.delete(7L, 9L, "alice", true);

        InOrder order = inOrder(workspaceMemberDao);
        order.verify(workspaceMemberDao).findEarliestActiveWorkspaceId(7L);
        order.verify(workspaceMemberDao).softDeleteAllByUser(7L, 9L);
    }

    @Test
    void aTombstoneUsernameCollisionOnDeleteIsReportedAsAConflictInsteadOfEscaping() {
        // Registration reserves the whole # namespace, but a row written before that fix (or
        // through a side channel) can still make the tombstone rewrite hit uk_username. The caller
        // must get a typed rejection instead of an uncaught DuplicateKeyException and a 500.
        when(userDao.findById(7L)).thenReturn(activeUser(7L, "alice"));
        when(workitemDao.countActiveByAssignee("HUMAN", 7L)).thenReturn(0);
        when(workspaceMemberDao.findEarliestActiveWorkspaceId(7L)).thenReturn(100L);
        when(userDao.logicalDeleteUser(eq(7L), any(Date.class), eq(9L)))
                .thenThrow(new DuplicateKeyException(
                        "Duplicate entry '#deleted-7' for key 'user.uk_username'"));

        BizException ex = assertThrows(BizException.class,
                () -> service.delete(7L, 9L, "alice", true));

        assertEquals("37010", ex.getCode());
        verify(workspaceMemberDao, never()).softDeleteAllByUser(anyLong(), anyLong());
        verifyNoInteractions(userImIdentityDao, auditLogService);
    }

    @Test
    void aTargetWithoutAnyActiveWorkspaceFallsBackToTheOperatorsWorkspace() {
        when(userDao.findById(7L)).thenReturn(activeUser(7L, "alice"));
        when(workitemDao.countActiveByAssignee("HUMAN", 7L)).thenReturn(0);
        when(workspaceMemberDao.findEarliestActiveWorkspaceId(7L)).thenReturn(null);
        when(workspaceMemberDao.findEarliestActiveWorkspaceId(9L)).thenReturn(500L);
        when(userDao.logicalDeleteUser(eq(7L), any(Date.class), eq(9L))).thenReturn(1);

        service.delete(7L, 9L, "alice", true);

        assertEquals(500L, recordedAudit().getTenantId());
    }

    @Test
    void aDeleteWithoutAnyWorkspaceOwnershipStillCompletesAndSkipsOnlyTheAudit() {
        when(userDao.findById(7L)).thenReturn(activeUser(7L, "alice"));
        when(workitemDao.countActiveByAssignee("HUMAN", 7L)).thenReturn(0);
        // Target and operator are the same user here, so one stub covers both lookups.
        when(workspaceMemberDao.findEarliestActiveWorkspaceId(7L)).thenReturn(null);
        when(userDao.logicalDeleteUser(eq(7L), any(Date.class), eq(7L))).thenReturn(1);

        service.delete(7L, 7L, "alice", false);

        verify(userDao).logicalDeleteUser(eq(7L), any(Date.class), eq(7L));
        verify(workspaceMemberDao).softDeleteAllByUser(7L, 7L);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void theAdminConsoleMayNotDeleteTheSignedInAdmin() {
        when(userDao.findById(7L)).thenReturn(activeUser(7L, "alice"));

        BizException ex = assertThrows(BizException.class,
                () -> service.delete(7L, 7L, "alice", true));

        assertEquals("37001", ex.getCode());
        verifyNoDestructiveWork();
    }

    @Test
    void selfServiceCancellationIsAllowedEvenThoughTargetEqualsOperator() {
        when(userDao.findById(7L)).thenReturn(activeUser(7L, "alice"));
        when(workitemDao.countActiveByAssignee("HUMAN", 7L)).thenReturn(0);
        when(userDao.logicalDeleteUser(eq(7L), any(Date.class), eq(7L))).thenReturn(1);

        service.delete(7L, 7L, "alice", false);

        verify(userDao).logicalDeleteUser(eq(7L), any(Date.class), eq(7L));
    }

    @Test
    void theUsernameConfirmationMustMatchExactlyAndMayNotBeOmitted() {
        when(userDao.findById(7L)).thenReturn(activeUser(7L, "alice"));

        BizException mismatch = assertThrows(BizException.class,
                () -> service.delete(7L, 7L, "alice2", false));
        assertEquals("29006", mismatch.getCode());
        BizException missing = assertThrows(BizException.class,
                () -> service.delete(7L, 7L, null, false));
        assertEquals("29006", missing.getCode());
        verifyNoDestructiveWork();
    }

    @Test
    void unfinishedWorkitemsBlockTheDeleteWithTheExactCount() {
        when(userDao.findById(7L)).thenReturn(activeUser(7L, "alice"));
        when(workitemDao.countActiveByAssignee("HUMAN", 7L)).thenReturn(3);

        BizException ex = assertThrows(BizException.class,
                () -> service.delete(7L, 7L, "alice", false));

        assertEquals("29002", ex.getCode());
        assertEquals("user.deactivation_unfinished_workitems", ex.getMessageKey());
        assertEquals(3, ex.getMessageArgs()[0]);
        assertNotNull(ex.getMessage());
        verifyNoDestructiveWork();
    }

    @Test
    void aSoleWorkspaceAdminMustTransferTheWorkspaceFirst() {
        when(userDao.findById(7L)).thenReturn(activeUser(7L, "alice"));
        when(workitemDao.countActiveByAssignee("HUMAN", 7L)).thenReturn(0);
        when(workspaceMemberDao.isSoleAdminOfAnyWorkspace(7L)).thenReturn(true);

        BizException ex = assertThrows(BizException.class,
                () -> service.delete(7L, 7L, "alice", false));

        assertEquals("29003", ex.getCode());
        verifyNoDestructiveWork();
    }

    @Test
    void aWorkspaceOwnerMustTransferOwnershipFirst() {
        when(userDao.findById(7L)).thenReturn(activeUser(7L, "alice"));
        when(workitemDao.countActiveByAssignee("HUMAN", 7L)).thenReturn(0);
        when(workspaceDao.countOwnedByUser(7L)).thenReturn(1L);

        BizException ex = assertThrows(BizException.class,
                () -> service.delete(7L, 7L, "alice", false));

        assertEquals("37002", ex.getCode());
        verifyNoDestructiveWork();
    }

    @Test
    void theLastPlatformAdminMayNotBeDeleted() {
        UserDO admin = activeUser(7L, "root");
        admin.setIsAdmin(1);
        when(userDao.findById(7L)).thenReturn(admin);
        when(workitemDao.countActiveByAssignee("HUMAN", 7L)).thenReturn(0);
        when(userDao.countSystemAdmins()).thenReturn(1L);

        BizException ex = assertThrows(BizException.class,
                () -> service.delete(7L, 7L, "root", false));

        assertEquals("37003", ex.getCode());
        verifyNoDestructiveWork();
    }

    @Test
    void anAdminWithAnotherAdminRemainingMayBeDeleted() {
        UserDO admin = activeUser(7L, "root");
        admin.setIsAdmin(1);
        when(userDao.findById(7L)).thenReturn(admin);
        when(workitemDao.countActiveByAssignee("HUMAN", 7L)).thenReturn(0);
        when(userDao.countSystemAdmins()).thenReturn(2L);
        when(userDao.logicalDeleteUser(eq(7L), any(Date.class), eq(7L))).thenReturn(1);

        service.delete(7L, 7L, "root", false);

        verify(userDao).logicalDeleteUser(eq(7L), any(Date.class), eq(7L));
    }

    @Test
    void aRegularUserIsNeverCountedAgainstThePlatformAdminRoster() {
        when(userDao.findById(7L)).thenReturn(activeUser(7L, "alice"));
        when(workitemDao.countActiveByAssignee("HUMAN", 7L)).thenReturn(0);
        when(userDao.countSystemAdmins()).thenReturn(1L);
        when(userDao.logicalDeleteUser(eq(7L), any(Date.class), eq(7L))).thenReturn(1);

        service.delete(7L, 7L, "alice", false);

        verify(userDao, never()).countSystemAdmins();
        verify(userDao).logicalDeleteUser(eq(7L), any(Date.class), eq(7L));
    }

    @Test
    void anAlreadyDeletedTargetIsRejectedAsAlreadyDeleted() {
        when(userDao.findById(7L)).thenReturn(null);
        when(userDao.findByIdIncludingDeleted(7L)).thenReturn(deletedUser(7L));

        BizException ex = assertThrows(BizException.class,
                () -> service.delete(7L, 9L, "alice", true));

        assertEquals("37004", ex.getCode());
        verifyNoDestructiveWork();
    }

    @Test
    void anUnknownTargetIsReportedAsNotFound() {
        when(userDao.findById(7L)).thenReturn(null);
        when(userDao.findByIdIncludingDeleted(7L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.delete(7L, 9L, "alice", true));

        assertEquals("10404", ex.getCode());
        assertEquals("user.not_found", ex.getMessageKey());
        verifyNoDestructiveWork();
    }

    @Test
    void aLegacyDeactivatedAccountIsReadOnly() {
        UserDO legacy = activeUser(7L, "ghost");
        legacy.setStatus(1);
        legacy.setPasswordHash(UserAccountStates.LEGACY_DEACTIVATED_HASH);
        when(userDao.findById(7L)).thenReturn(legacy);

        BizException ex = assertThrows(BizException.class,
                () -> service.delete(7L, 9L, "ghost", true));

        assertEquals("37007", ex.getCode());
        verifyNoDestructiveWork();
    }

    @Test
    void aLostRaceOnTheDeleteFlagIsReportedAsAlreadyDeleted() {
        when(userDao.findById(7L)).thenReturn(activeUser(7L, "alice"));
        when(workitemDao.countActiveByAssignee("HUMAN", 7L)).thenReturn(0);
        when(userDao.logicalDeleteUser(eq(7L), any(Date.class), eq(7L))).thenReturn(0);

        BizException ex = assertThrows(BizException.class,
                () -> service.delete(7L, 7L, "alice", false));

        assertEquals("37004", ex.getCode());
        verify(workspaceMemberDao, never()).softDeleteAllByUser(anyLong(), anyLong());
        verify(userImIdentityDao, never()).softDeleteAllByUser(anyLong(), anyLong());
    }

    // ---- restore ----

    @Test
    void restoreRebindsTheSnapshotAndRecordsTheAudit() {
        when(userDao.findByIdIncludingDeleted(7L)).thenReturn(deletedUser(7L));
        when(userDao.restoreUser(7L)).thenReturn(1);
        when(workspaceMemberDao.findEarliestActiveWorkspaceId(7L)).thenReturn(100L);

        service.restore(7L, 9L);

        verify(userDao).restoreUser(7L);
        AuditLogRecord record = recordedAudit();
        assertEquals("USER_RESTORE", record.getAction());
        assertEquals(100L, record.getTenantId());
        assertEquals(9L, record.getActorId());
        assertEquals(7L, record.getTargetId());
        assertEquals("alice", record.getDetail().get("targetName"));
    }

    @Test
    void restoreWithoutAUsableSnapshotIsAConflict() {
        UserDO missing = deletedUser(7L);
        missing.setDeletedUsername(null);
        when(userDao.findByIdIncludingDeleted(7L)).thenReturn(missing);

        BizException ex = assertThrows(BizException.class, () -> service.restore(7L, 9L));

        assertEquals("37006", ex.getCode());
        verifyNoRestoreWork();
    }

    @Test
    void aBlankSnapshotIsTreatedAsMissing() {
        UserDO blank = deletedUser(7L);
        blank.setDeletedUsername("   ");
        when(userDao.findByIdIncludingDeleted(7L)).thenReturn(blank);

        BizException ex = assertThrows(BizException.class, () -> service.restore(7L, 9L));

        assertEquals("37006", ex.getCode());
        verifyNoRestoreWork();
    }

    @Test
    void restoreOfAnUnknownUserIsNotFound() {
        when(userDao.findByIdIncludingDeleted(7L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.restore(7L, 9L));

        assertEquals("10404", ex.getCode());
        verifyNoRestoreWork();
    }

    @Test
    void restoreOfALiveUserIsRejected() {
        when(userDao.findByIdIncludingDeleted(7L)).thenReturn(activeUser(7L, "alice"));

        BizException ex = assertThrows(BizException.class, () -> service.restore(7L, 9L));

        assertEquals("37005", ex.getCode());
        verifyNoRestoreWork();
    }

    @Test
    void aUsernameReclaimedByAnotherAccountBlocksTheRestore() {
        when(userDao.findByIdIncludingDeleted(7L)).thenReturn(deletedUser(7L));
        when(userDao.findByUsername("alice")).thenReturn(activeUser(8L, "alice"));

        BizException ex = assertThrows(BizException.class, () -> service.restore(7L, 9L));

        assertEquals("37006", ex.getCode());
        verify(userDao, never()).restoreUser(anyLong());
    }

    @Test
    void anEmailReclaimedByAnotherAccountBlocksTheRestore() {
        UserDO deleted = deletedUser(7L);
        deleted.setDeletedEmail("alice@example.com");
        when(userDao.findByIdIncludingDeleted(7L)).thenReturn(deleted);
        when(userDao.findActiveByEmail("alice@example.com")).thenReturn(activeUser(8L, "bob"));

        BizException ex = assertThrows(BizException.class, () -> service.restore(7L, 9L));

        assertEquals("37006", ex.getCode());
        verify(userDao, never()).restoreUser(anyLong());
    }

    @Test
    void aSnapshotWithoutAnEmailSkipsTheEmailLookup() {
        when(userDao.findByIdIncludingDeleted(7L)).thenReturn(deletedUser(7L));
        when(userDao.restoreUser(7L)).thenReturn(1);

        service.restore(7L, 9L);

        verify(userDao, never()).findActiveByEmail(any());
        verify(userDao).restoreUser(7L);
    }

    @Test
    void aLostRaceOnTheRestoreFlagIsRejected() {
        when(userDao.findByIdIncludingDeleted(7L)).thenReturn(deletedUser(7L));
        when(userDao.restoreUser(7L)).thenReturn(0);

        BizException ex = assertThrows(BizException.class, () -> service.restore(7L, 9L));

        assertEquals("37005", ex.getCode());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void aDuplicateKeyOnRestoreIsReportedAsAConflict() {
        when(userDao.findByIdIncludingDeleted(7L)).thenReturn(deletedUser(7L));
        when(userDao.restoreUser(7L)).thenThrow(new DuplicateKeyException("uk_username"));

        BizException ex = assertThrows(BizException.class, () -> service.restore(7L, 9L));

        assertEquals("37006", ex.getCode());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void aRestoreWithoutAnyWorkspaceOwnershipStillCompletesAndSkipsOnlyTheAudit() {
        when(userDao.findByIdIncludingDeleted(7L)).thenReturn(deletedUser(7L));
        when(userDao.restoreUser(7L)).thenReturn(1);
        when(workspaceMemberDao.findEarliestActiveWorkspaceId(7L)).thenReturn(null);
        when(workspaceMemberDao.findEarliestActiveWorkspaceId(9L)).thenReturn(null);

        service.restore(7L, 9L);

        verify(userDao).restoreUser(7L);
        verifyNoInteractions(auditLogService);
    }

    // ---- fixtures ----

    private UserDO activeUser(long id, String username) {
        UserDO user = new UserDO();
        user.setId(id);
        user.setUsername(username);
        user.setStatus(0);
        user.setIsAdmin(0);
        return user;
    }

    private UserDO deletedUser(long id) {
        UserDO user = activeUser(id, "#deleted-" + id);
        user.setIsDeleted(1);
        user.setDeletedUsername("alice");
        return user;
    }

    private AuditLogRecord recordedAudit() {
        ArgumentCaptor<AuditLogRecord> captor = ArgumentCaptor.forClass(AuditLogRecord.class);
        verify(auditLogService).recordRequired(captor.capture());
        return captor.getValue();
    }

    private void verifyNoDestructiveWork() {
        verify(userDao, never()).logicalDeleteUser(anyLong(), any(Date.class), anyLong());
        verify(workspaceMemberDao, never()).softDeleteAllByUser(anyLong(), anyLong());
        verifyNoInteractions(userImIdentityDao, auditLogService);
    }

    private void verifyNoRestoreWork() {
        verify(userDao, never()).restoreUser(anyLong());
        verifyNoInteractions(workspaceMemberDao, auditLogService);
    }
}
