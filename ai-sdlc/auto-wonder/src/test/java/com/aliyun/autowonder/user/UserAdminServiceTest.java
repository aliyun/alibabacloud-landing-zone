package com.aliyun.autowonder.user;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.im.UserImIdentityService;
import com.aliyun.autowonder.im.dto.UserImIdentityVO;
import com.aliyun.autowonder.notification.NotifyEvent;
import com.aliyun.autowonder.notification.NotifyService;
import com.aliyun.autowonder.user.dto.AdminResetPasswordRequest;
import com.aliyun.autowonder.user.dto.UpdateProfileRequest;
import com.aliyun.autowonder.user.dto.UserAdminListVO;
import com.aliyun.autowonder.user.dto.UserAdminUserVO;
import com.aliyun.autowonder.user.dto.UserAdminWorkspaceVO;
import com.aliyun.autowonder.user.dto.UserWorkspaceAddRequest;
import com.aliyun.autowonder.workspace.WorkspaceDO;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import com.aliyun.autowonder.workspace.WorkspaceMembershipDO;
import com.aliyun.autowonder.workspace.WorkspaceRoleDO;
import com.aliyun.autowonder.workspace.WorkspaceRoleService;
import com.aliyun.autowonder.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Platform user-management orchestration. The read paths serve three account states through one
 * VO, and the write paths are all "delegate after a guard" — so the guards, the status mapping and
 * the password-reset notification wiring are what these tests pin down.
 */
class UserAdminServiceTest {

    private final UserDao userDao = mock(UserDao.class);
    private final UserService userService = mock(UserService.class);
    private final UserDeletionService userDeletionService = mock(UserDeletionService.class);
    private final WorkspaceDao workspaceDao = mock(WorkspaceDao.class);
    private final WorkspaceMemberDao workspaceMemberDao = mock(WorkspaceMemberDao.class);
    private final WorkspaceService workspaceService = mock(WorkspaceService.class);
    private final WorkspaceRoleService workspaceRoleService = mock(WorkspaceRoleService.class);
    private final UserImIdentityService userImIdentityService = mock(UserImIdentityService.class);
    private final NotifyService notifyService = mock(NotifyService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final UserAdminService service = new UserAdminService(userDao, userService,
            userDeletionService, workspaceDao, workspaceMemberDao, workspaceService,
            workspaceRoleService, userImIdentityService, notifyService, auditLogService);

    private static final long TARGET_ID = 42L;
    private static final long OPERATOR_ID = 9L;
    private static final long WORKSPACE_ID = 100L;

    // ---- list / detail ----

    @Test
    void thePageClampsPagingAndKeepsTheUnfilteredTotalSeparate() {
        when(userDao.countAllUsers()).thenReturn(37L);
        when(userDao.countUsersForAdmin(null, null, null)).thenReturn(5L);
        when(userDao.searchUsersForAdmin(null, null, null, 0, 100))
                .thenReturn(List.of(normalUser(TARGET_ID, "alice")));

        UserAdminListVO result = service.page(null, null, null, 0, 1000);

        assertEquals(1, result.getPageNum());
        assertEquals(100, result.getPageSize());
        // totalAll never follows the filters; total is the paginator's own count.
        assertEquals(37L, result.getTotalAll());
        assertEquals(5L, result.getTotal());
        assertEquals(1, result.getList().size());
        verify(userDao).searchUsersForAdmin(null, null, null, 0, 100);
    }

    @Test
    void thePageNormalisesFiltersAndTrimsTheKeyword() {
        when(userDao.searchUsersForAdmin("ali", "DELETED", "ADMIN", 20, 20)).thenReturn(List.of());

        service.page("  ali  ", " deleted ", "admin", 2, 20);

        verify(userDao).countUsersForAdmin("ali", "DELETED", "ADMIN");
        verify(userDao).searchUsersForAdmin("ali", "DELETED", "ADMIN", 20, 20);
    }

    @Test
    void theAllSentinelIsEquivalentToNoFilter() {
        when(userDao.searchUsersForAdmin(null, null, null, 0, 20)).thenReturn(List.of());

        service.page(null, "all", "ALL", 1, 20);

        verify(userDao).countUsersForAdmin(null, null, null);
    }

    @Test
    void anUnknownStatusOrTypeFilterIsRejected() {
        BizException status = assertThrows(BizException.class,
                () -> service.page(null, "bogus", null, 1, 20));
        assertEquals("10001", status.getCode());
        assertEquals("user.filter_invalid", status.getMessageKey());
        BizException type = assertThrows(BizException.class,
                () -> service.page(null, null, "bogus", 1, 20));
        assertEquals("10001", type.getCode());
    }

    @Test
    void aDeletedRowIsMappedToItsSnapshotWithTheDeletionTrail() {
        UserDO deleted = deletedUser(TARGET_ID);
        deleted.setDeletedBy(OPERATOR_ID);
        UserDO operator = normalUser(OPERATOR_ID, "root");
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(deleted);
        when(userDao.findByIdIncludingDeleted(OPERATOR_ID)).thenReturn(operator);

        UserAdminUserVO vo = service.detail(TARGET_ID);

        assertEquals("alice", vo.getUsername());
        assertEquals("alice@example.com", vo.getEmail());
        assertEquals(UserAccountStates.DELETED, vo.getStatus());
        assertFalse(vo.getEditable());
        assertTrue(vo.getRestorable());
        assertEquals(OPERATOR_ID, vo.getDeletedById());
        assertEquals("root", vo.getDeletedByName());
        assertNotNull(vo.getDeletedAt());
    }

    @Test
    void aDeletedRowWithoutADeletionTrailLeavesTheOperatorNameNull() {
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(deletedUser(TARGET_ID));

        UserAdminUserVO vo = service.detail(TARGET_ID);

        assertEquals(UserAccountStates.DELETED, vo.getStatus());
        assertNull(vo.getDeletedById());
        assertNull(vo.getDeletedByName());
        verify(userDao, never()).findByIdIncludingDeleted(OPERATOR_ID);
    }

    @Test
    void aLegacyDeactivatedRowIsReadOnlyAndNotRestorable() {
        UserDO legacy = new UserDO();
        legacy.setId(TARGET_ID);
        legacy.setUsername("ghost");
        legacy.setStatus(1);
        legacy.setPasswordHash(UserAccountStates.LEGACY_DEACTIVATED_HASH);
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(legacy);

        UserAdminUserVO vo = service.detail(TARGET_ID);

        assertEquals(UserAccountStates.DEACTIVATED, vo.getStatus());
        assertFalse(vo.getEditable());
        assertFalse(vo.getRestorable());
        assertNull(vo.getDeletedAt());
    }

    @Test
    void aNormalRowIsEditableAndCarriesTheAdminFlag() {
        UserDO admin = normalUser(TARGET_ID, "root");
        admin.setIsAdmin(1);
        admin.setLastLoginAt(new Date());
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(admin);

        UserAdminUserVO vo = service.detail(TARGET_ID);

        assertEquals(UserAccountStates.NORMAL, vo.getStatus());
        assertTrue(vo.getEditable());
        assertFalse(vo.getRestorable());
        assertTrue(vo.getIsAdmin());
        assertNotNull(vo.getLastLoginAt());
    }

    @Test
    void detailOfAnUnknownUserIsNotFound() {
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.detail(TARGET_ID));

        assertEquals("10404", ex.getCode());
        assertEquals("user.not_found", ex.getMessageKey());
    }

