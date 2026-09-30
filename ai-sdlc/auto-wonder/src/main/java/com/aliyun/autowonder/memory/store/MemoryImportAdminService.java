package com.aliyun.autowonder.memory.store;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class MemoryImportAdminService {
    private static final Set<String> MANAGED_STATUSES =
            Set.of("ACTIVE", "PAUSED", "RETIRED", "RECURATE_REQUESTED");
    private final MemoryImportDao imports;

    public MemoryImportAdminService(MemoryImportDao imports) {
        this.imports = imports;
    }

    public List<MemoryImportSourceDO> listSources(long tenantId) {
        List<MemoryImportSourceDO> result = imports.listSources(tenantId);
        return result == null ? List.of() : List.copyOf(result);
    }

    public List<MemoryImportReceiptDO> listReceipts(long tenantId, long sourceId) {
        List<MemoryImportReceiptDO> result = imports.listReceipts(tenantId, sourceId, 100);
        return result == null ? List.of() : List.copyOf(result);
    }

    @Transactional
    public void changeStatus(long tenantId, long sourceId, String status, int version) {
        requireSource(tenantId, sourceId);
        if (!MANAGED_STATUSES.contains(status)) {
            throw new IllegalArgumentException("unsupported import source status");
        }
        if (imports.updateSourceStatus(tenantId, sourceId, status, version) != 1) {
            throw new MemoryDocumentService.MemoryConflictException("memory import source version changed");
        }
    }

    private MemoryImportSourceDO requireSource(long tenantId, long sourceId) {
        MemoryImportSourceDO source = imports.findSourceById(tenantId, sourceId);
        if (source == null) throw new IllegalArgumentException("memory import source not found");
        return source;
    }
}
