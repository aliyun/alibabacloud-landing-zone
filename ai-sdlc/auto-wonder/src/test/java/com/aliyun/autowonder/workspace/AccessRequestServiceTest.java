package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.result.PageResult;
import com.aliyun.autowonder.user.UserDO;
import com.aliyun.autowonder.user.UserDao;
import com.aliyun.autowonder.workspace.dto.AccessRequestVO;
import com.aliyun.autowonder.workspace.dto.WorkspaceListItemVO;
import com.aliyun.autowonder.workspace.event.WorkspaceAccessCancelledEvent;
import com.aliyun.autowonder.workspace.event.WorkspaceAccessRequestedEvent;
import com.aliyun.autowonder.workspace.event.WorkspaceAccessReviewedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;

import java.util.Collection;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AccessRequestServiceTest {

    private WorkspaceDao workspaceDao;
    private WorkspaceMemberDao workspaceMemberDao;
    private AccessRequestDao accessRequestDao;
    private UserDao userDao;
    private ApplicationEventPublisher eventPublisher;
    private AccessRequestService service;

    @BeforeEach
    void setUp() {
        workspaceDao = mock(WorkspaceDao.class);
        workspaceMemberDao = mock(WorkspaceMemberDao.class);
        accessRequestDao = mock(AccessRequestDao.class);
        userDao = mock(UserDao.class);
        eventPublisher = mock(ApplicationEventPublisher.class);
        service = new AccessRequestService(workspaceDao, workspaceMemberDao, accessRequestDao,
                userDao, eventPublisher);
    }

    // ---------------- listAll ----------------

    @Test
    void listAllClassifiesMemberPendingAndNotMemberWithLevelOnlyForMember() {
        when(workspaceDao.listAllPaged(null, 0, 10)).thenReturn(List.of(
                workspace(100L, "Alpha", "alpha desc"),
                workspace(200L, "Beta", "beta desc"),
                workspace(300L, "Gamma", "gamma desc")));
        when(workspaceDao.countAll(null)).thenReturn(3L);
        when(workspaceDao.listMembershipsByUser(7L))
                .thenReturn(List.of(membership(100L, WorkspaceAccessLevel.READ_WRITE)));
        when(accessRequestDao.listPendingByRequester(7L))
                .thenReturn(List.of(request(55L, 200L, 7L, WorkspaceAccessLevel.ADMIN, "PENDING")));

        PageResult<WorkspaceListItemVO> result = service.listAll(null, 1, 10, 7L);

        assertThat(result.getTotal()).isEqualTo(3L);
        assertThat(result.getPageNum()).isEqualTo(1);
        assertThat(result.getPageSize()).isEqualTo(10);
        assertThat(result.getList()).hasSize(3);

        WorkspaceListItemVO member = result.getList().get(0);
        assertThat(member.getId()).isEqualTo(100L);
        assertThat(member.getName()).isEqualTo("Alpha");
        assertThat(member.getDescription()).isEqualTo("alpha desc");
        assertThat(member.getMembershipStatus()).isEqualTo("MEMBER");
        assertThat(member.getAccessLevel()).isEqualTo("READ_WRITE");
        assertThat(member.getPendingRequestId()).isNull();

        WorkspaceListItemVO pending = result.getList().get(1);
        assertThat(pending.getId()).isEqualTo(200L);
        assertThat(pending.getMembershipStatus()).isEqualTo("PENDING");
        assertThat(pending.getAccessLevel()).isNull();
        assertThat(pending.getPendingRequestId()).isEqualTo(55L);

        WorkspaceListItemVO notMember = result.getList().get(2);
        assertThat(notMember.getId()).isEqualTo(300L);
        assertThat(notMember.getMembershipStatus()).isEqualTo("NOT_MEMBER");
        assertThat(notMember.getAccessLevel()).isNull();
        assertThat(notMember.getPendingRequestId()).isNull();
    }

    @Test
    void listAllUsesTwoLookupQueriesInsteadOfPerRowQueries() {
        when(workspaceDao.listAllPaged(null, 0, 10)).thenReturn(List.of(
                workspace(100L, "Alpha", null),
                workspace(200L, "Beta", null),
                workspace(300L, "Gamma", null)));
        when(workspaceDao.countAll(null)).thenReturn(3L);

        service.listAll(null, 1, 10, 7L);

        verify(workspaceDao).listMembershipsByUser(7L);
        verify(accessRequestDao).listPendingByRequester(7L);
        verify(workspaceMemberDao, never()).findByWorkspaceAndUser(anyLong(), anyLong());
        verify(accessRequestDao, never()).findPendingByTenantAndRequester(anyLong(), anyLong());
    }

    @Test
    void listAllComputesOffsetForLaterPagesAndPropagatesPaging() {
        when(workspaceDao.listAllPaged("ac", 20, 10)).thenReturn(List.of(workspace(400L, "Delta", null)));
        when(workspaceDao.countAll("ac")).thenReturn(41L);

        PageResult<WorkspaceListItemVO> result = service.listAll("ac", 3, 10, 7L);

        verify(workspaceDao).listAllPaged("ac", 20, 10);
        assertThat(result.getTotal()).isEqualTo(41L);
        assertThat(result.getPageNum()).isEqualTo(3);
        assertThat(result.getPageSize()).isEqualTo(10);
        assertThat(result.getList()).hasSize(1);
        assertThat(result.getList().get(0).getMembershipStatus()).isEqualTo("NOT_MEMBER");
    }

    // ---------------- submitRequest ----------------

    @Test
    void submitRequestPersistsPendingRowAndPublishesEvent() {
        when(workspaceDao.findById(100L)).thenReturn(workspace(100L, "Alpha", "alpha desc"));
        when(userDao.findById(7L)).thenReturn(user(7L, "Alice"));
        doAnswer(invocation -> {
            ((AccessRequestDO) invocation.getArgument(0)).setId(4242L);
            return null;
        }).when(accessRequestDao).insert(any(AccessRequestDO.class));

        service.submitRequest(100L, "READ_WRITE", 7L);

        ArgumentCaptor<AccessRequestDO> inserted = ArgumentCaptor.forClass(AccessRequestDO.class);
        verify(accessRequestDao).insert(inserted.capture());
        assertThat(inserted.getValue().getTenantId()).isEqualTo(100L);
        assertThat(inserted.getValue().getRequesterId()).isEqualTo(7L);
        assertThat(inserted.getValue().getRequestedLevel()).isEqualTo("READ_WRITE");
        assertThat(inserted.getValue().getStatus()).isEqualTo("PENDING");

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOf(WorkspaceAccessRequestedEvent.class);
        WorkspaceAccessRequestedEvent published = (WorkspaceAccessRequestedEvent) event.getValue();
        assertThat(published.tenantId()).isEqualTo(100L);
        assertThat(published.requestId()).isEqualTo(4242L);
        assertThat(published.requesterId()).isEqualTo(7L);
        assertThat(published.requesterDisplayName()).isEqualTo("Alice");
        assertThat(published.requestedLevel()).isEqualTo("READ_WRITE");
        assertThat(published.workspaceName()).isEqualTo("Alpha");
    }

    @Test
    void submitRequestFallsBackToNumericIdWhenNicknameMissing() {
        when(workspaceDao.findById(100L)).thenReturn(workspace(100L, "Alpha", null));
        doAnswer(invocation -> {
            ((AccessRequestDO) invocation.getArgument(0)).setId(11L);
            return null;
        }).when(accessRequestDao).insert(any(AccessRequestDO.class));

        service.submitRequest(100L, "ADMIN", 7L);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(((WorkspaceAccessRequestedEvent) event.getValue()).requesterDisplayName())
                .isEqualTo("7");
    }

    @Test
    void submitRequestRejectsUnknownWorkspaceWithNotFound() {
        assertCode("10404", () -> service.submitRequest(100L, "READ_ONLY", 7L));
        verify(accessRequestDao, never()).insert(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void submitRequestRejectsActiveMember() {
        when(workspaceDao.findById(100L)).thenReturn(workspace(100L, "Alpha", null));
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 7L))
                .thenReturn(member(100L, 7L, 0, WorkspaceAccessLevel.READ_ONLY));

        assertCode("12013", () -> service.submitRequest(100L, "READ_WRITE", 7L));
        verify(accessRequestDao, never()).insert(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void submitRequestAllowsRequestWhenExistingMemberRowIsInactive() {
        when(workspaceDao.findById(100L)).thenReturn(workspace(100L, "Alpha", null));
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 7L))
                .thenReturn(member(100L, 7L, 1, WorkspaceAccessLevel.READ_ONLY));
        when(userDao.findById(7L)).thenReturn(user(7L, "Alice"));
        doAnswer(invocation -> {
            ((AccessRequestDO) invocation.getArgument(0)).setId(12L);
            return null;
        }).when(accessRequestDao).insert(any(AccessRequestDO.class));

        service.submitRequest(100L, "READ_WRITE", 7L);

        verify(accessRequestDao).insert(any(AccessRequestDO.class));
        verify(eventPublisher).publishEvent(any(WorkspaceAccessRequestedEvent.class));
    }

    @Test
    void submitRequestRejectsDuplicateDetectedByPreCheck() {
        when(workspaceDao.findById(100L)).thenReturn(workspace(100L, "Alpha", null));
        when(accessRequestDao.findPendingByTenantAndRequester(100L, 7L))
                .thenReturn(request(9L, 100L, 7L, WorkspaceAccessLevel.READ_ONLY, "PENDING"));

        assertCode("12012", () -> service.submitRequest(100L, "READ_WRITE", 7L));
        verify(accessRequestDao, never()).insert(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void submitRequestTranslatesDuplicateKeyViolationFromInsert() {
        when(workspaceDao.findById(100L)).thenReturn(workspace(100L, "Alpha", null));
        doThrow(new DuplicateKeyException("Duplicate entry for key 'uk_tenant_requester_pending'"))
                .when(accessRequestDao).insert(any(AccessRequestDO.class));

        assertCode("12012", () -> service.submitRequest(100L, "READ_WRITE", 7L));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void submitRequestRejectsNullBlankAndUnknownLevels() {
        assertCode("12015", () -> service.submitRequest(100L, null, 7L));
        assertCode("12015", () -> service.submitRequest(100L, "   ", 7L));
        assertCode("12015", () -> service.submitRequest(100L, "SUPERUSER", 7L));
        verifyNoInteractions(workspaceDao);
        verifyNoInteractions(accessRequestDao);
        verifyNoInteractions(eventPublisher);
    }

    // ---------------- listForWorkspace ----------------

    @Test
    void listForWorkspacePassesEachValidStatusThroughVerbatim() {
        for (String status : List.of("PENDING", "APPROVED", "REJECTED")) {
            service.listForWorkspace(100L, status);
            verify(accessRequestDao).listByTenantAndStatus(100L, status);
        }
    }

    @Test
    void listForWorkspaceRejectsInvalidAndBlankStatus() {
        assertCode("10001", () -> service.listForWorkspace(100L, null));
        assertCode("10001", () -> service.listForWorkspace(100L, "  "));
        assertCode("10001", () -> service.listForWorkspace(100L, "pending"));
        assertCode("10001", () -> service.listForWorkspace(100L, "CANCELLED"));
        verifyNoInteractions(accessRequestDao);
    }

    @Test
    void listForWorkspaceResolvesDisplayNamesWithOneBatchUserQuery() {
        Date created = new Date(1234L);
        AccessRequestDO approved = request(1L, 100L, 7L, WorkspaceAccessLevel.READ_WRITE, "APPROVED");
        approved.setReviewerId(9L);
        approved.setGmtCreate(created);
        AccessRequestDO rejected = request(2L, 100L, 8L, WorkspaceAccessLevel.ADMIN, "APPROVED");
        rejected.setReviewerId(9L);
        rejected.setRejectReason("not now");
        when(accessRequestDao.listByTenantAndStatus(100L, "APPROVED"))
                .thenReturn(List.of(approved, rejected));
        when(userDao.listByIds(anyCollection()))
                .thenReturn(List.of(user(7L, "Alice"), user(8L, "Bob"), user(9L, "Carol")));

        List<AccessRequestVO> result = service.listForWorkspace(100L, "APPROVED");

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(0).getTenantId()).isEqualTo(100L);
        assertThat(result.get(0).getRequesterId()).isEqualTo(7L);
        assertThat(result.get(0).getRequesterName()).isEqualTo("Alice");
        assertThat(result.get(0).getRequestedLevel()).isEqualTo("READ_WRITE");
        assertThat(result.get(0).getStatus()).isEqualTo("APPROVED");
        assertThat(result.get(0).getReviewerId()).isEqualTo(9L);
        assertThat(result.get(0).getReviewerName()).isEqualTo("Carol");
        assertThat(result.get(0).getGmtCreate()).isEqualTo(created);
        assertThat(result.get(1).getRequesterName()).isEqualTo("Bob");
        assertThat(result.get(1).getRejectReason()).isEqualTo("not now");

        ArgumentCaptor<Collection<Long>> ids = ArgumentCaptor.forClass(Collection.class);
        verify(userDao).listByIds(ids.capture());
        assertThat(ids.getValue()).containsExactlyInAnyOrder(7L, 8L, 9L);
        verify(userDao, never()).findById(anyLong());
    }

    @Test
    void listForWorkspaceShortCircuitsUserLookupForEmptyList() {
        when(accessRequestDao.listByTenantAndStatus(100L, "PENDING")).thenReturn(List.of());

        assertThat(service.listForWorkspace(100L, "PENDING")).isEmpty();
        verifyNoInteractions(userDao);
    }

    // ---------------- approve ----------------

    @Test
    void approveActivatesMemberAtRequestedLevelAndPublishesEvent() {
        when(accessRequestDao.findById(5L))
                .thenReturn(request(5L, 100L, 7L, WorkspaceAccessLevel.READ_WRITE, "PENDING"));
        when(accessRequestDao.updateStatus(5L, "APPROVED", 9L, null)).thenReturn(1);
        when(workspaceDao.findById(100L)).thenReturn(workspace(100L, "Alpha", null));
        when(userDao.findById(9L)).thenReturn(user(9L, "Carol"));

        service.approve(100L, 5L, 9L);

        ArgumentCaptor<WorkspaceMemberDO> added = ArgumentCaptor.forClass(WorkspaceMemberDO.class);
        verify(workspaceMemberDao).insertOrActivate(added.capture());
        assertThat(added.getValue().getTenantId()).isEqualTo(100L);
        assertThat(added.getValue().getUserId()).isEqualTo(7L);
        assertThat(added.getValue().getAccessLevel()).isEqualTo("READ_WRITE");
        assertThat(added.getValue().getStatus()).isEqualTo(0);
        assertThat(added.getValue().getIdentityTags()).isEqualTo("[]");
        // Only modifier_id is written on the ON DUPLICATE KEY UPDATE branch, so creator_id is not pinned.
        assertThat(added.getValue().getModifierId()).isEqualTo(9L);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOf(WorkspaceAccessReviewedEvent.class);
        WorkspaceAccessReviewedEvent published = (WorkspaceAccessReviewedEvent) event.getValue();
        assertThat(published.tenantId()).isEqualTo(100L);
        assertThat(published.requestId()).isEqualTo(5L);
        assertThat(published.requesterId()).isEqualTo(7L);
        assertThat(published.reviewerId()).isEqualTo(9L);
        assertThat(published.reviewerDisplayName()).isEqualTo("Carol");
        assertThat(published.workspaceName()).isEqualTo("Alpha");
        assertThat(published.requestedLevel()).isEqualTo("READ_WRITE");
        assertThat(published.outcome()).isEqualTo("APPROVED");
        assertThat(published.rejectReason()).isNull();
    }

    @Test
    void approveLeavesExistingActiveMembershipUntouchedButStillResolvesTheQueueEntry() {
        WorkspaceMemberDO existing = member(100L, 7L, 0, WorkspaceAccessLevel.ADMIN);
        existing.setIdentityTags("[\"TECH_LEAD\"]");
        when(accessRequestDao.findById(5L))
                .thenReturn(request(5L, 100L, 7L, WorkspaceAccessLevel.READ_ONLY, "PENDING"));
        when(accessRequestDao.updateStatus(5L, "APPROVED", 9L, null)).thenReturn(1);
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 7L)).thenReturn(existing);
        when(workspaceDao.findById(100L)).thenReturn(workspace(100L, "Alpha", null));
        when(userDao.findById(9L)).thenReturn(user(9L, "Carol"));

        service.approve(100L, 5L, 9L);

        // The stale request must not downgrade ADMIN to READ_ONLY nor erase identity_tags.
        verify(workspaceMemberDao, never()).insertOrActivate(any());
        assertThat(existing.getAccessLevel()).isEqualTo("ADMIN");
        assertThat(existing.getIdentityTags()).isEqualTo("[\"TECH_LEAD\"]");

        verify(accessRequestDao).updateStatus(5L, "APPROVED", 9L, null);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOf(WorkspaceAccessReviewedEvent.class);
        WorkspaceAccessReviewedEvent published = (WorkspaceAccessReviewedEvent) event.getValue();
        assertThat(published.requestId()).isEqualTo(5L);
        assertThat(published.requesterId()).isEqualTo(7L);
        assertThat(published.reviewerId()).isEqualTo(9L);
        assertThat(published.outcome()).isEqualTo("APPROVED");
        assertThat(published.requestedLevel()).isEqualTo("READ_ONLY");
        assertThat(published.rejectReason()).isNull();
    }

    @Test
    void approveReactivatesSoftInactiveMemberRowAtRequestedLevel() {
        when(accessRequestDao.findById(5L))
                .thenReturn(request(5L, 100L, 7L, WorkspaceAccessLevel.READ_WRITE, "PENDING"));
        when(accessRequestDao.updateStatus(5L, "APPROVED", 9L, null)).thenReturn(1);
        when(workspaceMemberDao.findByWorkspaceAndUser(100L, 7L))
                .thenReturn(member(100L, 7L, 1, WorkspaceAccessLevel.READ_ONLY));
        when(workspaceDao.findById(100L)).thenReturn(workspace(100L, "Alpha", null));
        when(userDao.findById(9L)).thenReturn(user(9L, "Carol"));

        service.approve(100L, 5L, 9L);

        ArgumentCaptor<WorkspaceMemberDO> added = ArgumentCaptor.forClass(WorkspaceMemberDO.class);
        verify(workspaceMemberDao).insertOrActivate(added.capture());
        assertThat(added.getValue().getTenantId()).isEqualTo(100L);
        assertThat(added.getValue().getUserId()).isEqualTo(7L);
        assertThat(added.getValue().getStatus()).isEqualTo(0);
        assertThat(added.getValue().getAccessLevel()).isEqualTo("READ_WRITE");
        verify(eventPublisher).publishEvent(any(WorkspaceAccessReviewedEvent.class));
    }

    @Test
    void approveRejectsMissingWrongWorkspaceAndNonPendingRequests() {
        assertCode("12014", () -> service.approve(100L, 5L, 9L));

        when(accessRequestDao.findById(5L))
                .thenReturn(request(5L, 999L, 7L, WorkspaceAccessLevel.READ_WRITE, "PENDING"));
        assertCode("12014", () -> service.approve(100L, 5L, 9L));

        when(accessRequestDao.findById(5L))
                .thenReturn(request(5L, 100L, 7L, WorkspaceAccessLevel.READ_WRITE, "APPROVED"));
        assertCode("12014", () -> service.approve(100L, 5L, 9L));

        verify(accessRequestDao, never()).updateStatus(anyLong(), any(), anyLong(), any());
        verify(workspaceMemberDao, never()).insertOrActivate(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void approveLosingTheRaceAddsNoMemberAndPublishesNoEvent() {
        when(accessRequestDao.findById(5L))
                .thenReturn(request(5L, 100L, 7L, WorkspaceAccessLevel.READ_WRITE, "PENDING"));
        when(accessRequestDao.updateStatus(5L, "APPROVED", 9L, null)).thenReturn(0);

        assertCode("12014", () -> service.approve(100L, 5L, 9L));
        verify(workspaceMemberDao, never()).insertOrActivate(any());
        verifyNoInteractions(eventPublisher);
    }

    // ---------------- reject ----------------

    @Test
    void rejectStoresReasonAndPublishesRejectedEvent() {
        when(accessRequestDao.findById(5L))
                .thenReturn(request(5L, 100L, 7L, WorkspaceAccessLevel.ADMIN, "PENDING"));
        when(accessRequestDao.updateStatus(5L, "REJECTED", 9L, "not now")).thenReturn(1);
        when(workspaceDao.findById(100L)).thenReturn(workspace(100L, "Alpha", null));
        when(userDao.findById(9L)).thenReturn(user(9L, "Carol"));

        service.reject(100L, 5L, 9L, "not now");

        verify(accessRequestDao).updateStatus(5L, "REJECTED", 9L, "not now");
        verify(workspaceMemberDao, never()).insertOrActivate(any());

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        WorkspaceAccessReviewedEvent published = (WorkspaceAccessReviewedEvent) event.getValue();
        assertThat(published.requestId()).isEqualTo(5L);
        assertThat(published.requesterId()).isEqualTo(7L);
        assertThat(published.reviewerId()).isEqualTo(9L);
        assertThat(published.reviewerDisplayName()).isEqualTo("Carol");
        assertThat(published.requestedLevel()).isEqualTo("ADMIN");
        assertThat(published.outcome()).isEqualTo("REJECTED");
        assertThat(published.rejectReason()).isEqualTo("not now");
    }

    @Test
    void rejectLosingTheRacePublishesNoEvent() {
        when(accessRequestDao.findById(5L))
                .thenReturn(request(5L, 100L, 7L, WorkspaceAccessLevel.ADMIN, "PENDING"));
        when(accessRequestDao.updateStatus(eq(5L), eq("REJECTED"), eq(9L), any())).thenReturn(0);

        assertCode("12014", () -> service.reject(100L, 5L, 9L, "not now"));
        verifyNoInteractions(eventPublisher);
    }

    // ---------------- cancelRequest ----------------

    @Test
    void cancelDeletesPendingRowAndPublishesEvent() {
        when(accessRequestDao.findById(5L))
                .thenReturn(request(5L, 100L, 7L, WorkspaceAccessLevel.READ_WRITE, "PENDING"));
        when(accessRequestDao.deletePendingById(5L)).thenReturn(1);
        when(workspaceDao.findById(100L)).thenReturn(workspace(100L, "Alpha", null));
        when(userDao.findById(7L)).thenReturn(user(7L, "Alice"));

        service.cancelRequest(100L, 5L, 7L);

        verify(accessRequestDao).deletePendingById(5L);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue()).isInstanceOf(WorkspaceAccessCancelledEvent.class);
        WorkspaceAccessCancelledEvent published = (WorkspaceAccessCancelledEvent) event.getValue();
        assertThat(published.tenantId()).isEqualTo(100L);
        assertThat(published.requestId()).isEqualTo(5L);
        assertThat(published.requesterId()).isEqualTo(7L);
        assertThat(published.requesterDisplayName()).isEqualTo("Alice");
        assertThat(published.requestedLevel()).isEqualTo("READ_WRITE");
        assertThat(published.workspaceName()).isEqualTo("Alpha");
    }

    @Test
    void cancelFallsBackToNumericIdWhenNicknameMissing() {
        when(accessRequestDao.findById(5L))
                .thenReturn(request(5L, 100L, 7L, WorkspaceAccessLevel.ADMIN, "PENDING"));
        when(accessRequestDao.deletePendingById(5L)).thenReturn(1);
        when(workspaceDao.findById(100L)).thenReturn(workspace(100L, "Alpha", null));

        service.cancelRequest(100L, 5L, 7L);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(((WorkspaceAccessCancelledEvent) event.getValue()).requesterDisplayName())
                .isEqualTo("7");
    }

    @Test
    void cancelRejectsMissingAndWrongWorkspaceRequestsWithNotFound() {
        assertCode("12014", () -> service.cancelRequest(100L, 5L, 7L));

        when(accessRequestDao.findById(5L))
                .thenReturn(request(5L, 999L, 7L, WorkspaceAccessLevel.READ_WRITE, "PENDING"));
        assertCode("12014", () -> service.cancelRequest(100L, 5L, 7L));

        verify(accessRequestDao, never()).deletePendingById(anyLong());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void cancelRejectsReviewedRequestsWithNotPending() {
        when(accessRequestDao.findById(5L))
                .thenReturn(request(5L, 100L, 7L, WorkspaceAccessLevel.READ_WRITE, "APPROVED"));
        assertCode("12017", () -> service.cancelRequest(100L, 5L, 7L));

        when(accessRequestDao.findById(5L))
                .thenReturn(request(5L, 100L, 7L, WorkspaceAccessLevel.READ_WRITE, "REJECTED"));
        assertCode("12017", () -> service.cancelRequest(100L, 5L, 7L));

        verify(accessRequestDao, never()).deletePendingById(anyLong());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void cancelRejectsAnyoneButTheRequester() {
        when(accessRequestDao.findById(5L))
                .thenReturn(request(5L, 100L, 7L, WorkspaceAccessLevel.READ_WRITE, "PENDING"));

        assertCode("12016", () -> service.cancelRequest(100L, 5L, 8L));

        verify(accessRequestDao, never()).deletePendingById(anyLong());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void cancelLosingTheRacePublishesNoEvent() {
        when(accessRequestDao.findById(5L))
                .thenReturn(request(5L, 100L, 7L, WorkspaceAccessLevel.READ_WRITE, "PENDING"));
        when(accessRequestDao.deletePendingById(5L)).thenReturn(0);

        assertCode("12014", () -> service.cancelRequest(100L, 5L, 7L));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void cancelToleratesADeletedWorkspaceWhenPublishingTheEvent() {
        when(accessRequestDao.findById(5L))
                .thenReturn(request(5L, 100L, 7L, WorkspaceAccessLevel.READ_WRITE, "PENDING"));
        when(accessRequestDao.deletePendingById(5L)).thenReturn(1);
        when(userDao.findById(7L)).thenReturn(user(7L, "Alice"));

        service.cancelRequest(100L, 5L, 7L);

        ArgumentCaptor<Object> event = ArgumentCaptor.forClass(Object.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(((WorkspaceAccessCancelledEvent) event.getValue()).workspaceName()).isNull();
    }

    // ---------------- helpers ----------------

    private static WorkspaceDO workspace(Long id, String name, String description) {
        WorkspaceDO workspace = new WorkspaceDO();
        workspace.setId(id);
        workspace.setName(name);
        workspace.setDescription(description);
        workspace.setStatus(0);
        return workspace;
    }

    private static WorkspaceMembershipDO membership(Long workspaceId, WorkspaceAccessLevel level) {
        WorkspaceMembershipDO membership = new WorkspaceMembershipDO();
        membership.setId(workspaceId);
        membership.setAccessLevel(level.name());
        return membership;
    }

    private static WorkspaceMemberDO member(Long tenantId, Long userId, Integer status,
                                            WorkspaceAccessLevel level) {
        WorkspaceMemberDO member = new WorkspaceMemberDO();
        member.setTenantId(tenantId);
        member.setUserId(userId);
        member.setStatus(status);
        member.setAccessLevel(level.name());
        member.setIsDeleted(0);
        return member;
    }

    private static AccessRequestDO request(Long id, Long tenantId, Long requesterId,
                                           WorkspaceAccessLevel level, String status) {
        AccessRequestDO request = new AccessRequestDO();
        request.setId(id);
        request.setTenantId(tenantId);
        request.setRequesterId(requesterId);
        request.setRequestedLevel(level.name());
        request.setStatus(status);
        return request;
    }

    private static UserDO user(Long id, String nickname) {
        UserDO user = new UserDO();
        user.setId(id);
        user.setNickname(nickname);
        return user;
    }

    private static void assertCode(String expectedCode, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BizException.class)
                .extracting(exception -> ((BizException) exception).getCode())
                .isEqualTo(expectedCode);
    }
}
