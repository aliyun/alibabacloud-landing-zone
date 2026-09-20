package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.audit.AuditLogRecord;
import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.agent.PlatformAgentSeeder;
import com.aliyun.autowonder.statemachine.StatusTemplateSeeder;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.workspace.dto.CreateWorkspaceRequest;
import com.aliyun.autowonder.workspace.dto.RecycleBinItemVO;
import com.aliyun.autowonder.workspace.dto.RestoreWorkspaceRequest;
import com.aliyun.autowonder.workspace.dto.WorkspaceUpdateRequest;
import com.aliyun.autowonder.workspace.dto.WorkspaceVO;
import com.aliyun.autowonder.workspace.event.WorkspaceDeletedEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Lifecycle half of {@link WorkspaceService}: edit (F1), logical delete (F2), recycle bin (F4) and
 * restore (F5). The pre-existing {@code WorkspaceServiceTest} keeps create/membership/switch.
 */
class WorkspaceServiceLifecycleTest {

    private static final long WORKSPACE_ID = 10L;
    private static final long OWNER_ID = 7L;
    private static final long ADMIN_ID = 8L;
    private static final long MEMBER_ID = 9L;
    private static final long OUTSIDER_ID = 11L;
    private static final long PLATFORM_ADMIN_ID = 12L;
    private static final String NAME = "星云工坊";
    private static final Date DELETED_AT = new Date(1_700_000_000_000L);

    private WorkspaceDao workspaceDao;
    private WorkspaceMemberDao workspaceMemberDao;
    private UserDao userDao;
    private AuditLogService auditLogService;
    private SystemAdminService systemAdminService;
    private WorkspaceDeletionLinkage deletionLinkage;
    private ApplicationEventPublisher eventPublisher;
    private WorkspaceService service;

