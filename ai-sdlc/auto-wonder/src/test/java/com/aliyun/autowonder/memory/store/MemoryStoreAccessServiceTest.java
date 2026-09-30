package com.aliyun.autowonder.memory.store;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryStoreAccessServiceTest {
    private final MemoryStoreAccessService service = new MemoryStoreAccessService();

    @Test
    void ownerAndWorkspaceAdministratorReceiveImplicitPermissions() {
        MemoryStoreDO privateStore = store(7L, "AGENT", 42L);

        assertEquals(MemoryPermission.WRITE, service.resolve(privateStore, List.of(),
                new MemoryStoreAccessService.Subjects(9L, 42L, Set.of(), Set.of(), false)));
        assertEquals(MemoryPermission.ADMIN, service.resolve(privateStore, List.of(),
                new MemoryStoreAccessService.Subjects(9L, 99L, Set.of(), Set.of(), true)));
    }

    @Test
    void strongestMatchingAclWinsAcrossUserAgentSquadAndRole() {
        MemoryStoreDO shared = store(7L, "SQUAD", 88L);
        List<MemoryStoreAclDO> acls = List.of(
                acl(7L, "USER", "9", "READ"),
                acl(7L, "AGENT", "42", "WRITE"),
                acl(7L, "SQUAD", "88", "READ"),
                acl(7L, "ROLE", "MEMORY_ADMIN", "ADMIN"));

        assertEquals(MemoryPermission.ADMIN, service.resolve(shared, acls,
                new MemoryStoreAccessService.Subjects(9L, 42L, Set.of(88L),
                        Set.of("MEMORY_ADMIN"), false)));
    }

    @Test
    void crossTenantAclNeverMatches() {
        MemoryStoreDO shared = store(7L, "ORG", 0L);
        assertEquals(MemoryPermission.NONE, service.resolve(shared,
                List.of(acl(8L, "USER", "9", "ADMIN")),
                new MemoryStoreAccessService.Subjects(9L, 42L, Set.of(), Set.of(), false)));
    }

    @Test
    void aclForAnotherStoreNeverMatches() {
        MemoryStoreDO shared = store(7L, "ORG", 0L);
        MemoryStoreAclDO wrongStore = acl(7L, "USER", "9", "ADMIN");
        wrongStore.setStoreId(99L);

        assertEquals(MemoryPermission.NONE, service.resolve(shared, List.of(wrongStore),
                new MemoryStoreAccessService.Subjects(9L, 42L, Set.of(), Set.of(), false)));
    }

    private MemoryStoreDO store(long tenantId, String scope, long owner) {
        MemoryStoreDO store = new MemoryStoreDO();
        store.setId(3L);
        store.setTenantId(tenantId);
        store.setScope(scope);
        store.setOwnerRef(owner);
        return store;
    }

    private MemoryStoreAclDO acl(long tenantId, String type, String ref, String permission) {
        MemoryStoreAclDO acl = new MemoryStoreAclDO();
        acl.setTenantId(tenantId);
        acl.setStoreId(3L);
        acl.setSubjectType(type);
        acl.setSubjectRef(ref);
        acl.setPermission(permission);
        return acl;
    }
}
