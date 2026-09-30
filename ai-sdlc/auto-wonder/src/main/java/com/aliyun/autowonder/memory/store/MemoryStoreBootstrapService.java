package com.aliyun.autowonder.memory.store;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MemoryStoreBootstrapService {
    private final MemoryStoreDao storeDao;
    private final MemoryDocumentDao documentDao;

    public MemoryStoreBootstrapService(MemoryStoreDao storeDao, MemoryDocumentDao documentDao) {
        this.storeDao = storeDao;
        this.documentDao = documentDao;
    }

    @Transactional
    public MemoryStoreDO getOrCreate(long tenantId, String scope, long ownerRef, String name, long creatorId) {
        long normalizedOwner = "ORG".equals(scope) ? 0L : ownerRef;
        MemoryStoreDO existing = storeDao.findActive(tenantId, scope, normalizedOwner);
        if (existing != null) {
            return existing;
        }
        MemoryStoreDO store = new MemoryStoreDO();
        store.setTenantId(tenantId);
        store.setScope(scope);
        store.setOwnerRef(normalizedOwner);
        store.setName(name);
        store.setCurrentRevision(0L);
        store.setStatus("ACTIVE");
        store.setCreatorId(creatorId);
        store.setVersion(0);
        storeDao.insertIfAbsent(store);
        MemoryStoreDO created = storeDao.findActive(tenantId, scope, normalizedOwner);
        if (created == null) {
            throw new IllegalStateException("memory store bootstrap did not create an active store");
        }
        return created;
    }

    @Transactional
    public MemoryStoreDO ensureAgentStoreWithIndex(long tenantId, long agentId, long creatorId) {
        MemoryStoreDO store = getOrCreate(tenantId, "AGENT", agentId,
                "Agent " + agentId + " memory", creatorId);
        documentDao.insertEmptyIndexIfAbsent(tenantId, store.getId(), creatorId);
        return store;
    }
}