    // ---- profile / password ----

    @Test
    void updateProfileDelegatesToTheSelfServiceValidationAndAuditsTheTarget() {
        UserDO target = normalUser(TARGET_ID, "alice");
        target.setNickname("爱丽丝");
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(target);

        service.updateProfile(TARGET_ID, new UpdateProfileRequest(), OPERATOR_ID);

        verify(userService).updateProfile(eq(TARGET_ID), any(UpdateProfileRequest.class));
        AuditLogRecord record = recordedAudit();
        assertEquals("USER_PROFILE_UPDATE", record.getAction());
        assertEquals(TARGET_ID, record.getTargetId());
        assertEquals("爱丽丝", record.getDetail().get("targetName"));
    }

    @Test
    void aMutationOnADeletedOrDeactivatedAccountIsReadOnly() {
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(deletedUser(TARGET_ID));

        BizException ex = assertThrows(BizException.class,
                () -> service.updateProfile(TARGET_ID, new UpdateProfileRequest(), OPERATOR_ID));

        assertEquals("37007", ex.getCode());
        verify(userService, never()).updateProfile(anyLong(), any());
    }

    @Test
    void resetPasswordRequiresTheTwoInputsToAgree() {
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(normalUser(TARGET_ID, "alice"));
        AdminResetPasswordRequest req = new AdminResetPasswordRequest();
        req.setNewPassword("brand-new-pw");
        req.setConfirmPassword("brand-new-pq");

        BizException ex = assertThrows(BizException.class,
                () -> service.resetPassword(TARGET_ID, req, OPERATOR_ID));

        assertEquals("37009", ex.getCode());
        verify(userService, never()).resetPassword(anyLong(), anyString());
        verify(notifyService, never()).notify(any());
    }

