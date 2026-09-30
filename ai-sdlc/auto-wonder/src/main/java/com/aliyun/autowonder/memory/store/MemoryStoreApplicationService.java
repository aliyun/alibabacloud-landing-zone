package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.memory.store.dto.MemoryChangePageVO;
import com.aliyun.autowonder.memory.store.dto.MemoryMutationRequest;
import com.aliyun.autowonder.memory.store.dto.MemorySnapshotVO;
import com.aliyun.autowonder.memory.store.dto.MemoryMaintenanceLeaseVO;
import com.aliyun.autowonder.mcp.DispatchMcpTokenService;
import com.aliyun.autowonder.squad.SquadMemberDO;
import com.aliyun.autowonder.squad.SquadMemberDao;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;
import java.util.Date;
import java.time.Instant;
import java.util.UUID;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryStoreApplicationService {
    private final MemoryStoreDao storeDao;
    private final MemoryDocumentDao documentDao;
    private final MemoryChangeDao changeDao;
    private final MemoryStoreAclDao aclDao;
    private final SquadMemberDao squadMemberDao;
    private final MemoryStoreAccessService accessService;
    private final MemoryDocumentService documentService;
    private final MemorySnapshotService snapshotService;
    private final MemoryStoreBootstrapService bootstrapService;

    public MemoryStoreApplicationService(MemoryStoreDao storeDao, MemoryDocumentDao documentDao,
                                         MemoryChangeDao changeDao, MemoryStoreAclDao aclDao,
                                         SquadMemberDao squadMemberDao,
                                         MemoryStoreAccessService accessService,
                                         MemoryDocumentService documentService,
                                         MemorySnapshotService snapshotService,
                                         MemoryStoreBootstrapService bootstrapService) {
        this.storeDao = storeDao;
        this.documentDao = documentDao;
        this.changeDao = changeDao;
        this.aclDao = aclDao;
        this.squadMemberDao = squadMemberDao;
        this.accessService = accessService;
        this.documentService = documentService;
        this.snapshotService = snapshotService;
        this.bootstrapService = bootstrapService;
    }

    public MemorySnapshotVO snapshotForAgent(DispatchMcpTokenService.DispatchPrincipal principal) {
        bootstrapService.ensureAgentStoreWithIndex(principal.workspaceId(), principal.agentId(), principal.userId());
        MemoryStoreAccessService.Subjects subjects = agentSubjects(principal.workspaceId(), principal.agentId());
        List<MemorySnapshotVO.Store> visible = new ArrayList<>();
        for (MemoryStoreDO store : safe(storeDao.listActive(principal.workspaceId()))) {
            if ("AGENT".equals(store.getScope()) && !Objects.equals(store.getOwnerRef(), principal.agentId())) {
                continue;
            }
            MemoryPermission permission = permission(store, subjects);
            if (!permission.includes(MemoryPermission.READ)) {
                continue;
            }
            MemoryPermission effectivePermission = "AGENT".equals(store.getScope())
                    ? permission : MemoryPermission.READ;
            List<MemorySnapshotVO.Document> documents = safe(documentDao.listLive(
                    principal.workspaceId(), store.getId())).stream()
                    .map(document -> new MemorySnapshotVO.Document(document.getPath(), document.getContentMd(),
                            document.getVersion()))
                    .toList();
            visible.add(new MemorySnapshotVO.Store(store.getId(), store.getScope(), store.getOwnerRef(),
                    store.getCurrentRevision(), effectivePermission.name(), documents));
        }
        return snapshotService.compose(visible);
    }

    public Map<Long, Long> startingRevisionsForAgent(long tenantId, long agentId) {
        MemoryStoreAccessService.Subjects subjects = agentSubjects(tenantId, agentId);
        Map<Long, Long> result = new LinkedHashMap<>();
        for (MemoryStoreDO store : safe(storeDao.listActive(tenantId))) {
            if ("AGENT".equals(store.getScope()) && !Objects.equals(store.getOwnerRef(), agentId)) {
                continue;
            }
            if (permission(store, subjects).includes(MemoryPermission.READ)) {
                result.put(store.getId(), store.getCurrentRevision());
            }
        }
        return result;
    }

    public void validateRecall(DispatchMcpTokenService.DispatchPrincipal principal, long storeId, String path) {
        MemoryStoreDO store = requiredStore(principal.workspaceId(), storeId);
        if ("AGENT".equals(store.getScope()) && !Objects.equals(store.getOwnerRef(), principal.agentId())) {
            throw new MemoryAccessDeniedException();
        }
        require(permission(store, agentSubjects(principal.workspaceId(), principal.agentId())), MemoryPermission.READ);
        String safePath = MemoryPathValidator.requireSafe(path);
        if (documentDao.findByPath(principal.workspaceId(), storeId, safePath, false) == null) {
            throw new IllegalArgumentException("memory document not found");
        }
    }

    @Transactional
    public MemoryDocumentDO mutateForAgent(DispatchMcpTokenService.DispatchPrincipal principal, long storeId,
                                           MemoryMutationRequest request) {
        MemoryStoreDO store = requiredStore(principal.workspaceId(), storeId);
        requireOwnedAgentStore(principal, store);
        require(permission(store, agentSubjects(principal.workspaceId(), principal.agentId())),
                MemoryPermission.WRITE);
        String activeLease = storeDao.lockActiveMaintenanceOwner(principal.workspaceId(), storeId, new Date());
        String presentedLease = request == null ? null : request.maintenanceLeaseId();
        if (!Objects.equals(activeLease, presentedLease)) {
            throw new MemoryMaintenanceLeaseConflictException();
        }
        return documentService.mutate(principal.workspaceId(), storeId, request,
                new MemoryDocumentService.Actor("AGENT", String.valueOf(principal.agentId()),
                        principal.userId(), principal.dispatchId(), null));
    }

    @Transactional
    public MemoryDocumentDO mutateForExplicitAgent(long tenantId, long dispatchId, long agentId, long userId,
                                                    long storeId, MemoryMutationRequest request) {
        MemoryStoreDO store = requiredStore(tenantId, storeId);
        if (!"AGENT".equals(store.getScope()) || !Objects.equals(store.getOwnerRef(), agentId)) {
            throw new MemoryAccessDeniedException();
        }
        if (storeDao.lockActiveMaintenanceOwner(tenantId, storeId, new Date()) != null) {
            throw new MemoryMaintenanceLeaseConflictException();
        }
        return documentService.mutate(tenantId, storeId, request,
                new MemoryDocumentService.Actor("AGENT", String.valueOf(agentId), userId, dispatchId, null));
    }

    public MemoryMaintenanceLeaseVO claimMaintenance(DispatchMcpTokenService.DispatchPrincipal principal,
                                                      long storeId) {
        MemoryStoreDO store = requiredStore(principal.workspaceId(), storeId);
        requireOwnedAgentStore(principal, store);
        require(permission(store, agentSubjects(principal.workspaceId(), principal.agentId())), MemoryPermission.WRITE);
        Instant now = Instant.now();
        String leaseId = UUID.randomUUID().toString();
        boolean acquired = storeDao.claimMaintenance(principal.workspaceId(), storeId, leaseId,
                principal.dispatchId(),
                Date.from(now), Date.from(now.plusSeconds(600))) == 1;
        return acquired ? new MemoryMaintenanceLeaseVO(true, leaseId) : MemoryMaintenanceLeaseVO.unavailable();
    }

    public MemoryMaintenanceLeaseVO reclaimMaintenance(DispatchMcpTokenService.DispatchPrincipal principal,
                                                        long storeId) {
        MemoryStoreDO store = requiredStore(principal.workspaceId(), storeId);
        requireOwnedAgentStore(principal, store);
        require(permission(store, agentSubjects(principal.workspaceId(), principal.agentId())), MemoryPermission.WRITE);
        Instant now = Instant.now();
        String leaseId = UUID.randomUUID().toString();
        boolean acquired = storeDao.reclaimMaintenance(principal.workspaceId(), storeId, leaseId,
                principal.dispatchId(), Date.from(now), Date.from(now.plusSeconds(600))) == 1;
        return acquired ? new MemoryMaintenanceLeaseVO(true, leaseId) : MemoryMaintenanceLeaseVO.unavailable();
    }

    public boolean renewMaintenance(DispatchMcpTokenService.DispatchPrincipal principal, long storeId,
                                    String leaseId) {
        requireOwnedAgentStore(principal, requiredStore(principal.workspaceId(), storeId));
        Instant now = Instant.now();
        return validLeaseId(leaseId) && storeDao.renewMaintenance(principal.workspaceId(), storeId, leaseId,
                Date.from(now), Date.from(now.plusSeconds(600))) == 1;
    }

    public void releaseMaintenance(DispatchMcpTokenService.DispatchPrincipal principal, long storeId,
                                   String leaseId) {
        requireOwnedAgentStore(principal, requiredStore(principal.workspaceId(), storeId));
        if (validLeaseId(leaseId)) {
            storeDao.releaseMaintenance(principal.workspaceId(), storeId, leaseId);
        }
    }

    private boolean validLeaseId(String leaseId) {
        return leaseId != null && leaseId.matches("[0-9a-f-]{36}");
    }

    private void requireOwnedAgentStore(DispatchMcpTokenService.DispatchPrincipal principal, MemoryStoreDO store) {
        if (!"AGENT".equals(store.getScope()) || !Objects.equals(store.getOwnerRef(), principal.agentId())) {
            throw new MemoryAccessDeniedException();
        }
    }

    public MemoryChangePageVO changesForAgent(DispatchMcpTokenService.DispatchPrincipal principal, long storeId,
                                              long afterRevision, int requestedLimit) {
        MemoryStoreDO store = requiredStore(principal.workspaceId(), storeId);
        require(permission(store, agentSubjects(principal.workspaceId(), principal.agentId())),
                MemoryPermission.READ);
        int limit = Math.max(1, Math.min(requestedLimit, 500));
        List<MemoryChangeDO> changes = safe(changeDao.listAfterRevision(
                principal.workspaceId(), storeId, afterRevision, limit + 1));
        boolean more = changes.size() > limit;
        if (more) {
            changes = changes.subList(0, limit);
        }
        return new MemoryChangePageVO(storeId, store.getCurrentRevision(), List.copyOf(changes), more);
    }

    public List<MemoryStoreDO> listForUser(long tenantId, long userId, boolean administrator) {
        MemoryStoreAccessService.Subjects subjects = userSubjects(userId, administrator);
        List<MemoryStoreDO> visible = new ArrayList<>();
        for (MemoryStoreDO store : safe(storeDao.listActive(tenantId))) {
            MemoryPermission effective = permission(store, subjects);
            if (effective.includes(MemoryPermission.READ)) {
                store.setPermission(effective.name());
                visible.add(store);
            }
        }
        return List.copyOf(visible);
    }

    public List<MemoryDocumentDO> documentsForUser(long tenantId, long userId, boolean administrator,
                                                   long storeId) {
        MemoryStoreDO store = requiredStore(tenantId, storeId);
        require(permission(store, userSubjects(userId, administrator)), MemoryPermission.READ);
        return safe(documentDao.listLive(tenantId, storeId));
    }

    public MemoryStoreDO createStore(long tenantId, long userId, boolean administrator,
                                     MemoryStoreDO requested) {
        if (!administrator) throw new MemoryAccessDeniedException();
        if (requested == null || !Set.of("AGENT", "SQUAD", "ORG").contains(requested.getScope())) {
            throw new IllegalArgumentException("supported memory scope is required");
        }
        Long ownerRef = "ORG".equals(requested.getScope()) ? 0L : requested.getOwnerRef();
        if (!"ORG".equals(requested.getScope()) && (ownerRef == null || ownerRef <= 0)) {
            throw new IllegalArgumentException("memory owner is required");
        }
        MemoryStoreDO existing = storeDao.findActive(tenantId, requested.getScope(), ownerRef);
        if (existing != null) return existing;
        MemoryStoreDO store = new MemoryStoreDO();
        store.setTenantId(tenantId);
        store.setScope(requested.getScope());
        store.setOwnerRef(ownerRef);
        store.setName(requested.getName() == null || requested.getName().isBlank()
                ? requested.getScope() + " memory" : requested.getName().trim());
        store.setCurrentRevision(0L);
        store.setStatus("ACTIVE");
        store.setCreatorId(userId);
        store.setVersion(0);
        storeDao.insert(store);
        return store;
    }

    public void archiveStore(long tenantId, long userId, boolean administrator, long storeId, int version) {
        MemoryStoreDO store = requiredStore(tenantId, storeId);
        require(permission(store, userSubjects(userId, administrator)), MemoryPermission.ADMIN);
        if ("AGENT".equals(store.getScope())) {
            throw new IllegalArgumentException("agent personal memory store cannot be archived");
        }
        if (storeDao.archive(tenantId, storeId, version, userId) != 1) {
            throw new MemoryDocumentService.MemoryConflictException("memory store version changed");
        }
    }

    public static class MemoryMaintenanceLeaseConflictException extends RuntimeException {
        public MemoryMaintenanceLeaseConflictException() {
            super("memory maintenance lease is stale");
        }
    }

    public List<MemoryStoreAclDO> aclsForUser(long tenantId, long userId, boolean administrator, long storeId) {
        MemoryStoreDO store = requiredStore(tenantId, storeId);
        require(permission(store, userSubjects(userId, administrator)), MemoryPermission.ADMIN);
        return safe(aclDao.listByStore(tenantId, storeId));
    }

    public MemoryDocumentDO mutateForUser(long tenantId, long userId, boolean administrator, long storeId,
                                          MemoryMutationRequest request) {
        MemoryStoreDO store = requiredStore(tenantId, storeId);
        require(permission(store, userSubjects(userId, administrator)), MemoryPermission.WRITE);
        return documentService.mutate(tenantId, storeId, request,
                new MemoryDocumentService.Actor("USER", String.valueOf(userId), userId, null, null));
    }

    public List<MemoryChangeDO> historyForUser(long tenantId, long userId, boolean administrator,
                                               long storeId, long afterRevision, int requestedLimit) {
        MemoryStoreDO store = requiredStore(tenantId, storeId);
        require(permission(store, userSubjects(userId, administrator)), MemoryPermission.READ);
        return safe(changeDao.listAfterRevision(tenantId, storeId, afterRevision,
                Math.max(1, Math.min(requestedLimit, 500))));
    }

    public void putAcl(long tenantId, long userId, boolean administrator, long storeId, MemoryStoreAclDO acl) {
        MemoryStoreDO store = requiredStore(tenantId, storeId);
        require(permission(store, userSubjects(userId, administrator)), MemoryPermission.ADMIN);
        if (acl == null) {
            throw new IllegalArgumentException("ACL is required");
        }
        MemoryPermission.valueOf(acl.getPermission());
        if (!Set.of("USER", "AGENT", "SQUAD", "ROLE").contains(acl.getSubjectType())) {
            throw new IllegalArgumentException("unsupported ACL subject type");
        }
        acl.setTenantId(tenantId);
        acl.setStoreId(storeId);
        acl.setCreatorId(userId);
        aclDao.insert(acl);
    }

    public void deleteAcl(long tenantId, long userId, boolean administrator, long storeId, long aclId) {
        MemoryStoreDO store = requiredStore(tenantId, storeId);
        require(permission(store, userSubjects(userId, administrator)), MemoryPermission.ADMIN);
        aclDao.delete(tenantId, storeId, aclId);
    }

    private MemoryPermission permission(MemoryStoreDO store, MemoryStoreAccessService.Subjects subjects) {
        return accessService.resolve(store, safe(aclDao.listByStore(store.getTenantId(), store.getId())), subjects);
    }

    private MemoryStoreAccessService.Subjects agentSubjects(long tenantId, long agentId) {
        Set<Long> squads = safe(squadMemberDao.listByAgent(agentId)).stream()
                .filter(member -> member != null && Long.valueOf(tenantId).equals(member.getTenantId()))
                .map(SquadMemberDO::getSquadId).collect(Collectors.toSet());
        return new MemoryStoreAccessService.Subjects(null, agentId, squads, Set.of("ALL_AGENTS"), false);
    }

    private MemoryStoreAccessService.Subjects userSubjects(long userId, boolean administrator) {
        return new MemoryStoreAccessService.Subjects(userId, null, Set.of(), Set.of(), administrator);
    }

    private MemoryStoreDO requiredStore(long tenantId, long storeId) {
        MemoryStoreDO store = storeDao.findById(tenantId, storeId);
        if (store == null) {
            throw new IllegalArgumentException("memory store not found");
        }
        return store;
    }

    private void require(MemoryPermission actual, MemoryPermission expected) {
        if (!actual.includes(expected)) {
            throw new MemoryAccessDeniedException();
        }
    }

    private <T> List<T> safe(List<T> values) {
        return values == null ? List.of() : values;
    }

    public static class MemoryAccessDeniedException extends RuntimeException {
    }
}
