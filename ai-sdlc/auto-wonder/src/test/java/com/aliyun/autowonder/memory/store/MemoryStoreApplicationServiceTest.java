package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.mcp.DispatchMcpTokenService;
import com.aliyun.autowonder.memory.store.dto.MemoryMutationRequest;
import com.aliyun.autowonder.squad.SquadMemberDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MemoryStoreApplicationServiceTest {
    private MemoryStoreDao stores;
    private MemoryDocumentDao documents;
    private MemoryChangeDao changes;
    private MemoryStoreAclDao acls;
    private MemoryDocumentService documentService;
    private MemoryStoreBootstrapService bootstrap;
    private MemoryStoreApplicationService service;

    @BeforeEach
    void setUp() {
        stores = mock(MemoryStoreDao.class);
        documents = mock(MemoryDocumentDao.class);
        changes = mock(MemoryChangeDao.class);
        acls = mock(MemoryStoreAclDao.class);
        documentService = mock(MemoryDocumentService.class);
        bootstrap = mock(MemoryStoreBootstrapService.class);
        service = new MemoryStoreApplicationService(stores, documents, changes, acls,
                mock(SquadMemberDao.class), new MemoryStoreAccessService(), documentService,
                new MemorySnapshotService(), bootstrap);
    }

    @Test
    void firstSnapshotEnsuresWritablePersonalStoreAndVersionedIndex() {
        MemoryStoreDO own = store(11L, 7L, "AGENT", 42L);
        when(bootstrap.ensureAgentStoreWithIndex(7L, 42L, 9L)).thenReturn(own);
        when(stores.listActive(7L)).thenReturn(List.of(own));
        when(acls.listByStore(anyLong(), anyLong())).thenReturn(List.of());
        MemoryDocumentDO index = new MemoryDocumentDO();
        index.setPath("MEMORY.md");
        index.setContentMd("");
        index.setVersion(1);
        when(documents.listLive(7L, 11L)).thenReturn(List.of(index));
        DispatchMcpTokenService.DispatchPrincipal principal = new DispatchMcpTokenService.DispatchPrincipal(
                7L, 9L, 99L, 42L, WorkspaceAccessLevel.READ_WRITE);

        var snapshot = service.snapshotForAgent(principal);

        verify(bootstrap).ensureAgentStoreWithIndex(7L, 42L, 9L);
        assertEquals(1, snapshot.stores().size());
        assertEquals("MEMORY.md", snapshot.stores().get(0).documents().get(0).path());
        assertEquals(1, snapshot.stores().get(0).documents().get(0).version());
    }

    @Test
    void agentSeesOwnPrivateStoreButNotAnotherAgentsPrivateStore() {
        MemoryStoreDO own = store(11L, 7L, "AGENT", 42L);
        MemoryStoreDO other = store(12L, 7L, "AGENT", 43L);
        when(stores.listActive(7L)).thenReturn(List.of(other, own));
        when(acls.listByStore(anyLong(), anyLong())).thenReturn(List.of());
        when(documents.listLive(7L, 11L)).thenReturn(List.of());
        DispatchMcpTokenService.DispatchPrincipal principal = new DispatchMcpTokenService.DispatchPrincipal(
                7L, 9L, 99L, 42L, WorkspaceAccessLevel.ADMIN);

        var snapshot = service.snapshotForAgent(principal);

        assertEquals(1, snapshot.stores().size());
        assertEquals(11L, snapshot.stores().get(0).id());
        verify(documents, never()).listLive(7L, 12L);
    }

    @Test
    void aclCannotProjectAnotherAgentsPrivateStoreIntoWritablePersonalDirectory() {
        MemoryStoreDO own = store(11L, 7L, "AGENT", 42L);
        MemoryStoreDO other = store(12L, 7L, "AGENT", 43L);
        MemoryStoreAclDO acl = new MemoryStoreAclDO();
        acl.setTenantId(7L);
        acl.setStoreId(12L);
        acl.setSubjectType("AGENT");
        acl.setSubjectRef("42");
        acl.setPermission("WRITE");
        when(stores.listActive(7L)).thenReturn(List.of(other, own));
        when(acls.listByStore(7L, 12L)).thenReturn(List.of(acl));
        when(acls.listByStore(7L, 11L)).thenReturn(List.of());
        when(documents.listLive(7L, 11L)).thenReturn(List.of());
        DispatchMcpTokenService.DispatchPrincipal principal = new DispatchMcpTokenService.DispatchPrincipal(
                7L, 9L, 99L, 42L, WorkspaceAccessLevel.READ_WRITE);

        var snapshot = service.snapshotForAgent(principal);

        assertEquals(List.of(11L), snapshot.stores().stream().map(s -> s.id()).toList());
        verify(documents, never()).listLive(7L, 12L);
    }

    @Test
    void dispatchDoesNotInheritIssuingWorkspaceAdministratorsGlobalAccess() {
        MemoryStoreDO org = store(13L, 7L, "ORG", 0L);
        when(stores.listActive(7L)).thenReturn(List.of(org));
        when(acls.listByStore(7L, 13L)).thenReturn(List.of());
        DispatchMcpTokenService.DispatchPrincipal principal = new DispatchMcpTokenService.DispatchPrincipal(
                7L, 9L, 99L, 42L, WorkspaceAccessLevel.ADMIN);

        assertTrue(service.snapshotForAgent(principal).stores().isEmpty());
    }

    @Test
    void aclDeletionIsBoundToTheAuthorizedStore() {
        when(stores.findById(7L, 13L)).thenReturn(store(13L, 7L, "ORG", 0L));

        service.deleteAcl(7L, 9L, true, 13L, 99L);

        verify(acls).delete(7L, 13L, 99L);
    }

    @Test
    void maintenanceMutationRequiresCurrentFencingLease() {
        MemoryStoreDO own = store(11L, 7L, "AGENT", 42L);
        when(stores.findById(7L, 11L)).thenReturn(own);
        when(acls.listByStore(7L, 11L)).thenReturn(List.of());
        DispatchMcpTokenService.DispatchPrincipal principal = new DispatchMcpTokenService.DispatchPrincipal(
                7L, 9L, 99L, 42L, WorkspaceAccessLevel.READ_WRITE);
        String leaseId = "12345678-1234-1234-1234-123456789abc";
        MemoryMutationRequest request = new MemoryMutationRequest(MemoryMutationRequest.Operation.UPDATE,
                "topic.md", "topic.md", "body", 1, "key", leaseId);
        when(stores.lockActiveMaintenanceOwner(eq(7L), eq(11L), any())).thenReturn(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        assertThrows(MemoryStoreApplicationService.MemoryMaintenanceLeaseConflictException.class,
                () -> service.mutateForAgent(principal, 11L, request));
        verify(stores).lockActiveMaintenanceOwner(eq(7L), eq(11L), any());
    }

    @Test
    void explicitMcpMutationCannotBypassActiveMaintenanceLease() {
        MemoryStoreDO own = store(11L, 7L, "AGENT", 42L);
        when(stores.findById(7L, 11L)).thenReturn(own);
        when(stores.lockActiveMaintenanceOwner(eq(7L), eq(11L), any())).thenReturn(
                "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        assertThrows(MemoryStoreApplicationService.MemoryMaintenanceLeaseConflictException.class,
                () -> service.mutateForExplicitAgent(7L, 99L, 42L, 9L, 11L,
                        MemoryMutationRequest.create("topic.md", "body", "key")));
        verifyNoInteractions(documentService);
    }

    @Test
    void sharedStoreSnapshotReportsEffectiveReadOnlyPermission() {
        MemoryStoreDO squad = store(13L, 7L, "SQUAD", 8L);
        MemoryStoreAclDO acl = new MemoryStoreAclDO();
        acl.setTenantId(7L);
        acl.setStoreId(13L);
        acl.setSubjectType("AGENT");
        acl.setSubjectRef("42");
        acl.setPermission("WRITE");
        when(stores.listActive(7L)).thenReturn(List.of(squad));
        when(acls.listByStore(7L, 13L)).thenReturn(List.of(acl));
        when(documents.listLive(7L, 13L)).thenReturn(List.of());
        DispatchMcpTokenService.DispatchPrincipal principal = new DispatchMcpTokenService.DispatchPrincipal(
                7L, 9L, 99L, 42L, WorkspaceAccessLevel.READ_WRITE);

        assertEquals("READ", service.snapshotForAgent(principal).stores().get(0).permission());
    }

    @Test
    void dispatchCannotMutateOrMaintainSharedStoreEvenWithWriteAcl() {
        MemoryStoreDO squad = store(13L, 7L, "SQUAD", 8L);
        MemoryStoreAclDO acl = new MemoryStoreAclDO();
        acl.setTenantId(7L);
        acl.setStoreId(13L);
        acl.setSubjectType("AGENT");
        acl.setSubjectRef("42");
        acl.setPermission("WRITE");
        when(stores.findById(7L, 13L)).thenReturn(squad);
        when(acls.listByStore(7L, 13L)).thenReturn(List.of(acl));
        DispatchMcpTokenService.DispatchPrincipal principal = new DispatchMcpTokenService.DispatchPrincipal(
                7L, 9L, 99L, 42L, WorkspaceAccessLevel.READ_WRITE);
        MemoryMutationRequest request = MemoryMutationRequest.create("topic.md", "body", "key");

        assertThrows(MemoryStoreApplicationService.MemoryAccessDeniedException.class,
                () -> service.mutateForAgent(principal, 13L, request));
        assertThrows(MemoryStoreApplicationService.MemoryAccessDeniedException.class,
                () -> service.claimMaintenance(principal, 13L));
        verifyNoInteractions(documentService);
    }

    @Test
    void maintenanceClaimReturnsUnforgeableOwnerAndCanOnlyRenewThatOwner() {
        MemoryStoreDO own = store(11L, 7L, "AGENT", 42L);
        when(stores.findById(7L, 11L)).thenReturn(own);
        when(acls.listByStore(7L, 11L)).thenReturn(List.of());
        when(stores.claimMaintenance(eq(7L), eq(11L), anyString(), eq(99L), any(), any())).thenReturn(1);
        when(stores.renewMaintenance(eq(7L), eq(11L), anyString(), any(), any())).thenReturn(1);
        DispatchMcpTokenService.DispatchPrincipal principal = new DispatchMcpTokenService.DispatchPrincipal(
                7L, 9L, 99L, 42L, WorkspaceAccessLevel.READ_WRITE);

        var lease = service.claimMaintenance(principal, 11L);

        assertTrue(lease.acquired());
        assertTrue(lease.leaseId().matches("[0-9a-f-]{36}"));
        assertTrue(service.renewMaintenance(principal, 11L, lease.leaseId()));
    }

    @Test
    void terminalRetryCanOnlyReclaimItsPreviouslyEstablishedStoreLineage() {
        MemoryStoreDO own = store(11L, 7L, "AGENT", 42L);
        when(stores.findById(7L, 11L)).thenReturn(own);
        when(acls.listByStore(7L, 11L)).thenReturn(List.of());
        when(stores.reclaimMaintenance(eq(7L), eq(11L), anyString(), eq(99L), any(), any())).thenReturn(1);
        DispatchMcpTokenService.DispatchPrincipal principal = new DispatchMcpTokenService.DispatchPrincipal(
                7L, 9L, 99L, 42L, WorkspaceAccessLevel.READ_WRITE);

        var lease = service.reclaimMaintenance(principal, 11L);

        assertTrue(lease.acquired());
        verify(stores).reclaimMaintenance(eq(7L), eq(11L), eq(lease.leaseId()), eq(99L), any(), any());
    }

    @Test
    void currentFencingLeaseAllowsMaintenanceMutation() {
        MemoryStoreDO own = store(11L, 7L, "AGENT", 42L);
        when(stores.findById(7L, 11L)).thenReturn(own);
        when(acls.listByStore(7L, 11L)).thenReturn(List.of());
        String leaseId = "12345678-1234-1234-1234-123456789abc";
        when(stores.lockActiveMaintenanceOwner(eq(7L), eq(11L), any())).thenReturn(leaseId);
        MemoryMutationRequest request = new MemoryMutationRequest(MemoryMutationRequest.Operation.UPDATE,
                "topic.md", "topic.md", "body", 1, "key", leaseId);
        MemoryDocumentDO acknowledged = new MemoryDocumentDO();
        when(documentService.mutate(eq(7L), eq(11L), eq(request), any())).thenReturn(acknowledged);
        DispatchMcpTokenService.DispatchPrincipal principal = new DispatchMcpTokenService.DispatchPrincipal(
                7L, 9L, 99L, 42L, WorkspaceAccessLevel.READ_WRITE);
        assertSame(acknowledged, service.mutateForAgent(principal, 11L, request));
    }

    @Test
    void personalAgentStoreCannotBeArchivedAndBrickFutureBootstrap() {
        MemoryStoreDO own = store(11L, 7L, "AGENT", 42L);
        when(stores.findById(7L, 11L)).thenReturn(own);
        when(acls.listByStore(7L, 11L)).thenReturn(List.of());

        assertThrows(IllegalArgumentException.class, () -> service.archiveStore(7L, 9L, true, 11L, 0));
        verify(stores, never()).archive(anyLong(), anyLong(), anyInt(), anyLong());
    }

    @Test
    void memberListsEveryActiveStoreIncludingOtherAgentsPersonalStoresAsReadOnly() {
        MemoryStoreDO org = store(13L, 7L, "ORG", 0L);
        MemoryStoreDO squad = store(14L, 7L, "SQUAD", 8L);
        MemoryStoreDO own = store(11L, 7L, "AGENT", 42L);
        MemoryStoreDO other = store(12L, 7L, "AGENT", 43L);
        when(stores.listActive(7L)).thenReturn(List.of(org, squad, own, other));
        when(acls.listByStore(anyLong(), anyLong())).thenReturn(List.of());

        var visible = service.listForUser(7L, 9L, false);

        assertEquals(List.of(13L, 14L, 11L, 12L), visible.stream().map(MemoryStoreDO::getId).toList());
        assertEquals(List.of("READ", "READ", "READ", "READ"),
                visible.stream().map(MemoryStoreDO::getPermission).toList());
    }

    @Test
    void memberReadsAnotherAgentsStoreButCannotMutateIt() {
        MemoryStoreDO other = store(12L, 7L, "AGENT", 43L);
        MemoryDocumentDO document = new MemoryDocumentDO();
        document.setPath("topic.md");
        when(stores.findById(7L, 12L)).thenReturn(other);
        when(acls.listByStore(7L, 12L)).thenReturn(List.of());
        when(documents.listLive(7L, 12L)).thenReturn(List.of(document));
        when(changes.listAfterRevision(7L, 12L, 0L, 100)).thenReturn(List.of(new MemoryChangeDO()));

        assertEquals(List.of(document), service.documentsForUser(7L, 9L, false, 12L));
        assertEquals(1, service.historyForUser(7L, 9L, false, 12L, 0L, 100).size());
        assertThrows(MemoryStoreApplicationService.MemoryAccessDeniedException.class,
                () -> service.mutateForUser(7L, 9L, false, 12L,
                        MemoryMutationRequest.create("topic.md", "body", "key")));
        verifyNoInteractions(documentService);
    }

    @Test
    void memberCannotAdministerStoresOrAccessControlLists() {
        when(stores.findById(7L, 12L)).thenReturn(store(12L, 7L, "AGENT", 43L));
        when(acls.listByStore(7L, 12L)).thenReturn(List.of());

        assertThrows(MemoryStoreApplicationService.MemoryAccessDeniedException.class,
                () -> service.aclsForUser(7L, 9L, false, 12L));
        assertThrows(MemoryStoreApplicationService.MemoryAccessDeniedException.class,
                () -> service.archiveStore(7L, 9L, false, 12L, 0));
        assertThrows(MemoryStoreApplicationService.MemoryAccessDeniedException.class,
                () -> service.createStore(7L, 9L, false, store(0L, 7L, "ORG", 0L)));
        verify(acls, never()).insert(any());
    }

    @Test
    void workspaceAdministratorStillSeesAndMutatesEveryStore() {
        MemoryStoreDO other = store(12L, 7L, "AGENT", 43L);
        MemoryMutationRequest request = MemoryMutationRequest.create("topic.md", "body", "key");
        MemoryDocumentDO acknowledged = new MemoryDocumentDO();
        when(stores.listActive(7L)).thenReturn(List.of(other));
        when(stores.findById(7L, 12L)).thenReturn(other);
        when(acls.listByStore(7L, 12L)).thenReturn(List.of());
        when(documentService.mutate(eq(7L), eq(12L), eq(request), any())).thenReturn(acknowledged);

        assertEquals(List.of("ADMIN"),
                service.listForUser(7L, 9L, true).stream().map(MemoryStoreDO::getPermission).toList());
        assertSame(acknowledged, service.mutateForUser(7L, 9L, true, 12L, request));
    }

    @Test
    void aclGrantedMemberRegainsWriteOnThatStoreOnly() {
        MemoryStoreDO granted = store(13L, 7L, "SQUAD", 8L);
        MemoryStoreDO baseline = store(14L, 7L, "SQUAD", 9L);
        MemoryStoreAclDO acl = new MemoryStoreAclDO();
        acl.setTenantId(7L);
        acl.setStoreId(13L);
        acl.setSubjectType("USER");
        acl.setSubjectRef("9");
        acl.setPermission("WRITE");
        MemoryMutationRequest request = MemoryMutationRequest.create("topic.md", "body", "key");
        MemoryDocumentDO acknowledged = new MemoryDocumentDO();
        when(stores.findById(7L, 13L)).thenReturn(granted);
        when(stores.findById(7L, 14L)).thenReturn(baseline);
        when(acls.listByStore(7L, 13L)).thenReturn(List.of(acl));
        when(acls.listByStore(7L, 14L)).thenReturn(List.of());
        when(documentService.mutate(eq(7L), eq(13L), eq(request), any())).thenReturn(acknowledged);

        assertSame(acknowledged, service.mutateForUser(7L, 9L, false, 13L, request));
        assertThrows(MemoryStoreApplicationService.MemoryAccessDeniedException.class,
                () -> service.mutateForUser(7L, 9L, false, 14L, request));
    }

    private MemoryStoreDO store(long id, long tenantId, String scope, long ownerRef) {
        MemoryStoreDO store = new MemoryStoreDO();
        store.setId(id);
        store.setTenantId(tenantId);
        store.setScope(scope);
        store.setOwnerRef(ownerRef);
        store.setCurrentRevision(0L);
        store.setVersion(0);
        store.setStatus("ACTIVE");
        return store;
    }
}