    @Test
    void resetPasswordNotifiesTheTargetInItsEarliestActiveWorkspace() {
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(normalUser(TARGET_ID, "alice"));
        when(workspaceMemberDao.findEarliestActiveWorkspaceId(TARGET_ID)).thenReturn(WORKSPACE_ID);
        AdminResetPasswordRequest req = matchingPasswordRequest();

        service.resetPassword(TARGET_ID, req, OPERATOR_ID);

        verify(userService).resetPassword(TARGET_ID, "brand-new-pw");
        ArgumentCaptor<NotifyEvent> captor = ArgumentCaptor.forClass(NotifyEvent.class);
        verify(notifyService).notify(captor.capture());
        NotifyEvent event = captor.getValue();
        assertEquals(WORKSPACE_ID, event.getTenantId());
        assertEquals("PASSWORD_RESET_BY_ADMIN", event.getType());
        assertEquals("notification.password_reset_by_admin.title", event.getTitleKey());
        assertEquals("notification.password_reset_by_admin.content", event.getContentKey());
        assertEquals(List.of(TARGET_ID), event.getRecipientIds());
        assertEquals("USER", event.getRefType());
        assertEquals(TARGET_ID, event.getRefId());
        assertNotNull(event.getContentArgs());
        // The content carries the reset time, never the password itself.
        assertTrue(event.getContentArgs()[0].toString().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}"));
        assertFalse(event.getContent().contains("brand-new-pw"));
        assertEquals("USER_PASSWORD_RESET", recordedAudit().getAction());
    }

    @Test
    void aTargetWithoutAnyActiveWorkspaceGetsNoNotificationButTheResetStillLands() {
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(normalUser(TARGET_ID, "alice"));
        when(workspaceMemberDao.findEarliestActiveWorkspaceId(TARGET_ID)).thenReturn(null);
        when(workspaceMemberDao.findEarliestActiveWorkspaceId(OPERATOR_ID)).thenReturn(WORKSPACE_ID);

        service.resetPassword(TARGET_ID, matchingPasswordRequest(), OPERATOR_ID);

        verify(userService).resetPassword(TARGET_ID, "brand-new-pw");
        verify(notifyService, never()).notify(any());
        assertEquals("USER_PASSWORD_RESET", recordedAudit().getAction());
    }

    @Test
    void aNotificationFailureDoesNotRollBackThePasswordChange() {
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(normalUser(TARGET_ID, "alice"));
        when(workspaceMemberDao.findEarliestActiveWorkspaceId(TARGET_ID)).thenReturn(WORKSPACE_ID);
        doThrow(new RuntimeException("im down")).when(notifyService).notify(any());

        service.resetPassword(TARGET_ID, matchingPasswordRequest(), OPERATOR_ID);

        verify(userService).resetPassword(TARGET_ID, "brand-new-pw");
        assertEquals("USER_PASSWORD_RESET", recordedAudit().getAction());
    }

    // ---- delete / restore ----

    @Test
    void deleteAndRestoreDelegateToTheSharedDeletionImplementation() {
        service.delete(TARGET_ID, "alice", OPERATOR_ID);
        verify(userDeletionService).delete(TARGET_ID, OPERATOR_ID, "alice", true);

        service.restore(TARGET_ID, OPERATOR_ID);
        verify(userDeletionService).restore(TARGET_ID, OPERATOR_ID);
    }

    // ---- workspaces ----

    @Test
    void theWorkspaceListMarksOwnershipAndCarriesTheJoinTime() {
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(normalUser(TARGET_ID, "alice"));
        Date joinedAt = new Date();
        WorkspaceMembershipDO owned = membership(WORKSPACE_ID, "org-a", WorkspaceAccessLevel.ADMIN.name());
        owned.setOwnerId(TARGET_ID);
        owned.setJoinedAt(joinedAt);
        WorkspaceMembershipDO joined = membership(200L, "org-b", WorkspaceAccessLevel.READ_ONLY.name());
        joined.setOwnerId(OPERATOR_ID);
        when(workspaceDao.listMembershipsByUser(TARGET_ID)).thenReturn(List.of(owned, joined));

        List<UserAdminWorkspaceVO> result = service.listWorkspaces(TARGET_ID);

        assertEquals(2, result.size());
        assertEquals(WORKSPACE_ID, result.get(0).getWorkspaceId());
        assertEquals("org-a", result.get(0).getName());
        assertTrue(result.get(0).getIsOwner());
        assertEquals(joinedAt, result.get(0).getJoinedAt());
        assertFalse(result.get(1).getIsOwner());
    }

