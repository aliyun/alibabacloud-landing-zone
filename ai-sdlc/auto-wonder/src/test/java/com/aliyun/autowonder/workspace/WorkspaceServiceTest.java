package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.WorkspaceDomain;
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
import com.aliyun.autowonder.agent.PlatformAgentSeeder;
import com.aliyun.autowonder.statemachine.StatusTemplateSeeder;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    private PlatformAgentSeeder platformAgentSeeder;
    private JwtService jwtService;
    private UserDao userDao;
    private AuditLogService auditLogService;
    private SystemAdminService systemAdminService;
    private WorkspaceDeletionLinkage deletionLinkage;
    private ApplicationEventPublisher eventPublisher;
    private WorkspaceService service;
    private WorkspaceRoleService roleService;

    @BeforeEach
    void setUp() {
        workspaceDao = mock(WorkspaceDao.class);
        workspaceMemberDao = mock(WorkspaceMemberDao.class);
        statusTemplateSeeder = mock(StatusTemplateSeeder.class);
        platformAgentSeeder = mock(PlatformAgentSeeder.class);
        userDao = mock(UserDao.class);
        auditLogService = mock(AuditLogService.class);
        systemAdminService = mock(SystemAdminService.class);
        deletionLinkage = mock(WorkspaceDeletionLinkage.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties props = new JwtProperties(env);
        props.setSecret("test-secret-key-that-is-long-enough-32bytes!");
        jwtService = new JwtService(props);
        roleService = mock(WorkspaceRoleService.class);
        when(roleService.builtIn(anyLong(), any())).thenAnswer(invocation -> role(invocation.getArgument(1)));
        when(roleService.requireRole(100L, 12L)).thenReturn(role(WorkspaceAccessLevel.READ_WRITE));
        service = new WorkspaceService(workspaceDao, workspaceMemberDao, statusTemplateSeeder,
                platformAgentSeeder, jwtService, userDao, auditLogService, systemAdminService,
                deletionLinkage, eventPublisher, roleService);
    }

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void createSeedsRolesAndAssignsOwnerTheAdministratorRole() {
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
        verify(roleService).seed(10L, 7L);
        verify(workspaceMemberDao).insert(argThat(member -> Long.valueOf(13L).equals(member.getRoleId())));
        verify(statusTemplateSeeder).seed(10L, 7L);
    }

    @Test
    void createSeedsPlatformAgentAfterStatusTemplates() {
        doAnswer(invocation -> {
            ((WorkspaceDO) invocation.getArgument(0)).setId(55L);
            return null;
        }).when(workspaceDao).insert(any(WorkspaceDO.class));
        CreateWorkspaceRequest request = new CreateWorkspaceRequest();
        request.setName("新工区");

        service.create(request, 7L);

        InOrder inOrder = inOrder(statusTemplateSeeder, platformAgentSeeder);
        inOrder.verify(statusTemplateSeeder).seed(55L, 7L);
        inOrder.verify(platformAgentSeeder).seed(55L, 7L);
    }

    @Test
    void createPropagatesPlatformSeedFailure() {
        doAnswer(invocation -> {
            ((WorkspaceDO) invocation.getArgument(0)).setId(55L);
            return null;
        }).when(workspaceDao).insert(any(WorkspaceDO.class));
        doThrow(new IllegalStateException("platform agent template missing"))
                .when(platformAgentSeeder).seed(55L, 7L);
        CreateWorkspaceRequest request = new CreateWorkspaceRequest();
        request.setName("新工区");

        assertThrows(IllegalStateException.class, () -> service.create(request, 7L));
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
    void addMemberDefaultsToTheViewerRoleAndEmptyTagsAndActiveMemberIsIdempotent() {
        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace(100L, 7L));
        when(userDao.findById(8L)).thenReturn(activeUser(8L));

        service.addMember(100L, 8L, null, 7L);

        verify(workspaceMemberDao).insertOrActivate(argThat(member ->
                Long.valueOf(100L).equals(member.getTenantId())
                        && Long.valueOf(8L).equals(member.getUserId())
                        && Integer.valueOf(0).equals(member.getStatus())
                        && Long.valueOf(11L).equals(member.getRoleId())
                        && WorkspaceAccessLevel.READ_ONLY.name().equals(member.getAccessLevel())
                        && "[]".equals(member.getIdentityTags())
                        && Long.valueOf(7L).equals(member.getCreatorId())
                        && Long.valueOf(7L).equals(member.getModifierId())));
        verify(roleService).builtIn(100L, WorkspaceAccessLevel.READ_ONLY);

        WorkspaceMemberDO active = activeMember(100L, 9L, WorkspaceAccessLevel.READ_WRITE, "[]");
        when(userDao.findById(9L)).thenReturn(activeUser(9L));
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 9L)).thenReturn(active);

        service.addMember(100L, 9L, null, 7L);

        verify(workspaceMemberDao, never()).insertOrActivate(argThat(member ->
                Long.valueOf(9L).equals(member.getUserId())));
    }

    @Test
    void addMemberRejectsMissingOrInactiveUsers() {
        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace(100L, 7L));
        assertCode("10001", () -> service.addMember(100L, null, null, 7L));
        assertCode("10404", () -> service.addMember(100L, 404L, null, 7L));

        UserDO inactive = activeUser(8L);
        inactive.setStatus(1);
        when(userDao.findById(8L)).thenReturn(inactive);
        assertCode("10001", () -> service.addMember(100L, 8L, null, 7L));
    }

    @Test
    void switchWorkspaceReturnsTokenAndExactAccessLevelAndUpdatesContext() {
        when(workspaceDao.countUsable(100L)).thenReturn(1L);
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
    void switchWorkspaceAllowsAPlatformAdminWhoIsNotAMember() {
        when(workspaceDao.countUsable(100L)).thenReturn(1L);
        when(systemAdminService.isSystemAdmin(7L)).thenReturn(true);
        AutoWonderContext.get().setCurrentWorkspaceId(200L);

        SwitchWorkspaceResponse response = service.switchWorkspace(100L, 7L);

        TokenPayload payload = jwtService.parse(response.getAccessToken());
        assertEquals(7L, payload.getUserId());
        assertEquals(100L, payload.getCurrentWorkspaceId());
        assertEquals(WorkspaceAccessLevel.ADMIN, response.getAccessLevel());
        assertEquals(100L, AutoWonderContext.get().getCurrentWorkspaceId());
        assertEquals(WorkspaceAccessLevel.ADMIN, AutoWonderContext.get().getWorkspaceAccessLevel());
    }

    @Test
    void switchWorkspaceRejectsARevokedPlatformAdminWhoIsNotAMember() {
        when(workspaceDao.countUsable(100L)).thenReturn(1L);
        when(systemAdminService.isSystemAdmin(7L)).thenReturn(false);

        assertCode("11001", () -> service.switchWorkspace(100L, 7L));
    }

    @Test
    void switchWorkspaceRejectsMissingInactiveDeletedAndInvalidLevelMemberships() {
        when(workspaceDao.countUsable(100L)).thenReturn(1L);

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
    void switchWorkspaceRejectsADeletedOrDisabledWorkspaceEvenForAPlatformAdmin() {
        when(workspaceDao.countUsable(100L)).thenReturn(0L);
        when(systemAdminService.isSystemAdmin(7L)).thenReturn(true);
        AutoWonderContext.get().setCurrentWorkspaceId(200L);

        // AuthFilter applies the same countUsable predicate on every request, so a token issued
        // here would die on arrival; the switch must fail up front with the same message.
        assertCode("11005", () -> service.switchWorkspace(100L, 7L));

        verify(workspaceMemberDao, never()).findByWorkspaceAndUser(100L, 7L);
        assertEquals(200L, AutoWonderContext.get().getCurrentWorkspaceId());
        assertNull(AutoWonderContext.get().getWorkspaceAccessLevel());
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
        when(userDao.findByIdIncludingDeleted(8L)).thenReturn(user);

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
    void listMembersFallsBackToTheDeletedSnapshotForAnActiveMemberRow() {
        WorkspaceMemberDO member = activeMember(100L, 8L, WorkspaceAccessLevel.READ_WRITE, "[]");
        UserDO user = activeUser(8L);
        user.setIsDeleted(1);
        user.setDeletedUsername("former-alice");
        user.setDeletedEmail("former-alice@example.com");
        when(workspaceDao.findById(100L)).thenReturn(workspace(100L, 7L));
        when(workspaceMemberDao.listByTenant(100L)).thenReturn(List.of(member));
        when(userDao.findByIdIncludingDeleted(8L)).thenReturn(user);

        MemberVO actual = service.listMembers(100L).get(0);

        assertEquals(8L, actual.getUserId());
        assertEquals("former-alice", actual.getUsername());
        assertEquals("former-alice@example.com", actual.getEmail());
    }

    @Test
    void currentMembershipMapsCurrentActiveMemberAndRejectsMissingOrInactive() {
        WorkspaceMemberDO member = activeMember(
                100L, 7L, WorkspaceAccessLevel.ADMIN, "[\" owner \",\"owner\"]");
        UserDO user = activeUser(7L);
        user.setUsername("owner");
        when(workspaceDao.findById(100L)).thenReturn(workspace(100L, 7L));
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 7L)).thenReturn(member);
        when(userDao.findByIdIncludingDeleted(7L)).thenReturn(user);

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
    void currentMembershipFallsBackToTheDeletedSnapshotForAnActiveMemberRow() {
        WorkspaceMemberDO member = activeMember(100L, 7L, WorkspaceAccessLevel.READ_ONLY, "[]");
        UserDO user = activeUser(7L);
        user.setIsDeleted(1);
        user.setDeletedUsername("former-owner");
        user.setDeletedEmail("former-owner@example.com");
        when(workspaceDao.findById(100L)).thenReturn(workspace(100L, 7L));
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 7L)).thenReturn(member);
        when(userDao.findByIdIncludingDeleted(7L)).thenReturn(user);

        CurrentMembershipVO result = service.currentMembership(100L, 7L);

        assertEquals(7L, result.getUserId());
        assertEquals("former-owner", result.getUsername());
        assertEquals("former-owner@example.com", result.getEmail());
        assertEquals(WorkspaceAccessLevel.READ_ONLY, result.getAccessLevel());
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
        when(userDao.findByIdIncludingDeleted(7L)).thenReturn(activeUser(7L));

        CurrentMembershipVO result = service.currentMembership(100L, 7L);

        assertEquals(WorkspaceAccessLevel.ADMIN, result.getAccessLevel());
        assertEquals(List.of("owner"), result.getIdentityTags());
        verify(workspaceMemberDao, never()).findByWorkspaceAndUser(100L, 7L);
    }

    @Test
    void currentMembershipSynthesizesAnAdminMembershipForAPlatformAdminWhoNeverJoined() {
        // ownerId belongs to someone else: this admin never joined, so the synthetic membership
        // must not paint it as the workspace Owner.
        when(workspaceDao.findById(100L)).thenReturn(workspace(100L, 99L));
        when(systemAdminService.isSystemAdmin(7L)).thenReturn(true);
        UserDO user = activeUser(7L);
        user.setUsername("platform-admin");
        when(userDao.findByIdIncludingDeleted(7L)).thenReturn(user);

        CurrentMembershipVO result = service.currentMembership(100L, 7L);

        // AuthFilter already granted ADMIN without a member row; the synthetic membership keeps
        // the frontend flags consistent instead of reporting "not a member".
        assertEquals(7L, result.getUserId());
        assertEquals("platform-admin", result.getUsername());
        assertEquals(WorkspaceAccessLevel.ADMIN, result.getAccessLevel());
        assertFalse(result.isOwner());
        assertNull(result.getJoinedAt());
        assertTrue(result.getIdentityTags().isEmpty());
    }

    @Test
    void currentMembershipSynthesizesAdminForAPlatformAdminWhoseMemberRowIsDeactivated() {
        WorkspaceMemberDO inactive = activeMember(100L, 7L, WorkspaceAccessLevel.READ_ONLY, "[]");
        inactive.setStatus(1);
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 7L)).thenReturn(inactive);
        when(workspaceDao.findById(100L)).thenReturn(workspace(100L, 99L));
        when(systemAdminService.isSystemAdmin(7L)).thenReturn(true);
        when(userDao.findByIdIncludingDeleted(7L)).thenReturn(activeUser(7L));

        // AuthFilter grants ADMIN here too — an inactive row is not a membership — so the view
        // must match instead of reporting 11001. Only an active row caps the admin's level.
        CurrentMembershipVO result = service.currentMembership(100L, 7L);

        assertEquals(WorkspaceAccessLevel.ADMIN, result.getAccessLevel());
        assertFalse(result.isOwner());
        assertNull(result.getJoinedAt());
    }

    @Test
    void updateMemberRolePersistsExactLevelAndAuditsOldAndNewValues() {
        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace(100L, 7L));
        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 8L))
                .thenReturn(activeMember(100L, 8L, WorkspaceAccessLevel.READ_ONLY, "[]"));
        when(workspaceMemberDao.updateRole(100L, 8L, 12L, 7L)).thenReturn(1);

        service.updateMemberRole(100L, 8L, 12L, 7L);

        InOrder order = inOrder(workspaceDao, workspaceMemberDao);
        order.verify(workspaceDao).findByIdForUpdate(100L);
        order.verify(workspaceMemberDao).findByWorkspaceAndUserForUpdate(100L, 8L);
        order.verify(workspaceMemberDao).updateRole(100L, 8L, 12L, 7L);
        verify(workspaceMemberDao, never()).findByWorkspaceAndUser(100L, 8L);
        AuditLogRecord audit = capturedAudit();
        assertAudit(audit, 100L, 7L, "MEMBER_ROLE_CHANGED", "MEMBER", 8L);
        assertEquals(11L, audit.getDetail().get("oldRoleId"));
        assertEquals(12L, audit.getDetail().get("newRoleId"));
        assertEquals(7L, audit.getDetail().get("operatorId"));
        assertEquals(8L, audit.getDetail().get("targetUserId"));
    }

    @Test
    void updateMemberRoleRejectsSelfOwnerAndInactiveMember() {
        assertCode("12010", () ->
                service.updateMemberRole(100L, 7L, 13L, 7L));

        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace(100L, 8L));
        assertCode("12009", () ->
                service.updateMemberRole(100L, 8L, 12L, 7L));
        verify(workspaceMemberDao, never()).findByWorkspaceAndUser(100L, 8L);
        verify(workspaceMemberDao, never()).findByWorkspaceAndUserForUpdate(100L, 8L);
        verify(workspaceMemberDao, never()).updateRole(anyLong(), anyLong(), any(), anyLong());

        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace(100L, 7L));
        WorkspaceMemberDO inactive = activeMember(100L, 8L, WorkspaceAccessLevel.READ_ONLY, "[]");
        inactive.setStatus(1);
        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 8L)).thenReturn(inactive);
        assertCode("11001", () ->
                service.updateMemberRole(100L, 8L, 12L, 7L));


    }

    @Test
    void updateMemberRoleRequiresExactlyOneUpdatedRowAndDoesNotAuditFailure() {
        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace(100L, 7L));
        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 8L))
                .thenReturn(activeMember(100L, 8L, WorkspaceAccessLevel.READ_ONLY, "[]"));

        assertCode("10409", () ->
                service.updateMemberRole(100L, 8L, 12L, 7L));

        verifyNoInteractions(auditLogService);
    }

    @Test
    void updateMemberRolePropagatesRequiredAuditFailure() {
        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace(100L, 7L));
        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 8L))
                .thenReturn(activeMember(100L, 8L, WorkspaceAccessLevel.READ_ONLY, "[]"));
        when(workspaceMemberDao.updateRole(100L, 8L, 12L, 7L)).thenReturn(1);
        IllegalStateException failure = new IllegalStateException("audit database unavailable");
        doThrow(failure).when(auditLogService).recordRequired(any(AuditLogRecord.class));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.updateMemberRole(100L, 8L, 12L, 7L));

        assertSame(failure, thrown);
        verify(workspaceMemberDao).updateRole(100L, 8L, 12L, 7L);
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
        assertTransactional("updateMemberRole",
                long.class, long.class, Long.class, long.class);
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
        when(workspaceMemberDao.updateRole(100L, 8L, 13L, 7L)).thenReturn(1);
        when(workspaceDao.updateOwner(100L, 7L, 8L, 7L)).thenReturn(1);

        service.transferOwner(100L, 8L, 7L);

        InOrder order = inOrder(workspaceDao, workspaceMemberDao, auditLogService);
        order.verify(workspaceDao).findByIdForUpdate(100L);
        order.verify(workspaceMemberDao).findByWorkspaceAndUserForUpdate(100L, 7L);
        order.verify(workspaceMemberDao).findByWorkspaceAndUserForUpdate(100L, 8L);
        order.verify(workspaceMemberDao).updateRole(100L, 8L, 13L, 7L);
        order.verify(workspaceDao).updateOwner(100L, 7L, 8L, 7L);
        ArgumentCaptor<AuditLogRecord> captor = ArgumentCaptor.forClass(AuditLogRecord.class);
        order.verify(auditLogService).recordRequired(captor.capture());
        verify(workspaceMemberDao, never()).updateRole(100L, 7L, 12L, 7L);
        AuditLogRecord audit = captor.getValue();
        assertAudit(audit, 100L, 7L, "ORG_OWNER_TRANSFERRED", "ORG", 100L);
        assertEquals(7L, audit.getDetail().get("oldOwnerId"));
        assertEquals(8L, audit.getDetail().get("newOwnerId"));
        assertEquals(7L, audit.getDetail().get("operatorId"));
        assertEquals(8L, audit.getDetail().get("targetUserId"));
    }

    @Test
    void transferOwnerPromotesTargetWithoutChangingRolePermissionsOrLegacyData() {
        WorkspaceDO workspace = workspace(100L, 7L);
        WorkspaceMemberDO owner = activeMember(100L, 7L, WorkspaceAccessLevel.ADMIN, "[]");
        WorkspaceMemberDO target = activeMember(100L, 8L, WorkspaceAccessLevel.READ_WRITE, "[]");
        target.setReadOnlyDomains("[\"WORKSPACE\",\"SDLC\"]");
        target.setForbiddenDomains("[\"SDLC\"]");
        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace);
        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 7L)).thenReturn(owner);
        when(workspaceMemberDao.findByWorkspaceAndUserForUpdate(100L, 8L)).thenReturn(target);
        when(workspaceMemberDao.updateRole(100L, 8L, 13L, 7L)).thenReturn(1);
        when(workspaceDao.updateOwner(100L, 7L, 8L, 7L)).thenReturn(1);

        service.transferOwner(100L, 8L, 7L);

        InOrder order = inOrder(workspaceDao, workspaceMemberDao);
        order.verify(workspaceMemberDao).updateRole(100L, 8L, 13L, 7L);
        order.verify(workspaceDao).updateOwner(100L, 7L, 8L, 7L);
        assertEquals("[\"WORKSPACE\",\"SDLC\"]", target.getReadOnlyDomains());
        assertEquals("[\"SDLC\"]", target.getForbiddenDomains());
        assertAudit(capturedAudit(), 100L, 7L, "ORG_OWNER_TRANSFERRED", "ORG", 100L);
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

        when(workspaceMemberDao.updateRole(100L, 8L, 13L, 7L)).thenReturn(1);
        assertCode("12011", () -> service.transferOwner(100L, 8L, 7L));

        verifyNoInteractions(auditLogService);
    }

    @Test
    void roleDomainsAreReadLiveAndAdministratorAlwaysHasAllPermissions() {
        var member = activeMember(100L, 8L, WorkspaceAccessLevel.READ_WRITE, "[]");
        member.setReadOnlyDomains("[\"SDLC\"]");
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 8L)).thenReturn(member);
        assertEquals(java.util.Set.of(WorkspaceDomain.SDLC), service.activeReadOnlyDomains(100L, 8L));
        member.setReadOnlyDomains("[\"MEMORY\"]");
        assertEquals(java.util.Set.of(WorkspaceDomain.MEMORY), service.activeReadOnlyDomains(100L, 8L));
        member.setAccessLevel("ADMIN");
        assertTrue(service.activeReadOnlyDomains(100L, 8L).isEmpty());
        member.setAccessLevel("READ_ONLY");
        assertEquals(WorkspaceDomain.values().length, service.activeReadOnlyDomains(100L, 8L).size());
    }

    @Test
    void addingMemberCanBindAScopedCustomRole() {
        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace(100L, 7L));
        when(userDao.findById(8L)).thenReturn(activeUser(8L));
        service.addMember(100L, 8L, 12L, 7L);
        verify(roleService).requireRole(100L, 12L);
        verify(workspaceMemberDao).insertOrActivate(argThat(member -> member.getRoleId() == 12L));
    }

    private static WorkspaceRoleDO role(WorkspaceAccessLevel level) {
        WorkspaceRoleDO role = new WorkspaceRoleDO();
        role.setId(11L + level.ordinal());
        role.setCode(level.name());
        role.setAccessLevel(level.name());
        return role;
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
        member.setRoleId(11L + level.ordinal());
        member.setRoleCode(level.name());
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
    void activeAccessLevelGrantsAdminToAPlatformAdminWithoutAMembership() {
        when(systemAdminService.isSystemAdmin(7L)).thenReturn(true);

        // Shared by MCP personal tokens: the same user gets the same cross-workspace rule on the
        // page and over MCP without holding a member row.
        assertEquals(WorkspaceAccessLevel.ADMIN, service.activeAccessLevel(10L, 7L));
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

    @Test
    void allowsCopyTargetGrantsPlatformAdminWithoutAnyMembership() {
        when(systemAdminService.isSystemAdmin(7L)).thenReturn(true);

        assertTrue(service.allowsCopyTarget(10L, 7L));

        verify(workspaceMemberDao, never()).findByWorkspaceAndUser(anyLong(), anyLong());
    }

    @Test
    void allowsCopyTargetRequiresActiveWriteOrAdminMembership() {
        when(workspaceMemberDao.findByWorkspaceAndUser(10L, 7L))
                .thenReturn(activeMember(10L, 7L, WorkspaceAccessLevel.READ_WRITE, null));
        assertTrue(service.allowsCopyTarget(10L, 7L));

        when(workspaceMemberDao.findByWorkspaceAndUser(10L, 7L))
                .thenReturn(activeMember(10L, 7L, WorkspaceAccessLevel.ADMIN, null));
        assertTrue(service.allowsCopyTarget(10L, 7L));

        when(workspaceMemberDao.findByWorkspaceAndUser(10L, 7L))
                .thenReturn(activeMember(10L, 7L, WorkspaceAccessLevel.READ_ONLY, null));
        assertFalse(service.allowsCopyTarget(10L, 7L));
    }

    @Test
    void allowsCopyTargetRejectsMissingInactiveOrInvalidMembership() {
        when(workspaceMemberDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(null);
        assertFalse(service.allowsCopyTarget(10L, 7L));

        WorkspaceMemberDO disabled = activeMember(10L, 7L, WorkspaceAccessLevel.ADMIN, null);
        disabled.setStatus(1);
        when(workspaceMemberDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(disabled);
        assertFalse(service.allowsCopyTarget(10L, 7L));

        WorkspaceMemberDO removed = activeMember(10L, 7L, WorkspaceAccessLevel.ADMIN, null);
        removed.setIsDeleted(1);
        when(workspaceMemberDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(removed);
        assertFalse(service.allowsCopyTarget(10L, 7L));

        WorkspaceMemberDO invalid = activeMember(10L, 7L, WorkspaceAccessLevel.READ_WRITE, null);
        invalid.setAccessLevel("writer");
        when(workspaceMemberDao.findByWorkspaceAndUser(10L, 7L)).thenReturn(invalid);
        assertFalse(service.allowsCopyTarget(10L, 7L));
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "workspaceMissing|10404|工作空间不存在|Workspace does not exist.",
        "userEmpty|10001|用户不能为空|User is required.",
        "userMissing|10404|用户不存在|User does not exist.",
        "userInactive|10001|用户不可添加|User cannot be added."
    })
    void customMembershipGuardRendersEnglishWithoutMutation(
            String branch, String code, String diagnostic, String english) {
        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace(100L, 7L));
        if (branch.equals("userInactive")) {
            UserDO user = new UserDO(); user.setId(8L); user.setStatus(1);
            when(userDao.findById(8L)).thenReturn(user);
        }
        BizException error = assertThrows(BizException.class, () -> {
            if (branch.equals("workspaceMissing")) service.getCurrent(100L);
            else service.addMember(100L, branch.equals("userEmpty") ? null : 8L, null, 7L);
        });
        assertEquals(BizException.class, error.getClass());
        assertEquals(code, error.getCode());
        assertEquals(diagnostic, error.getMessage());
        assertNull(error.getCause());
        when(workspaceDao.findByIdForUpdate(100L)).thenReturn(workspace(100L, 7L));
        if (branch.equals("userInactive")) {
            verify(workspaceMemberDao).findByWorkspaceAndUser(100L, 8L);
            verify(workspaceMemberDao, never()).insertOrActivate(any());
        } else verifyNoInteractions(workspaceMemberDao);
        verifyNoInteractions(auditLogService, eventPublisher);
        assertEquals(code.equals("10001") ? 400 : 200,
                new com.aliyun.autowonder.common.web.GlobalExceptionHandler().handleBiz(error).getStatusCodeValue());
        assertEquals(diagnostic, com.aliyun.autowonder.common.i18n.SystemMessages.render(
                error.getMessageKey(), error.getMessageArgs(), error.getMessage(), java.util.Locale.CHINA));
        assertEquals(english, com.aliyun.autowonder.common.i18n.SystemMessages.render(
                error.getMessageKey(), error.getMessageArgs(), error.getMessage(), java.util.Locale.US));
    }

}
