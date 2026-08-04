package com.aliyun.autowonder.org;

import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.jwt.TokenPayload;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.org.dto.CreateOrgRequest;
import com.aliyun.autowonder.org.dto.CurrentMembershipVO;
import com.aliyun.autowonder.org.dto.MemberVO;
import com.aliyun.autowonder.org.dto.OrgVO;
import com.aliyun.autowonder.org.dto.SwitchOrgResponse;
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

class OrgServiceTest {

    private OrgDao orgDao;
    private OrgMemberDao orgMemberDao;
    private StatusTemplateSeeder statusTemplateSeeder;
    private JwtService jwtService;
    private UserDao userDao;
    private AuditLogService auditLogService;
    private OrgService service;

    @BeforeEach
    void setUp() {
        orgDao = mock(OrgDao.class);
        orgMemberDao = mock(OrgMemberDao.class);
        statusTemplateSeeder = mock(StatusTemplateSeeder.class);
        userDao = mock(UserDao.class);
        auditLogService = mock(AuditLogService.class);
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties props = new JwtProperties(env);
        props.setSecret("test-secret-key-that-is-long-enough-32bytes!");
        jwtService = new JwtService(props);
        service = new OrgService(orgDao, orgMemberDao, statusTemplateSeeder,
                jwtService, userDao, auditLogService);
    }

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void createPersistsOwnerAsAdminWithoutRolesAndSeedsStatusTemplates() {
        doAnswer(invocation -> {
            ((OrgDO) invocation.getArgument(0)).setId(10L);
            return null;
        }).when(orgDao).insert(any(OrgDO.class));
        CreateOrgRequest request = new CreateOrgRequest();
        request.setName(" Acme ");

        OrgVO result = service.create(request, 7L);

        assertEquals(10L, result.getId());
        assertEquals("Acme", result.getName());
        verify(orgMemberDao).insert(argThat(member ->
                Long.valueOf(10L).equals(member.getTenantId())
                        && Long.valueOf(7L).equals(member.getUserId())
                        && Integer.valueOf(0).equals(member.getStatus())
                        && OrgAccessLevel.ADMIN.name().equals(member.getAccessLevel())
                        && "[]".equals(member.getIdentityTags())
                        && Long.valueOf(7L).equals(member.getCreatorId())));
        verify(statusTemplateSeeder).seed(10L, 7L);
    }

    @Test
    void createRejectsBlankAndDuplicateNames() {
        CreateOrgRequest blank = new CreateOrgRequest();
        blank.setName(" ");
        assertCode("11002", () -> service.create(blank, 7L));

        CreateOrgRequest duplicate = new CreateOrgRequest();
        duplicate.setName(" Acme ");
        when(orgDao.findByName("Acme")).thenReturn(new OrgDO());
        assertCode("11003", () -> service.create(duplicate, 7L));
    }

