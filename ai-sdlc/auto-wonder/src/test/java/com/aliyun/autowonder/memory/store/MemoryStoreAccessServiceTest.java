package com.aliyun.autowonder.memory.store;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryStoreAccessServiceTest {
    private final MemoryStoreAccessService service = new MemoryStoreAccessService();

    @Test
    void ownerAndWorkspaceAdministratorReceiveImplicitPermissions() {
        MemoryStoreDO privateStore = store(7L, "AGENT", 42L);

        assertEquals(MemoryPermission.WRITE, service.resolve(privateStore, List.of(),
                new MemoryStoreAccessService.Subjects(9L, 42L, Set.of(), Set.of(), false, false)));
        assertEquals(MemoryPermission.ADMIN, service.resolve(privateStore, List.of(),
                new MemoryStoreAccessService.Subjects(9L, 99L, Set.of(), Set.of(), true, true)));
    }

    @Test
    void workspaceMemberReceivesBaselineReadOnEveryStoreScope() {
        MemoryStoreAccessService.Subjects member =
                new MemoryStoreAccessService.Subjects(9L, null, Set.of(), Set.of(), false, true);

        assertEquals(MemoryPermission.READ, service.resolve(store(7L, "ORG", 0L), List.of(), member));
        assertEquals(MemoryPermission.READ, service.resolve(store(7L, "SQUAD", 88L), List.of(), member));
        assertEquals(MemoryPermission.READ, service.resolve(store(7L, "AGENT", 43L), List.of(), member));
    }

    @Test
    void baselineReadNeverLeaksIntoTheAgentSubjectPath() {
        MemoryStoreAccessService.Subjects agent =
                new MemoryStoreAccessService.Subjects(null, 42L, Set.of(), Set.of("ALL_AGENTS"), false, false);

        assertEquals(MemoryPermission.WRITE, service.resolve(store(7L, "AGENT", 42L), List.of(), agent));
        assertEquals(MemoryPermission.NONE, service.resolve(store(7L, "AGENT", 43L), List.of(), agent));
        assertEquals(MemoryPermission.NONE, service.resolve(store(7L, "ORG", 0L), List.of(), agent));
        assertEquals(MemoryPermission.NONE, service.resolve(store(7L, "SQUAD", 88L), List.of(), agent));
    }

    @Test
    void matchingAclStillPromotesMemberBaseline() {
        MemoryStoreDO shared = store(7L, "SQUAD", 88L);
        MemoryStoreAccessService.Subjects member = new MemoryStoreAccessService.Subjects(9L, null,
                Set.of(88L), Set.of("MEMORY_ADMIN"), false, true);

        assertEquals(MemoryPermission.READ,
                service.resolve(shared, List.of(acl(7L, "USER", "9", "READ")), member));
        assertEquals(MemoryPermission.WRITE,
                service.resolve(shared, List.of(acl(7L, "SQUAD", "88", "WRITE")), member));
        assertEquals(MemoryPermission.ADMIN,
                service.resolve(shared, List.of(acl(7L, "ROLE", "MEMORY_ADMIN", "ADMIN")), member));
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
                        Set.of("MEMORY_ADMIN"), false, false)));
    }

    @Test
    void crossTenantAclNeverMatches() {
        MemoryStoreDO shared = store(7L, "ORG", 0L);
        List<MemoryStoreAclDO> foreign = List.of(acl(8L, "USER", "9", "ADMIN"));

        assertEquals(MemoryPermission.NONE, service.resolve(shared, foreign,
                new MemoryStoreAccessService.Subjects(9L, 42L, Set.of(), Set.of(), false, false)));
        assertEquals(MemoryPermission.READ, service.resolve(shared, foreign,
                new MemoryStoreAccessService.Subjects(9L, null, Set.of(), Set.of(), false, true)));
    }

    @Test
    void aclForAnotherStoreNeverMatches() {
        MemoryStoreDO shared = store(7L, "ORG", 0L);
        MemoryStoreAclDO wrongStore = acl(7L, "USER", "9", "ADMIN");
        wrongStore.setStoreId(99L);
        List<MemoryStoreAclDO> entries = List.of(wrongStore);

        assertEquals(MemoryPermission.NONE, service.resolve(shared, entries,
                new MemoryStoreAccessService.Subjects(9L, 42L, Set.of(), Set.of(), false, false)));
        assertEquals(MemoryPermission.READ, service.resolve(shared, entries,
                new MemoryStoreAccessService.Subjects(9L, null, Set.of(), Set.of(), false, true)));
    }

    @Test
    void unknownOrIncompleteAclEntriesFailClosed() {
        MemoryStoreDO shared = store(7L, "ORG", 0L);
        MemoryStoreAccessService.Subjects member =
                new MemoryStoreAccessService.Subjects(9L, null, Set.of(), Set.of(), false, true);

        assertEquals(MemoryPermission.READ, service.resolve(shared,
                Arrays.asList(null, acl(7L, "USER", "9", "SUPERUSER")), member));
        assertEquals(MemoryPermission.READ,
                service.resolve(shared, List.of(acl(7L, null, "9", "ADMIN")), member));
        assertEquals(MemoryPermission.READ,
                service.resolve(shared, List.of(acl(7L, "USER", null, "ADMIN")), member));
        assertEquals(MemoryPermission.READ,
                service.resolve(shared, List.of(acl(7L, "TEAM", "9", "ADMIN")), member));
    }

    @Test
    void missingStoreOrSubjectsResolvesToNone() {
        assertEquals(MemoryPermission.NONE, service.resolve(null, List.of(),
                new MemoryStoreAccessService.Subjects(9L, null, Set.of(), Set.of(), false, true)));
        assertEquals(MemoryPermission.NONE, service.resolve(store(7L, "ORG", 0L), List.of(), null));
    }

    @Test
    void nullSquadAndRoleSetsAreNormalizedToEmpty() {
        MemoryStoreAccessService.Subjects member =
                new MemoryStoreAccessService.Subjects(9L, null, null, null, false, true);

        assertEquals(Set.of(), member.squadIds());
        assertEquals(Set.of(), member.roles());
        assertEquals(MemoryPermission.READ, service.resolve(store(7L, "ORG", 0L), List.of(), member));
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