    @BeforeEach
    void setUp() {
        workspaceDao = mock(WorkspaceDao.class);
        workspaceMemberDao = mock(WorkspaceMemberDao.class);
        userDao = mock(UserDao.class);
        auditLogService = mock(AuditLogService.class);
        systemAdminService = mock(SystemAdminService.class);
        deletionLinkage = mock(WorkspaceDeletionLinkage.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties props = new JwtProperties(env);
        props.setSecret("test-secret-key-that-is-long-enough-32bytes!");
        JwtService jwtService = new JwtService(props);
        service = new WorkspaceService(workspaceDao, workspaceMemberDao,
                mock(StatusTemplateSeeder.class), mock(PlatformAgentSeeder.class), jwtService, userDao,
                auditLogService, systemAdminService, deletionLinkage, eventPublisher);

        // Empty defaults, because both bulk helpers below feed an IN (...) list and a bare mock
        // would hand them null instead of an empty collection.
        when(workspaceDao.pageRecycleBin(anyLong(), anyBoolean(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(workspaceDao.listActiveNames(anyCollection())).thenReturn(List.of());
        when(userDao.listByIds(anyCollection())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    // ---------------------------------------------------------------- F1 edit

    @Test
    void updateWritesTheTrimmedNameToBothNameColumnsAndBumpsTheVersion() {
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(activeWorkspace());
        when(workspaceDao.updateDetail(eq(WORKSPACE_ID), anyString(), anyString(), any(), any(),
                anyInt(), anyLong())).thenReturn(1);

        WorkspaceVO result = service.updateWorkspace(WORKSPACE_ID,
                updateRequest("  新名称  ", "  新描述  ", "  新背景  ", 3), OWNER_ID);

        // F3.5: active_name_key has to follow name on every write path, otherwise the renamed
        // workspace silently drops out of the uniqueness index.
        verify(workspaceDao).updateDetail(WORKSPACE_ID, "新名称", "新名称", "新描述", "新背景", 3, OWNER_ID);
        assertEquals("新名称", result.getName());
        assertEquals("新描述", result.getDescription());
        assertEquals("新背景", result.getBackground());
        assertEquals(4, result.getVersion());
        assertEquals(Boolean.TRUE, result.getIsOwner());
        assertEquals(Boolean.TRUE, result.getCanManage());
    }

    @Test
    void updateNormalizesBlankDescriptionAndBackgroundToNull() {
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(activeWorkspace());
        when(workspaceDao.updateDetail(eq(WORKSPACE_ID), anyString(), anyString(), any(), any(),
                anyInt(), anyLong())).thenReturn(1);

        service.updateWorkspace(WORKSPACE_ID, updateRequest(NAME, "   ", "\t", 3), OWNER_ID);

        // F1.4: an empty textarea means "no description", not a row of whitespace that renders as
        // a populated field and then fails the next trim-comparison.
        verify(workspaceDao).updateDetail(WORKSPACE_ID, NAME, NAME, null, null, 3, OWNER_ID);
    }

    @Test
    void updateIsAllowedForAnActiveAdminWhoIsNotTheOwner() {
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(activeWorkspace());
        when(workspaceDao.updateDetail(eq(WORKSPACE_ID), anyString(), anyString(), any(), any(),
                anyInt(), anyLong())).thenReturn(1);
        stubMembership(ADMIN_ID, WorkspaceAccessLevel.ADMIN);

        WorkspaceVO result = service.updateWorkspace(WORKSPACE_ID,
                updateRequest("新名称", null, null, 3), ADMIN_ID);

        assertEquals("新名称", result.getName());
        assertEquals(Boolean.FALSE, result.getIsOwner());
        assertEquals(Boolean.TRUE, result.getCanManage());
        verify(workspaceDao).updateDetail(WORKSPACE_ID, "新名称", "新名称", null, null, 3, ADMIN_ID);
    }

    @ParameterizedTest
    @MethodSource("operatorsWithoutManageRights")
    void updateRejectsOperatorsWithoutManageRights(long operatorId, WorkspaceAccessLevel level) {
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(activeWorkspace());
        if (level != null) {
            stubMembership(operatorId, level);
        }

        assertCode("11006", () -> service.updateWorkspace(WORKSPACE_ID,
                updateRequest("新名称", null, null, 3), operatorId));

        verify(workspaceDao, never()).updateDetail(anyLong(), anyString(), anyString(), any(), any(),
                anyInt(), anyLong());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void updateIsAllowedForAPlatformAdminWhoNeverJoinedTheWorkspace() {
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(activeWorkspace());
        when(systemAdminService.isSystemAdmin(PLATFORM_ADMIN_ID)).thenReturn(true);
        when(workspaceDao.updateDetail(eq(WORKSPACE_ID), anyString(), anyString(), any(), any(),
                anyInt(), anyLong())).thenReturn(1);

        WorkspaceVO result = service.updateWorkspace(WORKSPACE_ID,
                updateRequest("新名称", null, null, 3), PLATFORM_ADMIN_ID);

        // D3 统一平台管理员: is_admin now grants edit rights over every workspace, not just
        // recycle-bin visibility over the ones the admin never joined.
        assertEquals(Boolean.FALSE, result.getIsOwner());
        assertEquals(Boolean.TRUE, result.getCanManage());
        verify(workspaceDao).updateDetail(WORKSPACE_ID, "新名称", "新名称", null, null, 3,
                PLATFORM_ADMIN_ID);
    }

    @Test
    void deleteIsAllowedForAPlatformAdminWhoNeverJoinedTheWorkspace() {
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(activeWorkspace());
        when(workspaceDao.softDelete(WORKSPACE_ID, PLATFORM_ADMIN_ID)).thenReturn(1);
        when(systemAdminService.isSystemAdmin(PLATFORM_ADMIN_ID)).thenReturn(true);

        service.deleteWorkspace(WORKSPACE_ID, PLATFORM_ADMIN_ID);

        verify(workspaceDao).softDelete(WORKSPACE_ID, PLATFORM_ADMIN_ID);
        verify(eventPublisher)
                .publishEvent(new WorkspaceDeletedEvent(WORKSPACE_ID, NAME, PLATFORM_ADMIN_ID));
    }

    @Test
    void updateAndDeleteRejectAPlatformAdminWhoseFlagWasRevoked() {
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(activeWorkspace());
        when(systemAdminService.isSystemAdmin(PLATFORM_ADMIN_ID)).thenReturn(false);

        // The flag was revoked mid-session: isSystemAdmin is re-read from the user row, so the
        // platform-admin branch grants nothing anymore and the operator is a plain non-member.
        assertCode("11006", () -> service.updateWorkspace(WORKSPACE_ID,
                updateRequest("新名称", null, null, 3), PLATFORM_ADMIN_ID));
        assertCode("11006", () -> service.deleteWorkspace(WORKSPACE_ID, PLATFORM_ADMIN_ID));

        verify(workspaceDao, never()).updateDetail(anyLong(), anyString(), anyString(), any(), any(),
                anyInt(), anyLong());
        verify(workspaceDao, never()).softDelete(anyLong(), anyLong());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void updateRejectsAnAdminMembershipThatIsNoLongerActive() {
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(activeWorkspace());
        WorkspaceMemberDO removed = member(ADMIN_ID, WorkspaceAccessLevel.ADMIN);
        removed.setIsDeleted(1);
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, ADMIN_ID)).thenReturn(removed);
        WorkspaceMemberDO left = member(MEMBER_ID, WorkspaceAccessLevel.ADMIN);
        left.setStatus(1);
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, MEMBER_ID)).thenReturn(left);

        assertCode("11006", () -> service.updateWorkspace(WORKSPACE_ID,
                updateRequest("新名称", null, null, 3), ADMIN_ID));
        assertCode("11006", () -> service.updateWorkspace(WORKSPACE_ID,
                updateRequest("新名称", null, null, 3), MEMBER_ID));
    }

    @Test
    void updateValidatesTheBodyBeforeReadingAnyRow() {
        assertCode("10001", () -> service.updateWorkspace(WORKSPACE_ID, null, OWNER_ID));
        assertCode("10001", () -> service.updateWorkspace(WORKSPACE_ID,
                updateRequest(NAME, null, null, null), OWNER_ID));

        // Without a version the optimistic lock cannot be applied at all, so the request is
        // refused before it can reach the database.
        verifyNoInteractions(workspaceDao);
        verifyNoInteractions(auditLogService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    void updateRejectsABlankName(String name) {
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(activeWorkspace());

        assertCode("11002", () -> service.updateWorkspace(WORKSPACE_ID,
                updateRequest(name, null, null, 3), OWNER_ID));
    }

    @Test
    void updateEnforcesTheLengthLimitsInclusively() {
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(activeWorkspace());

        assertCode("10001", () -> service.updateWorkspace(WORKSPACE_ID,
                updateRequest("名".repeat(129), null, null, 3), OWNER_ID));
        assertCode("10001", () -> service.updateWorkspace(WORKSPACE_ID,
                updateRequest(NAME, "述".repeat(513), null, 3), OWNER_ID));

        // F1.4: 128 and 512 are the maxima, not the first rejected lengths.
        when(workspaceDao.updateDetail(eq(WORKSPACE_ID), anyString(), anyString(), any(), any(),
                anyInt(), anyLong())).thenReturn(1);
        service.updateWorkspace(WORKSPACE_ID,
                updateRequest("名".repeat(128), "述".repeat(512), null, 3), OWNER_ID);
        verify(workspaceDao).updateDetail(WORKSPACE_ID, "名".repeat(128), "名".repeat(128),
                "述".repeat(512), null, 3, OWNER_ID);
    }

    @Test
    void updateExcludesItsOwnRowFromTheDuplicateNameCheck() {
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(activeWorkspace());
        when(workspaceDao.updateDetail(eq(WORKSPACE_ID), anyString(), anyString(), any(), any(),
                anyInt(), anyLong())).thenReturn(1);

        service.updateWorkspace(WORKSPACE_ID, updateRequest(NAME, "只改描述", null, 3), OWNER_ID);

        // excludeId is this row: without it, saving any other field while keeping the name would
        // report the workspace as colliding with itself.
        verify(workspaceDao).countActiveByName(NAME, WORKSPACE_ID);
    }

    @Test
    void updateReportsATakenNameWithTheBusinessCodeNotTheSqlException() {
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(activeWorkspace());
        when(workspaceDao.countActiveByName("别的空间", WORKSPACE_ID)).thenReturn(1L);

        assertCode("11003", () -> service.updateWorkspace(WORKSPACE_ID,
                updateRequest("别的空间", null, null, 3), OWNER_ID));

        verify(workspaceDao, never()).updateDetail(anyLong(), anyString(), anyString(), any(), any(),
                anyInt(), anyLong());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void updateReportsAStaleVersionAsAConflictInsteadOfOverwriting() {
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(activeWorkspace());
        when(workspaceDao.updateDetail(eq(WORKSPACE_ID), anyString(), anyString(), any(), any(),
                anyInt(), anyLong())).thenReturn(0);

        // F1.5: the row was locked above, so a zero-row update can only mean the client's version
        // went stale. Silently overwriting would lose the other operator's edit.
        assertCode("11004", () -> service.updateWorkspace(WORKSPACE_ID,
                updateRequest("新名称", null, null, 2), OWNER_ID));

        verifyNoInteractions(auditLogService);
    }

    @Test
    void updateRejectsAMissingOrAlreadyDeletedWorkspace() {
        // findByIdForUpdate filters on is_deleted = 0, so a deleted row reads back as absent and
        // both cases collapse onto one unenumerable code.
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(null);

        assertCode("11006", () -> service.updateWorkspace(WORKSPACE_ID,
                updateRequest("新名称", null, null, 3), OWNER_ID));
    }

    @Test
    void updateRecordsTheOperatorAndBothNamesInTheAuditLog() {
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(activeWorkspace());
        // The audit record has to be attributable to someone other than the owner, otherwise the
        // actorId assertions below would pass even if the operator were read from the wrong place.
        stubMembership(ADMIN_ID, WorkspaceAccessLevel.ADMIN);
        when(workspaceDao.updateDetail(eq(WORKSPACE_ID), anyString(), anyString(), any(), any(),
                anyInt(), anyLong())).thenReturn(1);

        service.updateWorkspace(WORKSPACE_ID, updateRequest("新名称", null, null, 3), ADMIN_ID);

        AuditLogRecord audit = capturedAudit();
        assertEquals("ORG_UPDATED", audit.getAction());
        assertEquals("ORG_UPDATED", audit.getEventType());
        assertEquals("ORG", audit.getModule());
        assertEquals(WORKSPACE_ID, audit.getTenantId());
        assertEquals(ADMIN_ID, audit.getActorId());
        assertEquals("HUMAN", audit.getActorType());
        assertEquals(NAME, audit.getDetail().get("oldName"));
        assertEquals("新名称", audit.getDetail().get("newName"));
        assertEquals(Long.valueOf(ADMIN_ID), audit.getDetail().get("operatorId"));
    }

    // ------------------------------------------------------- F2 logical delete

    @Test
    void deleteSoftDeletesPausesTimersAndAnnouncesTheDispatchLinkage() {
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(activeWorkspace());
        when(workspaceDao.softDelete(WORKSPACE_ID, OWNER_ID)).thenReturn(1);
        when(deletionLinkage.pauseScheduledTasks(WORKSPACE_ID, OWNER_ID)).thenReturn(3);

        service.deleteWorkspace(WORKSPACE_ID, OWNER_ID);

        // D5: the timer half is one SQL statement and has to land before the commit, while the
        // dispatch half reaches remote executors and must not run under the row locks above.
        InOrder order = inOrder(workspaceDao, deletionLinkage, auditLogService, eventPublisher);
        order.verify(workspaceDao).softDelete(WORKSPACE_ID, OWNER_ID);
        order.verify(deletionLinkage).pauseScheduledTasks(WORKSPACE_ID, OWNER_ID);
        order.verify(auditLogService).recordRequired(any(AuditLogRecord.class));
        order.verify(eventPublisher)
                .publishEvent(new WorkspaceDeletedEvent(WORKSPACE_ID, NAME, OWNER_ID));
    }

    @Test
    void deleteIsAllowedForAnActiveAdminWhoIsNotTheOwner() {
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(activeWorkspace());
        when(workspaceDao.softDelete(WORKSPACE_ID, ADMIN_ID)).thenReturn(1);
        stubMembership(ADMIN_ID, WorkspaceAccessLevel.ADMIN);

        service.deleteWorkspace(WORKSPACE_ID, ADMIN_ID);

        verify(workspaceDao).softDelete(WORKSPACE_ID, ADMIN_ID);
        verify(eventPublisher).publishEvent(new WorkspaceDeletedEvent(WORKSPACE_ID, NAME, ADMIN_ID));
    }

    @ParameterizedTest
    @MethodSource("operatorsWithoutManageRights")
    void deleteRejectsOperatorsWithoutManageRights(long operatorId, WorkspaceAccessLevel level) {
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(activeWorkspace());
        if (level != null) {
            stubMembership(operatorId, level);
        }

        assertCode("11006", () -> service.deleteWorkspace(WORKSPACE_ID, operatorId));

        verify(workspaceDao, never()).softDelete(anyLong(), anyLong());
        verifyNoInteractions(deletionLinkage);
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void deleteRejectsAMissingOrAlreadyDeletedWorkspace() {
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(null);

        assertCode("11006", () -> service.deleteWorkspace(WORKSPACE_ID, OWNER_ID));

        verify(workspaceDao, never()).softDelete(anyLong(), anyLong());
    }

    @Test
    void deleteReportsAConflictWhenARacingDeleteAlreadyTookTheRow() {
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(activeWorkspace());
        when(workspaceDao.softDelete(WORKSPACE_ID, OWNER_ID)).thenReturn(0);

        // The is_deleted = 0 guard in softDelete is what turns the second delete into a no-op
        // instead of re-stamping deleted_at and deleted_by with the wrong operator.
        assertCode("10409", () -> service.deleteWorkspace(WORKSPACE_ID, OWNER_ID));

        verifyNoInteractions(deletionLinkage);
        verifyNoInteractions(eventPublisher);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void deleteRecordsTheOperatorTheNameAndTheMandatedPauseReason() {
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(activeWorkspace());
        when(workspaceDao.softDelete(WORKSPACE_ID, OWNER_ID)).thenReturn(1);
        when(deletionLinkage.pauseScheduledTasks(WORKSPACE_ID, OWNER_ID)).thenReturn(2);

        service.deleteWorkspace(WORKSPACE_ID, OWNER_ID);

        AuditLogRecord audit = capturedAudit();
        assertEquals("ORG_DELETED", audit.getAction());
        assertEquals("ORG", audit.getModule());
        assertEquals("ORG", audit.getTargetType());
        assertEquals(WORKSPACE_ID, audit.getTargetId());
        assertEquals(OWNER_ID, audit.getActorId());
        // F2.5: operator, deleted workspace id and name are all required in the audit trail, and
        // scheduled_task has no reason column so the mandated wording lives here.
        assertEquals(NAME, audit.getDetail().get("name"));
        assertEquals(Long.valueOf(OWNER_ID), audit.getDetail().get("operatorId"));
        assertEquals(WorkspaceDeletionLinkage.DELETION_REASON, audit.getDetail().get("reason"));
        assertEquals(2, audit.getDetail().get("pausedScheduledTasks"));
    }

    @Test
    void deleteKeepsTheAuditTrailEvenWhenNoTaskWasPaused() {
        when(workspaceDao.findByIdForUpdate(WORKSPACE_ID)).thenReturn(activeWorkspace());
        when(workspaceDao.softDelete(WORKSPACE_ID, OWNER_ID)).thenReturn(1);
        when(deletionLinkage.pauseScheduledTasks(WORKSPACE_ID, OWNER_ID)).thenReturn(0);

        service.deleteWorkspace(WORKSPACE_ID, OWNER_ID);

        // detail() drops nulls, so a zero count has to be written explicitly rather than left out.
        assertEquals(0, capturedAudit().getDetail().get("pausedScheduledTasks"));
        verify(eventPublisher).publishEvent(new WorkspaceDeletedEvent(WORKSPACE_ID, NAME, OWNER_ID));
    }

    // ------------------------------------------------------------- F3 name key

    @Test
    void createWritesTheActiveNameKeySoTheUniqueIndexCoversTheRow() {
        doAnswer(invocation -> {
            ((WorkspaceDO) invocation.getArgument(0)).setId(WORKSPACE_ID);
            return null;
        }).when(workspaceDao).insert(any(WorkspaceDO.class));
        CreateWorkspaceRequest request = new CreateWorkspaceRequest();
        request.setName("  " + NAME + "  ");

        service.create(request, OWNER_ID);

        verify(workspaceDao).insert(argThat(workspace ->
                NAME.equals(workspace.getName())
                        && NAME.equals(workspace.getActiveNameKey())
                        && Integer.valueOf(0).equals(workspace.getVersion())));
    }

    @Test
    void createSurfacesTheBusinessCodeWhenTheUniqueIndexWinsTheRace() {
        doThrow(new DuplicateKeyException("uk_active_name"))
                .when(workspaceDao).insert(any(WorkspaceDO.class));
        CreateWorkspaceRequest request = new CreateWorkspaceRequest();
        request.setName(NAME);

        // F3.4: two concurrent creates — exactly one passes uk_active_name and the loser gets the
        // same stable business error the pre-check produces, never the driver's exception text.
        assertCode("11003", () -> service.create(request, OWNER_ID));

        verifyNoInteractions(workspaceMemberDao);
        verifyNoInteractions(auditLogService);
    }

    // ------------------------------------------------------------ F4 recycle bin

    @Test
    void recycleBinPushesIdentityFilteringIntoTheQueryForAnOrdinaryOperator() {
        when(workspaceDao.pageRecycleBin(OWNER_ID, false, null, 0, 20))
                .thenReturn(List.of(deletedWorkspace()));
        when(workspaceDao.countRecycleBin(OWNER_ID, false, null)).thenReturn(1L);
        when(userDao.listByIds(anyCollection())).thenReturn(List.of(
                user(OWNER_ID, "owner-login", "空间主人"),
                user(ADMIN_ID, "admin-login", "管理员")));

        PageResult<RecycleBinItemVO> page = service.pageRecycleBin(null, 1, 20, OWNER_ID);

        // F4.2: operator id and the platform-admin flag travel into the SQL. Filtering the rows in
        // memory afterwards would both leak the existence of unseen ids and desynchronize the page
        // from its total.
        verify(workspaceDao).pageRecycleBin(OWNER_ID, false, null, 0, 20);
        verify(workspaceDao).countRecycleBin(OWNER_ID, false, null);
        assertEquals(1L, page.getTotal());
        assertEquals(1, page.getPageNum());
        assertEquals(20, page.getPageSize());

        // F4.4: the page has to carry name, description, original owner, delete time, deleter and
        // the restorable flag — everything the row renders, and nothing else.
        RecycleBinItemVO item = page.getList().get(0);
        assertEquals(WORKSPACE_ID, item.getId());
        assertEquals(NAME, item.getName());
        assertEquals("旧描述", item.getDescription());
        assertEquals(OWNER_ID, item.getOwnerId());
        assertEquals("空间主人", item.getOwnerName());
        assertEquals(DELETED_AT, item.getDeletedAt());
        assertEquals(ADMIN_ID, item.getDeletedBy());
        assertEquals("管理员", item.getDeletedByName());
        assertEquals(Boolean.TRUE, item.getRestorable());
    }

    @Test
    void recycleBinDropsTheOwnershipPredicateForAPlatformAdmin() {
        when(systemAdminService.isSystemAdmin(PLATFORM_ADMIN_ID)).thenReturn(true);

        PageResult<RecycleBinItemVO> page = service.pageRecycleBin(null, 1, 20, PLATFORM_ADMIN_ID);

        verify(workspaceDao).pageRecycleBin(PLATFORM_ADMIN_ID, true, null, 0, 20);
        verify(workspaceDao).countRecycleBin(PLATFORM_ADMIN_ID, true, null);
        assertTrue(page.getList().isEmpty());
        assertEquals(0L, page.getTotal());
    }

    @Test
    void recycleBinDerivesTheOffsetFromThePageAndClampsBothBounds() {
        service.pageRecycleBin(null, 3, 50, OWNER_ID);
        verify(workspaceDao).pageRecycleBin(OWNER_ID, false, null, 100, 50);

        // A page or size below 1 would produce a negative offset, which MySQL rejects outright.
        service.pageRecycleBin(null, 0, 0, OWNER_ID);
        verify(workspaceDao).pageRecycleBin(OWNER_ID, false, null, 0, 1);

        // The cap keeps one request from dumping the whole table.
        service.pageRecycleBin(null, -5, 1000, OWNER_ID);
        verify(workspaceDao).pageRecycleBin(OWNER_ID, false, null, 0, 100);
        verify(workspaceDao, times(3)).countRecycleBin(OWNER_ID, false, null);
    }

    @Test
    void recycleBinTrimsTheKeywordAndTreatsABlankOneAsNoFilter() {
        service.pageRecycleBin("  星云  ", 1, 20, OWNER_ID);
        verify(workspaceDao).pageRecycleBin(OWNER_ID, false, "星云", 0, 20);
        verify(workspaceDao).countRecycleBin(OWNER_ID, false, "星云");

        // D7: search and paging share one filter, and an all-whitespace keyword must mean "no
        // filter" rather than LIKE '%%' built from a string that renders as empty in the input.
        service.pageRecycleBin("   ", 2, 20, OWNER_ID);
        verify(workspaceDao).pageRecycleBin(OWNER_ID, false, null, 20, 20);
        verify(workspaceDao).countRecycleBin(OWNER_ID, false, null);
    }

    @Test
    void recycleBinMarksARowUnrestorableWhenAnActiveWorkspaceHoldsItsName() {
        WorkspaceDO taken = deletedWorkspace();
        taken.setId(20L);
        taken.setName("重名空间");
        taken.setDeletedBy(OWNER_ID);
        when(workspaceDao.pageRecycleBin(OWNER_ID, false, null, 0, 20))
                .thenReturn(List.of(deletedWorkspace(), taken));
        when(workspaceDao.listActiveNames(anyCollection())).thenReturn(List.of("重名空间"));

        List<RecycleBinItemVO> items = service.pageRecycleBin(null, 1, 20, OWNER_ID).getList();

        assertEquals(Boolean.TRUE, items.get(0).getRestorable());
        // F5.6/D4: this flag is what makes the restore dialog demand a new name up front instead
        // of letting the operator submit and read the conflict back.
        assertEquals(Boolean.FALSE, items.get(1).getRestorable());
        verify(workspaceDao).listActiveNames(argThat(names ->
                Set.copyOf(names).equals(Set.of(NAME, "重名空间"))));
    }

    @Test
    void recycleBinSkipsBothBulkLookupsWhenThePageIsEmpty() {
        PageResult<RecycleBinItemVO> page = service.pageRecycleBin(null, 1, 20, OWNER_ID);

        // listActiveNames and listByIds both build an IN (...) list, and an empty one is a SQL
        // syntax error rather than an empty result set.
        assertTrue(page.getList().isEmpty());
        verify(workspaceDao, never()).listActiveNames(anyCollection());
        verify(userDao, never()).listByIds(anyCollection());
    }

    @Test
    void recycleBinResolvesEveryNamedUserInOneQueryAndFallsBackToTheUsername() {
        WorkspaceDO second = deletedWorkspace();
        second.setId(20L);
        second.setDeletedBy(PLATFORM_ADMIN_ID);
        when(workspaceDao.pageRecycleBin(OWNER_ID, false, null, 0, 20))
                .thenReturn(List.of(deletedWorkspace(), second));
        when(userDao.listByIds(anyCollection())).thenReturn(List.of(
                user(OWNER_ID, "owner-login", "  "),
                user(ADMIN_ID, "admin-login", null)));

        List<RecycleBinItemVO> items = service.pageRecycleBin(null, 1, 20, OWNER_ID).getList();

        // Three distinct users across four id slots; the set is what keeps this to one round trip.
        verify(userDao, times(1)).listByIds(argThat(ids ->
                Set.copyOf(ids).equals(Set.of(OWNER_ID, ADMIN_ID, PLATFORM_ADMIN_ID))));
        // A blank nickname falls back to the login name; an unresolved user stays null rather
        // than rendering as the string "null" in the table.
        assertEquals("owner-login", items.get(0).getOwnerName());
        assertEquals("admin-login", items.get(0).getDeletedByName());
        assertNull(items.get(1).getDeletedByName());
    }

    // --------------------------------------------------------------- F5 restore

    @Test
    void restoreReactivatesTheRowAndRebindsItsNameInOneStatement() {
        when(workspaceDao.findByIdAnyState(WORKSPACE_ID)).thenReturn(deletedWorkspace());
        when(workspaceDao.restore(WORKSPACE_ID, NAME, NAME, OWNER_ID)).thenReturn(1);

        WorkspaceVO result = service.restoreWorkspace(WORKSPACE_ID, null, OWNER_ID);

        // F5.4: name and active_name_key come back together with is_deleted = 0, so there is no
        // window where a restored workspace is invisible to the uniqueness index.
        verify(workspaceDao).restore(WORKSPACE_ID, NAME, NAME, OWNER_ID);
        assertEquals(NAME, result.getName());
        assertEquals(4, result.getVersion());
        assertEquals(Boolean.TRUE, result.getIsOwner());
        assertEquals(Boolean.TRUE, result.getCanManage());
    }

    @Test
    void restoreNeverReadsTheDeletedWorkspaceThroughATokenScopedFinder() {
        when(workspaceDao.findByIdAnyState(WORKSPACE_ID)).thenReturn(deletedWorkspace());
        when(workspaceDao.restore(WORKSPACE_ID, NAME, NAME, OWNER_ID)).thenReturn(1);

        service.restoreWorkspace(WORKSPACE_ID, null, OWNER_ID);

        // F5.2: findById and countUsable both filter on is_deleted = 0, so either one would make
        // restore impossible for exactly the rows the recycle bin lists — and the caller's token
        // is bound to some other workspace by now anyway.
        verify(workspaceDao, never()).findById(any());
        verify(workspaceDao, never()).findByIdForUpdate(any());
        verify(workspaceDao, never()).countUsable(any());
        assertNull(AutoWonderContext.get().getCurrentWorkspaceId());
        assertNull(AutoWonderContext.get().getWorkspaceAccessLevel());
    }

    @ParameterizedTest
    @MethodSource("operatorsAllowedToRestore")
    void restoreIsAllowedForTheOriginalOwnerAdminsAndPlatformAdmins(long operatorId,
                                                                   boolean systemAdmin) {
        when(workspaceDao.findByIdAnyState(WORKSPACE_ID)).thenReturn(deletedWorkspace());
        when(workspaceDao.restore(eq(WORKSPACE_ID), anyString(), anyString(), eq(operatorId)))
                .thenReturn(1);
        when(systemAdminService.isSystemAdmin(operatorId)).thenReturn(systemAdmin);
        stubMembership(ADMIN_ID, WorkspaceAccessLevel.ADMIN);

        WorkspaceVO result = service.restoreWorkspace(WORKSPACE_ID, null, operatorId);

        assertNotNull(result);
        verify(workspaceDao).restore(WORKSPACE_ID, NAME, NAME, operatorId);
    }

    @Test
    void restoreRejectsAPlainMemberAndANonMemberWithOneUnenumerableCode() {
        when(workspaceDao.findByIdAnyState(WORKSPACE_ID)).thenReturn(deletedWorkspace());
        stubMembership(MEMBER_ID, WorkspaceAccessLevel.READ_WRITE);

        // F4.3: "not yours" and "does not exist" answer identically, so guessing ids cannot
        // enumerate other tenants' deleted workspaces.
        assertCode("11006", () -> service.restoreWorkspace(WORKSPACE_ID, null, MEMBER_ID));
        assertCode("11006", () -> service.restoreWorkspace(WORKSPACE_ID, null, OUTSIDER_ID));

        verify(workspaceDao, never()).restore(anyLong(), anyString(), anyString(), anyLong());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void restoreRejectsAnAdminMembershipThatLogicalDeleteDidNotPreserve() {
        when(workspaceDao.findByIdAnyState(WORKSPACE_ID)).thenReturn(deletedWorkspace());
        WorkspaceMemberDO removed = member(ADMIN_ID, WorkspaceAccessLevel.ADMIN);
        removed.setIsDeleted(1);
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, ADMIN_ID)).thenReturn(removed);

        // F2.3 keeps org_member rows untouched, so a membership that is already gone predates the
        // delete and grants nothing.
        assertCode("11006", () -> service.restoreWorkspace(WORKSPACE_ID, null, ADMIN_ID));
    }

    @Test
    void restoreRejectsAnUnknownIdWithTheSameCodeAsAMissingPermission() {
        when(workspaceDao.findByIdAnyState(WORKSPACE_ID)).thenReturn(null);

        assertCode("11006", () -> service.restoreWorkspace(WORKSPACE_ID, null, OWNER_ID));
    }

    @Test
    void restoreIsIdempotentForAWorkspaceThatIsAlreadyActive() {
        when(workspaceDao.findByIdAnyState(WORKSPACE_ID)).thenReturn(activeWorkspace());

        WorkspaceVO result = service.restoreWorkspace(WORKSPACE_ID, null, OWNER_ID);

        // F5.8: a second click reports the wanted end state instead of a second state change.
        assertEquals(NAME, result.getName());
        assertEquals(3, result.getVersion());
        verify(workspaceDao, never()).restore(anyLong(), anyString(), anyString(), anyLong());
        verify(workspaceDao, never()).countActiveByName(anyString(), any());
        verifyNoInteractions(auditLogService);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void restoreRejectsANameAlreadyTakenByAnActiveWorkspace() {
        when(workspaceDao.findByIdAnyState(WORKSPACE_ID)).thenReturn(deletedWorkspace());
        when(workspaceDao.countActiveByName(NAME, null)).thenReturn(1L);

        // F5.6: refused by default, with its own code so the dialog can switch to rename mode.
        assertCode("11007", () -> service.restoreWorkspace(WORKSPACE_ID, null, OWNER_ID));

        verify(workspaceDao, never()).restore(anyLong(), anyString(), anyString(), anyLong());
        verifyNoInteractions(auditLogService);
    }

    @Test
    void restoreAcceptsANewNameSoAConflictResolvesInOneRequest() {
        when(workspaceDao.findByIdAnyState(WORKSPACE_ID)).thenReturn(deletedWorkspace());
        when(workspaceDao.restore(WORKSPACE_ID, "星云工坊 2", "星云工坊 2", OWNER_ID)).thenReturn(1);
        RestoreWorkspaceRequest request = new RestoreWorkspaceRequest();
        request.setNewName("  星云工坊 2  ");

        WorkspaceVO result = service.restoreWorkspace(WORKSPACE_ID, request, OWNER_ID);

        // D4: both the conflict check and the write use the requested name, never the stored one.
        verify(workspaceDao).countActiveByName("星云工坊 2", null);
        verify(workspaceDao, never()).countActiveByName(NAME, null);
        verify(workspaceDao).restore(WORKSPACE_ID, "星云工坊 2", "星云工坊 2", OWNER_ID);
        assertEquals("星云工坊 2", result.getName());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void restoreFallsBackToTheStoredNameWhenTheRequestedOneIsBlank(String newName) {
        when(workspaceDao.findByIdAnyState(WORKSPACE_ID)).thenReturn(deletedWorkspace());
        when(workspaceDao.restore(WORKSPACE_ID, NAME, NAME, OWNER_ID)).thenReturn(1);
        RestoreWorkspaceRequest request = new RestoreWorkspaceRequest();
        request.setNewName(newName);

        service.restoreWorkspace(WORKSPACE_ID, request, OWNER_ID);

        // A blank rename must not clear the name; the stored one is what the row keeps.
        verify(workspaceDao).restore(WORKSPACE_ID, NAME, NAME, OWNER_ID);
    }

    @Test
    void restoreRejectsAStoredNameThatIsBlankRatherThanWritingAnEmptyOne() {
        WorkspaceDO row = deletedWorkspace();
        row.setName("   ");
        when(workspaceDao.findByIdAnyState(WORKSPACE_ID)).thenReturn(row);

        assertCode("11002", () -> service.restoreWorkspace(WORKSPACE_ID, null, OWNER_ID));

        verify(workspaceDao, never()).restore(anyLong(), anyString(), anyString(), anyLong());
    }

    @Test
    void restoreEnforcesTheNameLengthLimitOnTheRequestedName() {
        when(workspaceDao.findByIdAnyState(WORKSPACE_ID)).thenReturn(deletedWorkspace());
        RestoreWorkspaceRequest request = new RestoreWorkspaceRequest();
        request.setNewName("名".repeat(129));

        assertCode("10001", () -> service.restoreWorkspace(WORKSPACE_ID, request, OWNER_ID));
    }

    @Test
    void restoreTreatsALostRaceAsSuccessOnceTheRowIsAlreadyActive() {
        when(workspaceDao.findByIdAnyState(WORKSPACE_ID))
                .thenReturn(deletedWorkspace(), activeWorkspace());
        when(workspaceDao.restore(WORKSPACE_ID, NAME, NAME, OWNER_ID)).thenReturn(0);

        WorkspaceVO result = service.restoreWorkspace(WORKSPACE_ID, null, OWNER_ID);

        // The UPDATE is conditional on is_deleted = 1, so exactly one racer changes state; the
        // loser re-reads and reports the end state instead of an error (F5.8).
        assertEquals(NAME, result.getName());
        verify(workspaceDao, times(2)).findByIdAnyState(WORKSPACE_ID);
        verifyNoInteractions(auditLogService);
    }

    @ParameterizedTest
    @MethodSource("racesThatLeaveTheRowUnrestored")
    void restoreReportsTheUnenumerableCodeWhenTheLostRaceLeavesTheRowDeleted(WorkspaceDO reRead) {
        when(workspaceDao.findByIdAnyState(WORKSPACE_ID)).thenReturn(deletedWorkspace(), reRead);
        when(workspaceDao.restore(WORKSPACE_ID, NAME, NAME, OWNER_ID)).thenReturn(0);

        assertCode("11006", () -> service.restoreWorkspace(WORKSPACE_ID, null, OWNER_ID));

        verifyNoInteractions(auditLogService);
    }

    @Test
    void restoreRecordsThatPausedTasksAreDeliberatelyNotResumed() {
        when(workspaceDao.findByIdAnyState(WORKSPACE_ID)).thenReturn(deletedWorkspace());
        when(workspaceDao.restore(WORKSPACE_ID, NAME, NAME, OWNER_ID)).thenReturn(1);

        service.restoreWorkspace(WORKSPACE_ID, null, OWNER_ID);

        AuditLogRecord audit = capturedAudit();
        assertEquals("ORG_RESTORED", audit.getAction());
        assertEquals("ORG", audit.getModule());
        assertEquals(WORKSPACE_ID, audit.getTenantId());
        assertEquals(OWNER_ID, audit.getActorId());
        assertEquals(NAME, audit.getDetail().get("name"));
        assertEquals(NAME, audit.getDetail().get("deletedName"));
        // D6: restore leaves the timers the delete paused for a human to re-enable on purpose.
        assertEquals(Boolean.FALSE, audit.getDetail().get("scheduledTasksResumed"));
        verifyNoInteractions(deletionLinkage);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void restoreRecordsTheOldNameWhenTheRowCameBackUnderANewOne() {
        when(workspaceDao.findByIdAnyState(WORKSPACE_ID)).thenReturn(deletedWorkspace());
        when(workspaceDao.restore(WORKSPACE_ID, "星云工坊 2", "星云工坊 2", OWNER_ID)).thenReturn(1);
        RestoreWorkspaceRequest request = new RestoreWorkspaceRequest();
        request.setNewName("星云工坊 2");

        service.restoreWorkspace(WORKSPACE_ID, request, OWNER_ID);

        AuditLogRecord audit = capturedAudit();
        assertEquals("星云工坊 2", audit.getDetail().get("name"));
        assertEquals(NAME, audit.getDetail().get("deletedName"));
    }

    @Test
    void restoreBumpsANullStoredVersionToOneInsteadOfFailing() {
        WorkspaceDO row = deletedWorkspace();
        row.setVersion(null);
        when(workspaceDao.findByIdAnyState(WORKSPACE_ID)).thenReturn(row);
        when(workspaceDao.restore(WORKSPACE_ID, NAME, NAME, OWNER_ID)).thenReturn(1);

        assertEquals(1, service.restoreWorkspace(WORKSPACE_ID, null, OWNER_ID).getVersion());
    }

    // ------------------------------------------------------- F8 list enrichment

    @Test
    void listByUserCarriesTheCardFlagsAndTheFieldsTheEditModalNeeds() {
        WorkspaceDO owned = activeWorkspace();
        WorkspaceDO administered = activeWorkspace();
        administered.setId(20L);
        administered.setName("管理空间");
        administered.setOwnerId(OUTSIDER_ID);
        administered.setBackground("背景");
        administered.setVersion(9);
        WorkspaceDO readOnly = activeWorkspace();
        readOnly.setId(30L);
        readOnly.setName("只读空间");
        readOnly.setOwnerId(OUTSIDER_ID);

        when(workspaceDao.listByUser(OWNER_ID)).thenReturn(List.of(owned, administered, readOnly));
        when(workspaceDao.listMembershipsByUser(OWNER_ID)).thenReturn(List.of(
                membership(WORKSPACE_ID, WorkspaceAccessLevel.ADMIN),
                membership(20L, WorkspaceAccessLevel.ADMIN),
                membership(30L, WorkspaceAccessLevel.READ_ONLY)));

        List<WorkspaceVO> result = service.listByUser(OWNER_ID);

        // F1.1/F2.1: the card renders its edit and delete entries from canManage, so the flag has
        // to be decided server-side rather than guessed in the browser. D8: ownership is
        // org.owner_id — there is no OWNER access level to compare against.
        assertEquals(Boolean.TRUE, result.get(0).getIsOwner());
        assertEquals(Boolean.TRUE, result.get(0).getCanManage());
        assertEquals(Boolean.FALSE, result.get(1).getIsOwner());
        assertEquals(Boolean.TRUE, result.get(1).getCanManage());
        assertEquals(Boolean.FALSE, result.get(2).getIsOwner());
        assertEquals(Boolean.FALSE, result.get(2).getCanManage());
        // F1.3: the modal pre-fills all three fields and sends version back as the lock.
        assertEquals("背景", result.get(1).getBackground());
        assertEquals(9, result.get(1).getVersion());
        assertEquals(WorkspaceAccessLevel.READ_ONLY, result.get(2).getAccessLevel());
        // One bulk membership read for the whole list, not one permission round trip per card.
        verify(workspaceDao, times(1)).listMembershipsByUser(OWNER_ID);
        verifyNoInteractions(workspaceMemberDao);
    }

    // ------------------------------------------------------------- structure

    @Test
    void theMutatingLifecycleOperationsRunInsideATransaction() throws Exception {
        // Delete has to be atomic across the org row, the timer pause and the audit entry; restore
        // across the name-conflict check and the reactivation.
        assertNotNull(lifecycleMethod("updateWorkspace",
                WorkspaceUpdateRequest.class).getAnnotation(Transactional.class));
        assertNotNull(lifecycleMethod("deleteWorkspace").getAnnotation(Transactional.class));
        assertNotNull(lifecycleMethod("restoreWorkspace",
                RestoreWorkspaceRequest.class).getAnnotation(Transactional.class));
    }

    // ------------------------------------------------------------- fixtures

    private Method lifecycleMethod(String name, Class<?>... extraParameters)
            throws Exception {
        Class<?>[] types = new Class<?>[extraParameters.length + 2];
        types[0] = long.class;
        System.arraycopy(extraParameters, 0, types, 1, extraParameters.length);
        types[types.length - 1] = long.class;
        return WorkspaceService.class.getDeclaredMethod(name, types);
    }

    private AuditLogRecord capturedAudit() {
        ArgumentCaptor<AuditLogRecord> captor = ArgumentCaptor.forClass(AuditLogRecord.class);
        verify(auditLogService).recordRequired(captor.capture());
        return captor.getValue();
    }

    private static WorkspaceUpdateRequest updateRequest(String name, String description,
                                                        String background, Integer version) {
        WorkspaceUpdateRequest request = new WorkspaceUpdateRequest();
        request.setName(name);
        request.setDescription(description);
        request.setBackground(background);
        request.setVersion(version);
        return request;
    }

    private void stubMembership(long userId, WorkspaceAccessLevel level) {
        when(workspaceMemberDao.findByWorkspaceAndUser(WORKSPACE_ID, userId))
                .thenReturn(member(userId, level));
    }

    private static WorkspaceMemberDO member(long userId, WorkspaceAccessLevel level) {
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setTenantId(WORKSPACE_ID);
        member.setUserId(userId);
        member.setStatus(0);
        member.setIsDeleted(0);
        member.setAccessLevel(level.name());
        member.setIdentityTags("[]");
        return member;
    }

    private static WorkspaceMembershipDO membership(long id, WorkspaceAccessLevel level) {
        WorkspaceMembershipDO membership = new WorkspaceMembershipDO();
        membership.setId(id);
        membership.setOwnerId(OUTSIDER_ID);
        membership.setAccessLevel(level.name());
        return membership;
    }

    private static WorkspaceDO activeWorkspace() {
        WorkspaceDO workspace = new WorkspaceDO();
        workspace.setId(WORKSPACE_ID);
        workspace.setName(NAME);
        workspace.setActiveNameKey(NAME);
        workspace.setDescription("旧描述");
        workspace.setBackground("旧背景");
        workspace.setOwnerId(OWNER_ID);
        workspace.setStatus(0);
        workspace.setIsDeleted(0);
        workspace.setVersion(3);
        return workspace;
    }

    /** A deleted row keeps its name for the recycle bin but has released active_name_key. */
    private static WorkspaceDO deletedWorkspace() {
        WorkspaceDO workspace = activeWorkspace();
        workspace.setIsDeleted(1);
        workspace.setActiveNameKey(null);
        workspace.setDeletedAt(DELETED_AT);
        workspace.setDeletedBy(ADMIN_ID);
        return workspace;
    }

    private static UserDO user(long id, String username, String nickname) {
        UserDO user = new UserDO();
        user.setId(id);
        user.setUsername(username);
        user.setNickname(nickname);
        user.setStatus(0);
        return user;
    }

    /** Owner and ADMIN pass; everyone else — including a revoked platform admin — must be refused. */
    private static Stream<Arguments> operatorsWithoutManageRights() {
        return Stream.of(
                Arguments.of(MEMBER_ID, WorkspaceAccessLevel.READ_WRITE),
                Arguments.of(MEMBER_ID, WorkspaceAccessLevel.READ_ONLY),
                Arguments.of(OUTSIDER_ID, null));
    }

    private static Stream<Arguments> operatorsAllowedToRestore() {
        return Stream.of(
                Arguments.of(OWNER_ID, false),
                Arguments.of(ADMIN_ID, false),
                // F4/F5: a platform admin is usually not a member of the workspace at all.
                Arguments.of(PLATFORM_ADMIN_ID, true));
    }

    /** Both shapes a lost restore race can leave behind: still deleted, or gone entirely. */
    private static Stream<Arguments> racesThatLeaveTheRowUnrestored() {
        return Stream.of(
                Arguments.of(deletedWorkspace()),
                Arguments.of((WorkspaceDO) null));
    }

    private static void assertCode(String code, ThrowingRunnable runnable) {
        BizException exception = assertThrows(BizException.class, runnable::run);
        assertEquals(code, exception.getCode());
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