    @Test
    void addingToAWorkspaceDefaultsToReadOnly() {
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(normalUser(TARGET_ID, "alice"));
        when(workspaceDao.findById(WORKSPACE_ID)).thenReturn(new WorkspaceDO());
        when(workspaceRoleService.builtIn(WORKSPACE_ID, WorkspaceAccessLevel.READ_ONLY))
                .thenReturn(role(11L, WorkspaceAccessLevel.READ_ONLY));
        UserWorkspaceAddRequest req = new UserWorkspaceAddRequest();
        req.setWorkspaceId(WORKSPACE_ID);

        service.addWorkspace(TARGET_ID, req, OPERATOR_ID);

        verify(workspaceService).addMember(WORKSPACE_ID, TARGET_ID, 11L, OPERATOR_ID);
        verify(workspaceService, never()).updateMemberRole(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void addingToAWorkspaceWithAnExplicitLevelResolvesThatBuiltInRole() {
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(normalUser(TARGET_ID, "alice"));
        when(workspaceDao.findById(WORKSPACE_ID)).thenReturn(new WorkspaceDO());
        when(workspaceRoleService.builtIn(WORKSPACE_ID, WorkspaceAccessLevel.ADMIN))
                .thenReturn(role(12L, WorkspaceAccessLevel.ADMIN));
        UserWorkspaceAddRequest req = new UserWorkspaceAddRequest();
        req.setWorkspaceId(WORKSPACE_ID);
        req.setAccessLevel("admin");

        service.addWorkspace(TARGET_ID, req, OPERATOR_ID);

        // A single insert already carries the chosen role; there is no follow-up promotion.
        verify(workspaceService).addMember(WORKSPACE_ID, TARGET_ID, 12L, OPERATOR_ID);
        verify(workspaceService, never()).updateMemberRole(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void addingToAWorkspaceRejectsAMissingBodyAndAnUnknownWorkspace() {
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(normalUser(TARGET_ID, "alice"));

        BizException missingBody = assertThrows(BizException.class,
                () -> service.addWorkspace(TARGET_ID, null, OPERATOR_ID));
        assertEquals("user.request_required", missingBody.getMessageKey());

        BizException missingWorkspace = assertThrows(BizException.class,
                () -> service.addWorkspace(TARGET_ID, new UserWorkspaceAddRequest(), OPERATOR_ID));
        assertEquals("workspace.not_found", missingWorkspace.getMessageKey());
        verify(workspaceService, never()).addMember(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void anExplicitLevelIsResolvedToItsBuiltInRoleAndDelegated() {
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(normalUser(TARGET_ID, "alice"));
        when(workspaceDao.findById(WORKSPACE_ID)).thenReturn(new WorkspaceDO());
        when(workspaceRoleService.builtIn(WORKSPACE_ID, WorkspaceAccessLevel.READ_WRITE))
                .thenReturn(role(13L, WorkspaceAccessLevel.READ_WRITE));

        List<UserAdminWorkspaceVO> result = service.updateWorkspaceLevel(
                TARGET_ID, WORKSPACE_ID, " read_write ", OPERATOR_ID);

        verify(workspaceService).updateMemberRole(WORKSPACE_ID, TARGET_ID, 13L, OPERATOR_ID);
        assertTrue(result.isEmpty());
    }

    @Test
    void aBlankLevelIsRejectedBeforeAnyRoleResolution() {
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(normalUser(TARGET_ID, "alice"));
        when(workspaceDao.findById(WORKSPACE_ID)).thenReturn(new WorkspaceDO());

        BizException ex = assertThrows(BizException.class,
                () -> service.updateWorkspaceLevel(TARGET_ID, WORKSPACE_ID, "  ", OPERATOR_ID));

        // A null level used to be rejected one layer down; with role-based membership the
        // admin layer must translate blank into the same invalid-level error itself.
        assertEquals("12007", ex.getCode());
        verify(workspaceRoleService, never()).builtIn(anyLong(), any());
        verify(workspaceService, never()).updateMemberRole(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void anUnknownWorkspaceLevelIsRejected() {
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(normalUser(TARGET_ID, "alice"));
        when(workspaceDao.findById(WORKSPACE_ID)).thenReturn(new WorkspaceDO());

        BizException ex = assertThrows(BizException.class,
                () -> service.updateWorkspaceLevel(TARGET_ID, WORKSPACE_ID, "superuser", OPERATOR_ID));

        assertEquals("12007", ex.getCode());
        verify(workspaceRoleService, never()).builtIn(anyLong(), any());
        verify(workspaceService, never()).updateMemberRole(anyLong(), anyLong(), any(), anyLong());
    }

    @Test
    void removingFromAWorkspaceDelegatesToTheWorkspaceSideGuard() {
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(normalUser(TARGET_ID, "alice"));
        when(workspaceDao.findById(WORKSPACE_ID)).thenReturn(new WorkspaceDO());

        service.removeWorkspace(TARGET_ID, WORKSPACE_ID, OPERATOR_ID);

        verify(workspaceService).removeMember(WORKSPACE_ID, TARGET_ID, OPERATOR_ID);
    }

    // ---- IM identities ----

    @Test
    void theIdentityListIsReadForDeletedUsersToo() {
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(deletedUser(TARGET_ID));
        UserImIdentityVO vo = new UserImIdentityVO();
        vo.setProvider("dingtalk");
        when(userImIdentityService.list(TARGET_ID)).thenReturn(List.of(vo));

        assertEquals(List.of(vo), service.listImIdentities(TARGET_ID));
    }

    @Test
    void anIdentityChangeIsAuditedWithTheAdminAsTheActor() {
        UserDO target = normalUser(TARGET_ID, "alice");
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(target);
        when(userImIdentityService.update(TARGET_ID, "dingtalk", "emp-1", OPERATOR_ID))
                .thenReturn(new UserImIdentityVO());

        service.updateImIdentity(TARGET_ID, "dingtalk", "emp-1", OPERATOR_ID);

        verify(userImIdentityService).update(TARGET_ID, "dingtalk", "emp-1", OPERATOR_ID);
        AuditLogRecord record = recordedAudit();
        assertEquals("USER_IM_IDENTITY_CHANGED", record.getAction());
        assertEquals(OPERATOR_ID, record.getActorId());
        assertEquals(TARGET_ID, record.getTargetId());
    }

    @Test
    void aTestImMessageIsSentOnlyForActiveAccounts() {
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(normalUser(TARGET_ID, "alice"));

        service.sendTestIm(TARGET_ID, "dingtalk", OPERATOR_ID);

        verify(userImIdentityService).sendTest(TARGET_ID, "dingtalk");

        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(deletedUser(TARGET_ID));
        BizException ex = assertThrows(BizException.class,
                () -> service.sendTestIm(TARGET_ID, "dingtalk", OPERATOR_ID));
        assertEquals("37007", ex.getCode());
    }

    @Test
    void anUnknownUserIsNotFoundForEverySubResource() {
        when(userDao.findByIdIncludingDeleted(TARGET_ID)).thenReturn(null);

        assertEquals("10404", assertThrows(BizException.class,
                () -> service.listWorkspaces(TARGET_ID)).getCode());
        assertEquals("10404", assertThrows(BizException.class,
                () -> service.listImIdentities(TARGET_ID)).getCode());
        assertEquals("10404", assertThrows(BizException.class,
                () -> service.sendTestIm(TARGET_ID, "dingtalk", OPERATOR_ID)).getCode());
    }

    // ---- fixtures ----

    private AdminResetPasswordRequest matchingPasswordRequest() {
        AdminResetPasswordRequest req = new AdminResetPasswordRequest();
        req.setNewPassword("brand-new-pw");
        req.setConfirmPassword("brand-new-pw");
        return req;
    }

    private UserDO normalUser(long id, String username) {
        UserDO user = new UserDO();
        user.setId(id);
        user.setUsername(username);
        user.setStatus(0);
        user.setIsAdmin(0);
        return user;
    }

    private UserDO deletedUser(long id) {
        UserDO user = normalUser(id, "#deleted-" + id);
        user.setIsDeleted(1);
        user.setDeletedUsername("alice");
        user.setDeletedEmail("alice@example.com");
        user.setDeletedAt(new Date());
        return user;
    }

    private WorkspaceMembershipDO membership(long id, String name, String accessLevel) {
        WorkspaceMembershipDO membership = new WorkspaceMembershipDO();
        membership.setId(id);
        membership.setName(name);
        membership.setAccessLevel(accessLevel);
        return membership;
    }

    private WorkspaceRoleDO role(long id, WorkspaceAccessLevel level) {
        WorkspaceRoleDO role = new WorkspaceRoleDO();
        role.setId(id);
        role.setAccessLevel(level.name());
        return role;
    }

    private AuditLogRecord recordedAudit() {
        ArgumentCaptor<AuditLogRecord> captor = ArgumentCaptor.forClass(AuditLogRecord.class);
        verify(auditLogService).recordRequired(captor.capture());
        return captor.getValue();
    }
}
