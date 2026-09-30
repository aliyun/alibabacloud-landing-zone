package com.aliyun.autowonder.memory.store;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Service;

@Service
public class MemoryStoreAccessService {
    public record Subjects(Long userId, Long agentId, Set<Long> squadIds, Set<String> roles,
                           boolean workspaceAdmin) {
        public Subjects {
            squadIds = squadIds == null ? Set.of() : Set.copyOf(squadIds);
            roles = roles == null ? Set.of() : Set.copyOf(roles);
        }
    }

    public MemoryPermission resolve(MemoryStoreDO store, List<MemoryStoreAclDO> aclEntries,
                                    Subjects subjects) {
        if (store == null || subjects == null) {
            return MemoryPermission.NONE;
        }
        if (subjects.workspaceAdmin()) {
            return MemoryPermission.ADMIN;
        }

        MemoryPermission result = "AGENT".equals(store.getScope())
                && store.getOwnerRef() != null
                && store.getOwnerRef().equals(subjects.agentId())
                ? MemoryPermission.WRITE : MemoryPermission.NONE;
        if (aclEntries == null) {
            return result;
        }
        for (MemoryStoreAclDO acl : aclEntries) {
            if (acl == null || !store.getTenantId().equals(acl.getTenantId())
                    || !store.getId().equals(acl.getStoreId()) || !matches(acl, subjects)) {
                continue;
            }
            try {
                result = MemoryPermission.strongest(result, MemoryPermission.valueOf(acl.getPermission()));
            } catch (IllegalArgumentException ignored) {
                // Unknown permissions fail closed.
            }
        }
        return result;
    }

    private boolean matches(MemoryStoreAclDO acl, Subjects subjects) {
        String ref = acl.getSubjectRef();
        if (ref == null || acl.getSubjectType() == null) {
            return false;
        }
        return switch (acl.getSubjectType()) {
            case "USER" -> ref.equals(String.valueOf(subjects.userId()));
            case "AGENT" -> ref.equals(String.valueOf(subjects.agentId()));
            case "SQUAD" -> subjects.squadIds().stream().anyMatch(id -> ref.equals(String.valueOf(id)));
            case "ROLE" -> subjects.roles().contains(ref);
            default -> false;
        };
    }
}
