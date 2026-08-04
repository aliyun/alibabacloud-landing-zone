package com.aliyun.autowonder.memory;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MemoryScopeResolver {
    private final MemoryDao memoryDao;

    public MemoryScopeResolver(MemoryDao memoryDao) {
        this.memoryDao = memoryDao;
    }

    public List<MemoryDO> listApplicable(long tenantId, long agentId) {
        List<MemoryDO> memories = memoryDao.listApplicableToAgent(tenantId, agentId);
        return memories == null ? List.of() : memories;
    }
}
