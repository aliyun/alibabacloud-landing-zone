package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.jwt.TokenPayload;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.workspace.dto.CreateWorkspaceRequest;
import com.aliyun.autowonder.workspace.dto.CurrentMembershipVO;
import com.aliyun.autowonder.workspace.dto.MemberVO;
import com.aliyun.autowonder.workspace.dto.WorkspaceVO;
import com.aliyun.autowonder.workspace.dto.SwitchWorkspaceResponse;
import com.aliyun.autowonder.statemachine.StatusTemplateSeeder;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkspaceServiceTest {

    private WorkspaceDao workspaceDao;
    private WorkspaceMemberDao workspaceMemberDao;
    private StatusTemplateSeeder statusTemplateSeeder;
    private JwtService jwtService;
    private UserDao userDao;
    private AuditLogService auditLogService;
    private WorkspaceService service;

    @BeforeEach
    void setUp() {
        workspaceDao = mock(WorkspaceDao.class);
        workspaceMemberDao = mock(WorkspaceMemberDao.class);
        statusTemplateSeeder = mock(StatusTemplateSeeder.class);
        userDao = mock(UserDao.class);
        auditLogService = mock(AuditLogService.class);
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties props = new JwtProperties(env);
        props.setSecret("test-secret-key-that-is-long-enough-32bytes!");
        jwtService = new JwtService(props);
        service = new WorkspaceService(workspaceDao, workspaceMemberDao, statusTemplateSeeder,
                jwtService, userDao, auditLogService);
    }

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void createPersistsOwnerAsAdminWithoutRolesAndSeedsStatusTemplates() {
        doAnswer(invocation -> {
            ((WorkspaceDO) invocation.getArgument(0)).setId(10L);
            return null;
        }).when(workspaceDao).insert(any(WorkspaceDO.class));
        CreateWorkspaceRequest request = new CreateWorkspaceRequest();
        request.setName(" Acme ");

        WorkspaceVO result = service.create(request, 7L);

        assertEquals(10L, result.getId());
        assertEquals("Acme", result.getName());
        verify(workspaceMemberDao).insert(argThat(member ->
                Long.valueOf(10L).equals(member.getTenantId())
                        && Long.valueOf(7L).equals(member.getUserId())
                        && Integer.valueOf(0).equals(member.getStatus())
                        && WorkspaceAccessLevel.ADMIN.name().equals(member.getAccessLevel())
                        && "[]".equals(member.getIdentityTags())
                        && Long.valueOf(7L).equals(member.getCreatorId())));
        verify(statusTemplateSeeder).seed(10L, 7L);
    }

    @Test
    void createRejectsBlankAndDuplicateNames() {
        CreateWorkspaceRequest blank = new CreateWorkspaceRequest();
        blank.setName(" ");
        assertCode("11002", () -> service.create(blank, 7L));

        CreateWorkspaceRequest duplicate = new CreateWorkspaceRequest();
        duplicate.setName(" Acme ");
        when(workspaceDao.findByName("Acme")).thenReturn(new WorkspaceDO());
        assertCode("11003", () -> service.create(duplicate, 7L));
    }

    @Test
    void addMemberUsesReadOnlyAndEmptyTagsAndActiveMemberIsIdempotent() {
        when(userDao.findById(8L)).thenReturn(activeUser(8L));

        service.addMember(100L, 8L, 7L);

        verify(workspaceMemberDao).insertOrActivate(argThat(member ->
                Long.valueOf(100L).equals(member.getTenantId())
                        && Long.valueOf(8L).equals(member.getUserId())
                        && Integer.valueOf(0).equals(member.getStatus())
                        && WorkspaceAccessLevel.READ_ONLY.name().equals(member.getAccessLevel())
                        && "[]".equals(member.getIdentityTags())
                        && Long.valueOf(7L).equals(member.getCreatorId())
                        && Long.valueOf(7L).equals(member.getModifierId())));

        WorkspaceMemberDO active = activeMember(100L, 9L, WorkspaceAccessLevel.READ_WRITE, "[]");
        when(userDao.findById(9L)).thenReturn(activeUser(9L));
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 9L)).thenReturn(active);

        service.addMember(100L, 9L, 7L);

        verify(workspaceMemberDao, never()).insertOrActivate(argThat(member ->
                Long.valueOf(9L).equals(member.getUserId())));
    }

    @Test
    void addMemberRejectsMissingOrInactiveUsers() {
        assertCode("10001", () -> service.addMember(100L, null, 7L));
        assertCode("10404", () -> service.addMember(100L, 404L, 7L));

        UserDO inactive = activeUser(8L);
        inactive.setStatus(1);
        when(userDao.findById(8L)).thenReturn(inactive);
        assertCode("10001", () -> service.addMember(100L, 8L, 7L));
    }

    @Test
    void switchWorkspaceReturnsTokenAndExactAccessLevelAndUpdatesContext() {
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 7L))
                .thenReturn(activeMember(100L, 7L, WorkspaceAccessLevel.READ_WRITE, "[]"));
        AutoWonderContext.get().setCurrentWorkspaceId(200L);
        AutoWonderContext.get().setWorkspaceAccessLevel(WorkspaceAccessLevel.READ_ONLY);

        SwitchWorkspaceResponse response = service.switchWorkspace(100L, 7L);

        TokenPayload payload = jwtService.parse(response.getAccessToken());
        assertEquals(7L, payload.getUserId());
        assertEquals(100L, payload.getCurrentWorkspaceId());
        assertEquals(WorkspaceAccessLevel.READ_WRITE, response.getAccessLevel());
        assertEquals(100L, AutoWonderContext.get().getCurrentWorkspaceId());
        assertEquals(WorkspaceAccessLevel.READ_WRITE, AutoWonderContext.get().getWorkspaceAccessLevel());
    }

    @Test
    void switchWorkspaceRejectsMissingInactiveDeletedAndInvalidLevelMemberships() {
        assertCode("11001", () -> service.switchWorkspace(100L, 7L));

        WorkspaceMemberDO inactive = activeMember(100L, 7L, WorkspaceAccessLevel.READ_ONLY, "[]");
        inactive.setStatus(1);
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 7L)).thenReturn(inactive);
        assertCode("11001", () -> service.switchWorkspace(100L, 7L));

        WorkspaceMemberDO deleted = activeMember(100L, 7L, WorkspaceAccessLevel.READ_ONLY, "[]");
        deleted.setIsDeleted(1);
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 7L)).thenReturn(deleted);
        assertCode("11001", () -> service.switchWorkspace(100L, 7L));

        WorkspaceMemberDO invalid = activeMember(100L, 7L, WorkspaceAccessLevel.READ_ONLY, "[]");
        invalid.setAccessLevel("admin");
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 7L)).thenReturn(invalid);
        assertCode("12007", () -> service.switchWorkspace(100L, 7L));
    }

    @Test
    void listMembersMapsIdentityOwnerLevelAndNormalizedTags() {
        WorkspaceDO workspace = workspace(100L, 7L);
        Date joinedAt = new Date(1234L);
        WorkspaceMemberDO member = activeMember(
                100L, 8L, WorkspaceAccessLevel.READ_WRITE, "[\" reviewer \",\"developer\",\"reviewer\"]");
        member.setJoinedAt(joinedAt);
        UserDO user = activeUser(8L);
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setNickname("Alice");
        when(workspaceDao.findById(100L)).thenReturn(workspace);
        when(workspaceMemberDao.listByTenant(100L)).thenReturn(List.of(member));
        when(userDao.findById(8L)).thenReturn(user);

        List<MemberVO> result = service.listMembers(100L);

        assertEquals(1, result.size());
        MemberVO actual = result.get(0);
        assertEquals(8L, actual.getUserId());
        assertEquals("alice", actual.getUsername());
        assertEquals("alice@example.com", actual.getEmail());
        assertEquals("Alice", actual.getNickname());
        assertEquals(joinedAt, actual.getJoinedAt());
        assertFalse(actual.isOwner());
        assertEquals(WorkspaceAccessLevel.READ_WRITE, actual.getAccessLevel());
        assertEquals(List.of("reviewer", "developer"), actual.getIdentityTags());
    }

    @Test
    void currentMembershipMapsCurrentActiveMemberAndRejectsMissingOrInactive() {
        WorkspaceMemberDO member = activeMember(
                100L, 7L, WorkspaceAccessLevel.ADMIN, "[\" owner \",\"owner\"]");
        UserDO user = activeUser(7L);
        user.setUsername("owner");
        when(workspaceDao.findById(100L)).thenReturn(workspace(100L, 7L));
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 7L)).thenReturn(member);
        when(userDao.findById(7L)).thenReturn(user);

        CurrentMembershipVO result = service.currentMembership(100L, 7L);

        assertEquals(7L, result.getUserId());
        assertEquals("owner", result.getUsername());
        assertTrue(result.isOwner());
        assertEquals(WorkspaceAccessLevel.ADMIN, result.getAccessLevel());
        assertEquals(List.of("owner"), result.getIdentityTags());

        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 7L)).thenReturn(null);
        assertCode("11001", () -> service.currentMembership(100L, 7L));

        member.setStatus(1);
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 7L)).thenReturn(member);
        assertCode("11001", () -> service.currentMembership(100L, 7L));
    }

    @Test
    void currentMembershipReusesTheMemberValidatedByTheRequestFilter() {
        WorkspaceMemberDO member = activeMember(
                100L, 7L, WorkspaceAccessLevel.ADMIN, "[\"owner\"]");
        AutoWonderContext context = AutoWonderContext.get();
        context.setUserId(7L);
        context.setCurrentWorkspaceId(100L);
        context.setWorkspaceAccessLevel(WorkspaceAccessLevel.ADMIN);
        context.setWorkspaceMember(member);
        when(workspaceDao.findById(100L)).thenReturn(workspace(100L, 7L));
        when(userDao.findById(7L)).thenReturn(activeUser(7L));

        CurrentMembershipVO result = service.currentMembership(100L, 7L);

        assertEquals(WorkspaceAccessLevel.ADMIN, result.getAccessLevel());
        assertEquals(List.of("owner"), result.getIdentityTags());
        verify(workspaceMemberDao, never()).findByWorkspaceAndUser(100L, 7L);
    }

    @Test
    void updateMemberAccessPersistsExactLevelAndAuditsOldAndNewValues() {
        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace(100L, 7L));
        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 8L))
                .thenReturn(activeMember(100L, 8L, WorkspaceAccessLevel.READ_ONLY, "[]"));
        when(workspaceMemberDao.updateAccessLevel(100L, 8L, "READ_WRITE", 7L)).thenReturn(1);

        service.updateMemberAccess(100L, 8L, WorkspaceAccessLevel.READ_WRITE, 7L);

        InOrder order = inOrder(workspaceDao, workspaceMemberDao);
        order.verify(workspaceDao).findByIdForUpdate(100L);
        order.verify(workspaceMemberDao).findByWorkspaceAndUserForUpdate(100L, 8L);
        order.verify(workspaceMemberDao).updateAccessLevel(100L, 8L, "READ_WRITE", 7L);
        verify(workspaceMemberDao, never()).findByWorkspaceAndUser(100L, 8L);
        AuditLogRecord audit = capturedAudit();
        assertAudit(audit, 100L, 7L, "MEMBER_ACCESS_CHANGED", "MEMBER", 8L);
        assertEquals("READ_ONLY", audit.getDetail().get("oldAccessLevel"));
        assertEquals("READ_WRITE", audit.getDetail().get("newAccessLevel"));
        assertEquals(7L, audit.getDetail().get("operatorId"));
        assertEquals(8L, audit.getDetail().get("targetUserId"));
    }

    @Test
    void updateMemberAccessRejectsNullSelfOwnerInactiveAndInvalidPersistedLevel() {
        assertCode("12007", () -> service.updateMemberAccess(100L, 8L, null, 7L));
        assertCode("12010", () ->
                service.updateMemberAccess(100L, 7L, WorkspaceAccessLevel.ADMIN, 7L));

        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace(100L, 8L));
        assertCode("12009", () ->
                service.updateMemberAccess(100L, 8L, WorkspaceAccessLevel.READ_WRITE, 7L));
        verify(workspaceMemberDao, never()).findByWorkspaceAndUser(100L, 8L);
        verify(workspaceMemberDao, never()).findByWorkspaceAndUserForUpdate(100L, 8L);
        verify(workspaceMemberDao, never()).updateAccessLevel(anyLong(), anyLong(), any(), anyLong());

        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace(100L, 7L));
        WorkspaceMemberDO inactive = activeMember(100L, 8L, WorkspaceAccessLevel.READ_ONLY, "[]");
        inactive.setStatus(1);
        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 8L)).thenReturn(inactive);
        assertCode("11001", () ->
                service.updateMemberAccess(100L, 8L, WorkspaceAccessLevel.READ_WRITE, 7L));

        WorkspaceMemberDO invalid = activeMember(100L, 8L, WorkspaceAccessLevel.READ_ONLY, "[]");
        invalid.setAccessLevel("READ");
        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 8L)).thenReturn(invalid);
        assertCode("12007", () ->
                service.updateMemberAccess(100L, 8L, WorkspaceAccessLevel.READ_WRITE, 7L));
    }

    @Test
    void updateMemberAccessRequiresExactlyOneUpdatedRowAndDoesNotAuditFailure() {
        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace(100L, 7L));
        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 8L))
                .thenReturn(activeMember(100L, 8L, WorkspaceAccessLevel.READ_ONLY, "[]"));

        assertCode("10409", () ->
                service.updateMemberAccess(100L, 8L, WorkspaceAccessLevel.READ_WRITE, 7L));

        verifyNoInteractions(auditLogService);
    }

    @Test
    void updateMemberAccessPropagatesRequiredAuditFailure() {
        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace(100L, 7L));
        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 8L))
                .thenReturn(activeMember(100L, 8L, WorkspaceAccessLevel.READ_ONLY, "[]"));
        when(workspaceMemberDao.updateAccessLevel(100L, 8L, "READ_WRITE", 7L)).thenReturn(1);
        IllegalStateException failure = new IllegalStateException("audit database unavailable");
        doThrow(failure).when(auditLogService).recordRequired(any(AuditLogRecord.class));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.updateMemberAccess(100L, 8L, WorkspaceAccessLevel.READ_WRITE, 7L));

        assertSame(failure, thrown);
        verify(workspaceMemberDao).updateAccessLevel(100L, 8L, "READ_WRITE", 7L);
    }

    @Test
    void updateMemberIdentityTagsNormalizesPersistsAndAuditsLists() {
        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 8L))
                .thenReturn(activeMember(100L, 8L, WorkspaceAccessLevel.READ_ONLY, "[\"reviewer\"]"));
        when(workspaceMemberDao.updateIdentityTags(
                100L, 8L, "[\"developer\",\"reviewer\"]", 7L)).thenReturn(1);

        service.updateMemberIdentityTags(
                100L, 8L, List.of(" developer ", "reviewer", "developer"), 7L);

        InOrder order = inOrder(workspaceMemberDao, auditLogService);
        order.verify(workspaceMemberDao).findByWorkspaceAndUserForUpdate(100L, 8L);
        order.verify(workspaceMemberDao).updateIdentityTags(
                100L, 8L, "[\"developer\",\"reviewer\"]", 7L);
        verify(workspaceMemberDao, never()).findByWorkspaceAndUser(100L, 8L);
        AuditLogRecord audit = capturedAudit();
        assertAudit(audit, 100L, 7L, "MEMBER_IDENTITY_TAGS_CHANGED", "MEMBER", 8L);
        assertEquals(List.of("reviewer"), audit.getDetail().get("oldIdentityTags"));
        assertEquals(List.of("developer", "reviewer"), audit.getDetail().get("newIdentityTags"));
        assertEquals(7L, audit.getDetail().get("operatorId"));
        assertEquals(8L, audit.getDetail().get("targetUserId"));
    }

    @Test
    void updateMemberIdentityTagsRejectsInactiveAndRequiresExactlyOneUpdatedRow() {
        WorkspaceMemberDO inactive = activeMember(100L, 8L, WorkspaceAccessLevel.READ_ONLY, "[]");
        inactive.setStatus(1);
        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 8L)).thenReturn(inactive);
        assertCode("11001", () ->
                service.updateMemberIdentityTags(100L, 8L, List.of("developer"), 7L));

        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 8L))
                .thenReturn(activeMember(100L, 8L, WorkspaceAccessLevel.READ_ONLY, "[]"));
        assertCode("10409", () ->
                service.updateMemberIdentityTags(100L, 8L, List.of("developer"), 7L));

        verifyNoInteractions(auditLogService);
    }

    @Test
    void removeMemberProtectsLockedOwnerWithoutMutatingMembership() {
        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace(100L, 7L));

        assertCode("12009", () -> service.removeMember(100L, 7L, 9L));

        verify(workspaceDao).findByIdForUpdate(100L);
        verify(workspaceMemberDao, never()).findByWorkspaceAndUserForUpdate(anyLong(), anyLong());
        verify(workspaceMemberDao, never()).softDelete(anyLong(), anyLong(), anyLong());
    }

    @Test
    void removeMemberLocksActiveTargetAndRequiresExactlyOneDeletedRow() {
        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace(100L, 7L));
        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 8L))
                .thenReturn(activeMember(100L, 8L, WorkspaceAccessLevel.READ_ONLY, "[]"));
        when(workspaceMemberDao.softDelete(100L, 8L, 7L)).thenReturn(1);

        service.removeMember(100L, 8L, 7L);

        InOrder order = inOrder(workspaceDao, workspaceMemberDao);
        order.verify(workspaceDao).findByIdForUpdate(100L);
        order.verify(workspaceMemberDao).findByWorkspaceAndUserForUpdate(100L, 8L);
        order.verify(workspaceMemberDao).softDelete(100L, 8L, 7L);
        verify(workspaceMemberDao, never()).findByWorkspaceAndUser(100L, 8L);
    }

    @Test
    void removeMemberRejectsMissingInactiveAndZeroRowTargets() {
        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace(100L, 7L));

        assertCode("11001", () -> service.removeMember(100L, 8L, 7L));
        verify(workspaceMemberDao, never()).softDelete(anyLong(), anyLong(), anyLong());

        WorkspaceMemberDO inactive = activeMember(100L, 8L, WorkspaceAccessLevel.READ_ONLY, "[]");
        inactive.setStatus(1);
        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 8L)).thenReturn(inactive);
        assertCode("11001", () -> service.removeMember(100L, 8L, 7L));
        verify(workspaceMemberDao, never()).softDelete(anyLong(), anyLong(), anyLong());

        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 8L))
                .thenReturn(activeMember(100L, 8L, WorkspaceAccessLevel.READ_ONLY, "[]"));
        assertCode("10409", () -> service.removeMember(100L, 8L, 7L));
    }

    @Test
    void governanceMutationMethodsAreTransactional() throws Exception {
        assertTransactional("updateMemberAccess",
                long.class, long.class, WorkspaceAccessLevel.class, long.class);
        assertTransactional("updateMemberIdentityTags",
                long.class, long.class, List.class, long.class);
        assertTransactional("removeMember", long.class, long.class, long.class);
        assertTransactional("transferOwner", long.class, long.class, long.class);
    }

    @Test
    void transferOwnerLocksRowsPromotesTargetConditionallyUpdatesOwnerAndAudits() throws Exception {
        assertNotNull(WorkspaceService.class
                .getDeclaredMethod("transferOwner", long.class, long.class, long.class)
                .getAnnotation(Transactional.class));
        WorkspaceDO workspace = workspace(100L, 7L);
        WorkspaceMemberDO owner = activeMember(100L, 7L, WorkspaceAccessLevel.ADMIN, "[\"founder\"]");
        WorkspaceMemberDO target = activeMember(100L, 8L, WorkspaceAccessLevel.READ_WRITE, "[]");
        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace);
        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 7L)).thenReturn(owner);
        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 8L)).thenReturn(target);
        when(workspaceMemberDao.updateAccessLevel(100L, 8L, "ADMIN", 7L)).thenReturn(1);
        when(workspaceDao.updateOwner(100L, 7L, 8L, 7L)).thenReturn(1);

        service.transferOwner(100L, 8L, 7L);

        InOrder order = inOrder(workspaceDao, workspaceMemberDao, auditLogService);
        order.verify(workspaceDao).findByIdForUpdate(100L);
        order.verify(workspaceMemberDao).findByWorkspaceAndUserForUpdate(100L, 7L);
        order.verify(workspaceMemberDao).findByWorkspaceAndUserForUpdate(100L, 8L);
        order.verify(workspaceMemberDao).updateAccessLevel(100L, 8L, "ADMIN", 7L);
        order.verify(workspaceDao).updateOwner(100L, 7L, 8L, 7L);
        ArgumentCaptor<AuditLogRecord> captor = ArgumentCaptor.forClass(AuditLogRecord.class);
        order.verify(auditLogService).recordRequired(captor.capture());
        verify(workspaceMemberDao, never()).updateAccessLevel(100L, 7L, "READ_WRITE", 7L);
        AuditLogRecord audit = captor.getValue();
        assertAudit(audit, 100L, 7L, "ORG_OWNER_TRANSFERRED", "ORG", 100L);
        assertEquals(7L, audit.getDetail().get("oldOwnerId"));
        assertEquals(8L, audit.getDetail().get("newOwnerId"));
        assertEquals(7L, audit.getDetail().get("operatorId"));
        assertEquals(8L, audit.getDetail().get("targetUserId"));
    }

    @Test
    void transferOwnerRejectsNonOwnerSameTargetAndInactiveMemberships() {
        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace(100L, 7L));
        assertCode("12011", () -> service.transferOwner(100L, 8L, 9L));
        assertCode("12011", () -> service.transferOwner(100L, 7L, 7L));

        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 7L))
                .thenReturn(activeMember(100L, 7L, WorkspaceAccessLevel.ADMIN, "[]"));
        WorkspaceMemberDO inactiveTarget = activeMember(100L, 8L, WorkspaceAccessLevel.READ_ONLY, "[]");
        inactiveTarget.setStatus(1);
        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 8L)).thenReturn(inactiveTarget);
        assertCode("12011", () -> service.transferOwner(100L, 8L, 7L));

        verify(workspaceDao, never()).updateOwner(anyLong(), anyLong(), anyLong(), anyLong());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void transferOwnerRejectsInvalidOwnerMembershipAndFailedUpdates() {
        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace(100L, 7L));
        WorkspaceMemberDO invalidOwner = activeMember(100L, 7L, WorkspaceAccessLevel.READ_WRITE, "[]");
        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 7L)).thenReturn(invalidOwner);
        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 8L))
                .thenReturn(activeMember(100L, 8L, WorkspaceAccessLevel.READ_ONLY, "[]"));
        assertCode("12011", () -> service.transferOwner(100L, 8L, 7L));

        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 7L))
                .thenReturn(activeMember(100L, 7L, WorkspaceAccessLevel.ADMIN, "[]"));
        assertCode("12011", () -> service.transferOwner(100L, 8L, 7L));

        when(workspaceMemberDao.updateAccessLevel(100L, 8L, "ADMIN", 7L)).thenReturn(1);
        assertCode("12011", () -> service.transferOwner(100L, 8L, 7L));

        verifyNoInteractions(auditLogService);
    }

    private AuditLogRecord capturedAudit() {
        ArgumentCaptor<AuditLogRecord> captor = ArgumentCaptor.forClass(AuditLogRecord.class);
        verify(auditLogService).recordRequired(captor.capture());
        return captor.getValue();
    }

    private static void assertAudit(AuditLogRecord audit, long tenantId, long actorId,
                                    String event, String targetType, long targetId) {
        assertEquals(tenantId, audit.getTenantId());
        assertEquals(actorId, audit.getActorId());
        assertEquals("HUMAN", audit.getActorType());
        assertEquals("ORG", audit.getModule());
        assertEquals(event, audit.getAction());
        assertEquals(event, audit.getEventType());
        assertEquals(targetType, audit.getTargetType());
        assertEquals(targetId, audit.getTargetId());
        assertFalse(audit.getDetail().containsKey("accessToken"));
        assertFalse(audit.getDetail().containsKey("token"));
        assertFalse(audit.getDetail().containsKey("secret"));
    }

    private static WorkspaceMemberDO activeMember(long tenantId, long userId,
                                            WorkspaceAccessLevel level, String tags) {
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setTenantId(tenantId);
        member.setUserId(userId);
        member.setStatus(0);
        member.setIsDeleted(0);
        member.setAccessLevel(level.name());
        member.setIdentityTags(tags);
        return member;
    }

    @Test
    void listByUserWithAccessReadsWorkspacesAndLevelsInOneQuery() {
        when(workspaceDao.listMembershipsByUser(7L)).thenReturn(List.of(
                membership(10L, "first", WorkspaceAccessLevel.ADMIN),
                membership(20L, "second", WorkspaceAccessLevel.READ_ONLY)));

        var workspaces = service.listByUserWithAccess(7L);

        assertEquals(List.of(10L, 20L), workspaces.stream().map(WorkspaceVO::getId).toList());
        assertEquals(List.of(WorkspaceAccessLevel.ADMIN, WorkspaceAccessLevel.READ_ONLY),
                workspaces.stream().map(WorkspaceVO::getAccessLevel).toList());
        verify(workspaceMemberDao, never()).findByWorkspaceAndUser(anyLong(), anyLong());
    }

    @Test
    void listByUserWithAccessRejectsAnInvalidPersistedLevel() {
        WorkspaceMembershipDO invalid = membership(10L, "first", WorkspaceAccessLevel.ADMIN);
        invalid.setAccessLevel("admin");
        when(workspaceDao.listMembershipsByUser(7L)).thenReturn(List.of(invalid));

        assertCode("12007", () -> service.listByUserWithAccess(7L));
    }

    @Test
    void scopedWorkspaceReportsThePinnedWorkspaceWithTheScopeLevel() {
        when(workspaceDao.findById(10L)).thenReturn(workspace(10L, 7L));

        WorkspaceVO scoped = service.scopedWorkspace(10L, WorkspaceAccessLevel.READ_WRITE);

        assertEquals(10L, scoped.getId());
        assertEquals(WorkspaceAccessLevel.READ_WRITE, scoped.getAccessLevel());
    }

    @Test
    void activeAccessLevelResolvesTheLiveMembershipLevel() {
        when(workspaceMemberDao.findByWorkspaceAndUser(10L, 7L))
                .thenReturn(activeMember(10L, 7L, WorkspaceAccessLevel.READ_WRITE, null));

        assertEquals(WorkspaceAccessLevel.READ_WRITE, service.activeAccessLevel(10L, 7L));
    }

    @Test
    void activeAccessLevelRejectsNonMembersAndInvalidLevels() {
        when(workspaceMemberDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(null);
        assertCode("11001", () -> service.activeAccessLevel(10L, 7L));

        WorkspaceMemberDO removed = activeMember(10L, 7L, WorkspaceAccessLevel.ADMIN, null);
        removed.setIsDeleted(1);
        when(workspaceMemberDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(removed);
        assertCode("11001", () -> service.activeAccessLevel(10L, 7L));

        WorkspaceMemberDO invalid = activeMember(10L, 7L, WorkspaceAccessLevel.ADMIN, null);
        invalid.setAccessLevel("admin");
        when(workspaceMemberDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(invalid);
        assertCode("12007", () -> service.activeAccessLevel(10L, 7L));
    }

    private static WorkspaceMembershipDO membership(long workspaceId, String name, WorkspaceAccessLevel level) {
        WorkspaceMembershipDO membership = new WorkspaceMembershipDO();
        membership.setId(workspaceId);
        membership.setName(name);
        membership.setAccessLevel(level.name());
        return membership;
    }

    private static WorkspaceDO workspace(long id, long ownerId) {
        WorkspaceDO workspace = new WorkspaceDO();
        workspace.setId(id);
        workspace.setOwnerId(ownerId);
        return workspace;
    }

    private static UserDO activeUser(long id) {
        UserDO user = new UserDO();
        user.setId(id);
        user.setStatus(0);
        return user;
    }

    private static void assertCode(String code, ThrowingRunnable runnable) {
        BizException exception = assertThrows(BizException.class, runnable::run);
        assertEquals(code, exception.getCode());
    }

    private static void assertTransactional(String methodName, Class<?>... parameterTypes)
            throws Exception {
        assertNotNull(WorkspaceService.class.getDeclaredMethod(methodName, parameterTypes)
                .getAnnotation(Transactional.class), methodName + " must be transactional");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