    @Test
    void addMemberUsesReadOnlyAndEmptyTagsAndActiveMemberIsIdempotent() {
        when(userDao.findById(8L)).thenReturn(activeUser(8L));

        service.addMember(100L, 8L, 7L);

        verify(orgMemberDao).insertOrActivate(argThat(member ->
                Long.valueOf(100L).equals(member.getTenantId())
                        && Long.valueOf(8L).equals(member.getUserId())
                        && Integer.valueOf(0).equals(member.getStatus())
                        && OrgAccessLevel.READ_ONLY.name().equals(member.getAccessLevel())
                        && "[]".equals(member.getIdentityTags())
                        && Long.valueOf(7L).equals(member.getCreatorId())
                        && Long.valueOf(7L).equals(member.getModifierId())));

        OrgMemberDO active = activeMember(100L, 9L, OrgAccessLevel.READ_WRITE, "[]");
        when(userDao.findById(9L)).thenReturn(activeUser(9L));
        when(orgMemberDao.findByOrgAndUser(100L, 9L)).thenReturn(active);

        service.addMember(100L, 9L, 7L);

        verify(orgMemberDao, never()).insertOrActivate(argThat(member ->
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
    void switchOrgReturnsTokenAndExactAccessLevelAndUpdatesContext() {
        when(orgMemberDao.findByOrgAndUser(100L, 7L))
                .thenReturn(activeMember(100L, 7L, OrgAccessLevel.READ_WRITE, "[]"));
        AutoWonderContext.get().setCurrentOrgId(200L);
        AutoWonderContext.get().setOrgAccessLevel(OrgAccessLevel.READ_ONLY);

        SwitchOrgResponse response = service.switchOrg(100L, 7L);

        TokenPayload payload = jwtService.parse(response.getAccessToken());
        assertEquals(7L, payload.getUserId());
        assertEquals(100L, payload.getCurrentOrgId());
        assertEquals(OrgAccessLevel.READ_WRITE, response.getAccessLevel());
        assertEquals(100L, AutoWonderContext.get().getCurrentOrgId());
        assertEquals(OrgAccessLevel.READ_WRITE, AutoWonderContext.get().getOrgAccessLevel());
    }

    @Test
    void switchOrgRejectsMissingInactiveDeletedAndInvalidLevelMemberships() {
        assertCode("11001", () -> service.switchOrg(100L, 7L));

        OrgMemberDO inactive = activeMember(100L, 7L, OrgAccessLevel.READ_ONLY, "[]");
        inactive.setStatus(1);
        when(orgMemberDao.findByOrgAndUser(100L, 7L)).thenReturn(inactive);
        assertCode("11001", () -> service.switchOrg(100L, 7L));

        OrgMemberDO deleted = activeMember(100L, 7L, OrgAccessLevel.READ_ONLY, "[]");
        deleted.setIsDeleted(1);
        when(orgMemberDao.findByOrgAndUser(100L, 7L)).thenReturn(deleted);
        assertCode("11001", () -> service.switchOrg(100L, 7L));

        OrgMemberDO invalid = activeMember(100L, 7L, OrgAccessLevel.READ_ONLY, "[]");
        invalid.setAccessLevel("admin");
        when(orgMemberDao.findByOrgAndUser(100L, 7L)).thenReturn(invalid);
        assertCode("12007", () -> service.switchOrg(100L, 7L));
    }

    @Test
    void listMembersMapsIdentityOwnerLevelAndNormalizedTags() {
        OrgDO org = org(100L, 7L);
        Date joinedAt = new Date(1234L);
        OrgMemberDO member = activeMember(
                100L, 8L, OrgAccessLevel.READ_WRITE, "[\" reviewer \",\"developer\",\"reviewer\"]");
        member.setJoinedAt(joinedAt);
        UserDO user = activeUser(8L);
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setNickname("Alice");
        when(orgDao.findById(100L)).thenReturn(org);
        when(orgMemberDao.listByTenant(100L)).thenReturn(List.of(member));
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
        assertEquals(OrgAccessLevel.READ_WRITE, actual.getAccessLevel());
        assertEquals(List.of("reviewer", "developer"), actual.getIdentityTags());
    }

    @Test
    void currentMembershipMapsCurrentActiveMemberAndRejectsMissingOrInactive() {
        OrgMemberDO member = activeMember(
                100L, 7L, OrgAccessLevel.ADMIN, "[\" owner \",\"owner\"]");
        UserDO user = activeUser(7L);
        user.setUsername("owner");
        when(orgDao.findById(100L)).thenReturn(org(100L, 7L));
        when(orgMemberDao.findByOrgAndUser(100L, 7L)).thenReturn(member);
        when(userDao.findById(7L)).thenReturn(user);

        CurrentMembershipVO result = service.currentMembership(100L, 7L);

        assertEquals(7L, result.getUserId());
        assertEquals("owner", result.getUsername());
        assertTrue(result.isOwner());
        assertEquals(OrgAccessLevel.ADMIN, result.getAccessLevel());
        assertEquals(List.of("owner"), result.getIdentityTags());

        when(orgMemberDao.findByOrgAndUser(100L, 7L)).thenReturn(null);
        assertCode("11001", () -> service.currentMembership(100L, 7L));

        member.setStatus(1);
        when(orgMemberDao.findByOrgAndUser(100L, 7L)).thenReturn(member);
        assertCode("11001", () -> service.currentMembership(100L, 7L));
    }

    @Test
    void currentMembershipReusesTheMemberValidatedByTheRequestFilter() {
        OrgMemberDO member = activeMember(
                100L, 7L, OrgAccessLevel.ADMIN, "[\"owner\"]");
        AutoWonderContext context = AutoWonderContext.get();
        context.setUserId(7L);
        context.setCurrentOrgId(100L);
        context.setOrgAccessLevel(OrgAccessLevel.ADMIN);
        context.setOrgMember(member);
        when(orgDao.findById(100L)).thenReturn(org(100L, 7L));
        when(userDao.findById(7L)).thenReturn(activeUser(7L));

        CurrentMembershipVO result = service.currentMembership(100L, 7L);

        assertEquals(OrgAccessLevel.ADMIN, result.getAccessLevel());
        assertEquals(List.of("owner"), result.getIdentityTags());
        verify(orgMemberDao, never()).findByOrgAndUser(100L, 7L);
    }

    @Test
    void updateMemberAccessPersistsExactLevelAndAuditsOldAndNewValues() {
        when(orgDao.findByIdForUpdate(100L)).thenReturn(org(100L, 7L));
        when(orgMemberDao.findByOrgAndUserForUpdate(100L, 8L))
                .thenReturn(activeMember(100L, 8L, OrgAccessLevel.READ_ONLY, "[]"));
        when(orgMemberDao.updateAccessLevel(100L, 8L, "READ_WRITE", 7L)).thenReturn(1);

        service.updateMemberAccess(100L, 8L, OrgAccessLevel.READ_WRITE, 7L);

        InOrder order = inOrder(orgDao, orgMemberDao);
        order.verify(orgDao).findByIdForUpdate(100L);
        order.verify(orgMemberDao).findByOrgAndUserForUpdate(100L, 8L);
        order.verify(orgMemberDao).updateAccessLevel(100L, 8L, "READ_WRITE", 7L);
        verify(orgMemberDao, never()).findByOrgAndUser(100L, 8L);
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
                service.updateMemberAccess(100L, 7L, OrgAccessLevel.ADMIN, 7L));

        when(orgDao.findByIdForUpdate(100L)).thenReturn(org(100L, 8L));
        assertCode("12009", () ->
                service.updateMemberAccess(100L, 8L, OrgAccessLevel.READ_WRITE, 7L));
        verify(orgMemberDao, never()).findByOrgAndUser(100L, 8L);
        verify(orgMemberDao, never()).findByOrgAndUserForUpdate(100L, 8L);
        verify(orgMemberDao, never()).updateAccessLevel(anyLong(), anyLong(), any(), anyLong());

        when(orgDao.findByIdForUpdate(100L)).thenReturn(org(100L, 7L));
        OrgMemberDO inactive = activeMember(100L, 8L, OrgAccessLevel.READ_ONLY, "[]");
        inactive.setStatus(1);
        when(orgMemberDao.findByOrgAndUserForUpdate(100L, 8L)).thenReturn(inactive);
        assertCode("11001", () ->
                service.updateMemberAccess(100L, 8L, OrgAccessLevel.READ_WRITE, 7L));

        OrgMemberDO invalid = activeMember(100L, 8L, OrgAccessLevel.READ_ONLY, "[]");
        invalid.setAccessLevel("READ");
        when(orgMemberDao.findByOrgAndUserForUpdate(100L, 8L)).thenReturn(invalid);
        assertCode("12007", () ->
                service.updateMemberAccess(100L, 8L, OrgAccessLevel.READ_WRITE, 7L));
    }

    @Test
    void updateMemberAccessRequiresExactlyOneUpdatedRowAndDoesNotAuditFailure() {
        when(orgDao.findByIdForUpdate(100L)).thenReturn(org(100L, 7L));
        when(orgMemberDao.findByOrgAndUserForUpdate(100L, 8L))
                .thenReturn(activeMember(100L, 8L, OrgAccessLevel.READ_ONLY, "[]"));

        assertCode("10409", () ->
                service.updateMemberAccess(100L, 8L, OrgAccessLevel.READ_WRITE, 7L));

        verifyNoInteractions(auditLogService);
    }

    @Test
    void updateMemberAccessPropagatesRequiredAuditFailure() {
        when(orgDao.findByIdForUpdate(100L)).thenReturn(org(100L, 7L));
        when(orgMemberDao.findByOrgAndUserForUpdate(100L, 8L))
                .thenReturn(activeMember(100L, 8L, OrgAccessLevel.READ_ONLY, "[]"));
        when(orgMemberDao.updateAccessLevel(100L, 8L, "READ_WRITE", 7L)).thenReturn(1);
        IllegalStateException failure = new IllegalStateException("audit database unavailable");
        doThrow(failure).when(auditLogService).recordRequired(any(AuditLogRecord.class));

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                service.updateMemberAccess(100L, 8L, OrgAccessLevel.READ_WRITE, 7L));

        assertSame(failure, thrown);
        verify(orgMemberDao).updateAccessLevel(100L, 8L, "READ_WRITE", 7L);
    }

    @Test
    void updateMemberIdentityTagsNormalizesPersistsAndAuditsLists() {
        when(orgMemberDao.findByOrgAndUserForUpdate(100L, 8L))
                .thenReturn(activeMember(100L, 8L, OrgAccessLevel.READ_ONLY, "[\"reviewer\"]"));
        when(orgMemberDao.updateIdentityTags(
                100L, 8L, "[\"developer\",\"reviewer\"]", 7L)).thenReturn(1);

        service.updateMemberIdentityTags(
                100L, 8L, List.of(" developer ", "reviewer", "developer"), 7L);

        InOrder order = inOrder(orgMemberDao, auditLogService);
        order.verify(orgMemberDao).findByOrgAndUserForUpdate(100L, 8L);
        order.verify(orgMemberDao).updateIdentityTags(
                100L, 8L, "[\"developer\",\"reviewer\"]", 7L);
        verify(orgMemberDao, never()).findByOrgAndUser(100L, 8L);
        AuditLogRecord audit = capturedAudit();
        assertAudit(audit, 100L, 7L, "MEMBER_IDENTITY_TAGS_CHANGED", "MEMBER", 8L);
        assertEquals(List.of("reviewer"), audit.getDetail().get("oldIdentityTags"));
        assertEquals(List.of("developer", "reviewer"), audit.getDetail().get("newIdentityTags"));
        assertEquals(7L, audit.getDetail().get("operatorId"));
        assertEquals(8L, audit.getDetail().get("targetUserId"));
    }

    @Test
    void updateMemberIdentityTagsRejectsInactiveAndRequiresExactlyOneUpdatedRow() {
        OrgMemberDO inactive = activeMember(100L, 8L, OrgAccessLevel.READ_ONLY, "[]");
        inactive.setStatus(1);
        when(orgMemberDao.findByOrgAndUserForUpdate(100L, 8L)).thenReturn(inactive);
        assertCode("11001", () ->
                service.updateMemberIdentityTags(100L, 8L, List.of("developer"), 7L));

        when(orgMemberDao.findByOrgAndUserForUpdate(100L, 8L))
                .thenReturn(activeMember(100L, 8L, OrgAccessLevel.READ_ONLY, "[]"));
        assertCode("10409", () ->
                service.updateMemberIdentityTags(100L, 8L, List.of("developer"), 7L));

        verifyNoInteractions(auditLogService);
    }

    @Test
    void removeMemberProtectsLockedOwnerWithoutMutatingMembership() {
        when(orgDao.findByIdForUpdate(100L)).thenReturn(org(100L, 7L));

        assertCode("12009", () -> service.removeMember(100L, 7L, 9L));

        verify(orgDao).findByIdForUpdate(100L);
        verify(orgMemberDao, never()).findByOrgAndUserForUpdate(anyLong(), anyLong());
        verify(orgMemberDao, never()).softDelete(anyLong(), anyLong(), anyLong());
    }

    @Test
    void removeMemberLocksActiveTargetAndRequiresExactlyOneDeletedRow() {
        when(orgDao.findByIdForUpdate(100L)).thenReturn(org(100L, 7L));
        when(orgMemberDao.findByOrgAndUserForUpdate(100L, 8L))
                .thenReturn(activeMember(100L, 8L, OrgAccessLevel.READ_ONLY, "[]"));
        when(orgMemberDao.softDelete(100L, 8L, 7L)).thenReturn(1);

        service.removeMember(100L, 8L, 7L);

        InOrder order = inOrder(orgDao, orgMemberDao);
        order.verify(orgDao).findByIdForUpdate(100L);
        order.verify(orgMemberDao).findByOrgAndUserForUpdate(100L, 8L);
        order.verify(orgMemberDao).softDelete(100L, 8L, 7L);
        verify(orgMemberDao, never()).findByOrgAndUser(100L, 8L);
    }

    @Test
    void removeMemberRejectsMissingInactiveAndZeroRowTargets() {
        when(orgDao.findByIdForUpdate(100L)).thenReturn(org(100L, 7L));

        assertCode("11001", () -> service.removeMember(100L, 8L, 7L));
        verify(orgMemberDao, never()).softDelete(anyLong(), anyLong(), anyLong());

        OrgMemberDO inactive = activeMember(100L, 8L, OrgAccessLevel.READ_ONLY, "[]");
        inactive.setStatus(1);
        when(orgMemberDao.findByOrgAndUserForUpdate(100L, 8L)).thenReturn(inactive);
        assertCode("11001", () -> service.removeMember(100L, 8L, 7L));
        verify(orgMemberDao, never()).softDelete(anyLong(), anyLong(), anyLong());

        when(orgMemberDao.findByOrgAndUserForUpdate(100L, 8L))
                .thenReturn(activeMember(100L, 8L, OrgAccessLevel.READ_ONLY, "[]"));
        assertCode("10409", () -> service.removeMember(100L, 8L, 7L));
    }

    @Test
    void governanceMutationMethodsAreTransactional() throws Exception {
        assertTransactional("updateMemberAccess",
                long.class, long.class, OrgAccessLevel.class, long.class);
        assertTransactional("updateMemberIdentityTags",
                long.class, long.class, List.class, long.class);
        assertTransactional("removeMember", long.class, long.class, long.class);
        assertTransactional("transferOwner", long.class, long.class, long.class);
    }

    @Test
    void transferOwnerLocksRowsPromotesTargetConditionallyUpdatesOwnerAndAudits() throws Exception {
        assertNotNull(OrgService.class
                .getDeclaredMethod("transferOwner", long.class, long.class, long.class)
                .getAnnotation(Transactional.class));
        OrgDO org = org(100L, 7L);
        OrgMemberDO owner = activeMember(100L, 7L, OrgAccessLevel.ADMIN, "[\"founder\"]");
        OrgMemberDO target = activeMember(100L, 8L, OrgAccessLevel.READ_WRITE, "[]");
        when(orgDao.findByIdForUpdate(100L)).thenReturn(org);
        when(orgMemberDao.findByOrgAndUserForUpdate(100L, 7L)).thenReturn(owner);
        when(orgMemberDao.findByOrgAndUserForUpdate(100L, 8L)).thenReturn(target);
        when(orgMemberDao.updateAccessLevel(100L, 8L, "ADMIN", 7L)).thenReturn(1);
        when(orgDao.updateOwner(100L, 7L, 8L, 7L)).thenReturn(1);

        service.transferOwner(100L, 8L, 7L);

        InOrder order = inOrder(orgDao, orgMemberDao, auditLogService);
        order.verify(orgDao).findByIdForUpdate(100L);
        order.verify(orgMemberDao).findByOrgAndUserForUpdate(100L, 7L);
        order.verify(orgMemberDao).findByOrgAndUserForUpdate(100L, 8L);
        order.verify(orgMemberDao).updateAccessLevel(100L, 8L, "ADMIN", 7L);
        order.verify(orgDao).updateOwner(100L, 7L, 8L, 7L);
        ArgumentCaptor<AuditLogRecord> captor = ArgumentCaptor.forClass(AuditLogRecord.class);
        order.verify(auditLogService).recordRequired(captor.capture());
        verify(orgMemberDao, never()).updateAccessLevel(100L, 7L, "READ_WRITE", 7L);
        AuditLogRecord audit = captor.getValue();
        assertAudit(audit, 100L, 7L, "ORG_OWNER_TRANSFERRED", "ORG", 100L);
        assertEquals(7L, audit.getDetail().get("oldOwnerId"));
        assertEquals(8L, audit.getDetail().get("newOwnerId"));
        assertEquals(7L, audit.getDetail().get("operatorId"));
        assertEquals(8L, audit.getDetail().get("targetUserId"));
    }

    @Test
    void transferOwnerRejectsNonOwnerSameTargetAndInactiveMemberships() {
        when(orgDao.findByIdForUpdate(100L)).thenReturn(org(100L, 7L));
        assertCode("12011", () -> service.transferOwner(100L, 8L, 9L));
        assertCode("12011", () -> service.transferOwner(100L, 7L, 7L));

        when(orgMemberDao.findByOrgAndUserForUpdate(100L, 7L))
                .thenReturn(activeMember(100L, 7L, OrgAccessLevel.ADMIN, "[]"));
        OrgMemberDO inactiveTarget = activeMember(100L, 8L, OrgAccessLevel.READ_ONLY, "[]");
        inactiveTarget.setStatus(1);
        when(orgMemberDao.findByOrgAndUserForUpdate(100L, 8L)).thenReturn(inactiveTarget);
        assertCode("12011", () -> service.transferOwner(100L, 8L, 7L));

        verify(orgDao, never()).updateOwner(anyLong(), anyLong(), anyLong(), anyLong());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void transferOwnerRejectsInvalidOwnerMembershipAndFailedUpdates() {
        when(orgDao.findByIdForUpdate(100L)).thenReturn(org(100L, 7L));
        OrgMemberDO invalidOwner = activeMember(100L, 7L, OrgAccessLevel.READ_WRITE, "[]");
        when(orgMemberDao.findByOrgAndUserForUpdate(100L, 7L)).thenReturn(invalidOwner);
        when(orgMemberDao.findByOrgAndUserForUpdate(100L, 8L))
                .thenReturn(activeMember(100L, 8L, OrgAccessLevel.READ_ONLY, "[]"));
        assertCode("12011", () -> service.transferOwner(100L, 8L, 7L));

        when(orgMemberDao.findByOrgAndUserForUpdate(100L, 7L))
                .thenReturn(activeMember(100L, 7L, OrgAccessLevel.ADMIN, "[]"));
        assertCode("12011", () -> service.transferOwner(100L, 8L, 7L));

        when(orgMemberDao.updateAccessLevel(100L, 8L, "ADMIN", 7L)).thenReturn(1);
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

    private static OrgMemberDO activeMember(long tenantId, long userId,
                                            OrgAccessLevel level, String tags) {
        OrgMemberDO member = new OrgMemberDO();
        member.setTenantId(tenantId);
        member.setUserId(userId);
        member.setStatus(0);
        member.setIsDeleted(0);
        member.setAccessLevel(level.name());
        member.setIdentityTags(tags);
        return member;
    }

    @Test
    void listByUserWithAccessReadsOrganizationsAndLevelsInOneQuery() {
        when(orgDao.listMembershipsByUser(7L)).thenReturn(List.of(
                membership(10L, "first", OrgAccessLevel.ADMIN),
                membership(20L, "second", OrgAccessLevel.READ_ONLY)));

        var orgs = service.listByUserWithAccess(7L);

        assertEquals(List.of(10L, 20L), orgs.stream().map(OrgVO::getId).toList());
        assertEquals(List.of(OrgAccessLevel.ADMIN, OrgAccessLevel.READ_ONLY),
                orgs.stream().map(OrgVO::getAccessLevel).toList());
        verify(orgMemberDao, never()).findByOrgAndUser(anyLong(), anyLong());
    }

    @Test
    void listByUserWithAccessRejectsAnInvalidPersistedLevel() {
        OrgMembershipDO invalid = membership(10L, "first", OrgAccessLevel.ADMIN);
        invalid.setAccessLevel("admin");
        when(orgDao.listMembershipsByUser(7L)).thenReturn(List.of(invalid));

        assertCode("12007", () -> service.listByUserWithAccess(7L));
    }

    @Test
    void scopedOrgReportsThePinnedOrganizationWithTheScopeLevel() {
        when(orgDao.findById(10L)).thenReturn(org(10L, 7L));

        OrgVO scoped = service.scopedOrg(10L, OrgAccessLevel.READ_WRITE);

        assertEquals(10L, scoped.getId());
        assertEquals(OrgAccessLevel.READ_WRITE, scoped.getAccessLevel());
    }

    @Test
    void activeAccessLevelResolvesTheLiveMembershipLevel() {
        when(orgMemberDao.findByOrgAndUser(10L, 7L))
                .thenReturn(activeMember(10L, 7L, OrgAccessLevel.READ_WRITE, null));

        assertEquals(OrgAccessLevel.READ_WRITE, service.activeAccessLevel(10L, 7L));
    }

    @Test
    void activeAccessLevelRejectsNonMembersAndInvalidLevels() {
        when(orgMemberDao.findByOrgAndUser(10L, 7L)).thenReturn(null);
        assertCode("11001", () -> service.activeAccessLevel(10L, 7L));

        OrgMemberDO removed = activeMember(10L, 7L, OrgAccessLevel.ADMIN, null);
        removed.setIsDeleted(1);
        when(orgMemberDao.findByOrgAndUser(10L, 7L)).thenReturn(removed);
        assertCode("11001", () -> service.activeAccessLevel(10L, 7L));

        OrgMemberDO invalid = activeMember(10L, 7L, OrgAccessLevel.ADMIN, null);
        invalid.setAccessLevel("admin");
        when(orgMemberDao.findByOrgAndUser(10L, 7L)).thenReturn(invalid);
        assertCode("12007", () -> service.activeAccessLevel(10L, 7L));
    }

    private static OrgMembershipDO membership(long orgId, String name, OrgAccessLevel level) {
        OrgMembershipDO membership = new OrgMembershipDO();
        membership.setId(orgId);
        membership.setName(name);
        membership.setAccessLevel(level.name());
        return membership;
    }

    private static OrgDO org(long id, long ownerId) {
        OrgDO org = new OrgDO();
        org.setId(id);
        org.setOwnerId(ownerId);
        return org;
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
        assertNotNull(OrgService.class.getDeclaredMethod(methodName, parameterTypes)
                .getAnnotation(Transactional.class), methodName + " must be transactional");
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
