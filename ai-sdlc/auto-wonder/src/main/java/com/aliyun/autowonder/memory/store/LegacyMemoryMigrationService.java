package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.memory.MemoryDao;
import com.aliyun.autowonder.memory.store.dto.MemoryTopicRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Set;

/** Lossless one-time import; the change receipt survives subsequent topic edits/deletions. */
@Service
public class LegacyMemoryMigrationService {
    private final MemoryDao legacy;
    private final MemoryChangeDao changes;
    private final MemoryTopicService topics;

    public LegacyMemoryMigrationService(MemoryDao legacy, MemoryChangeDao changes, MemoryTopicService topics) {
        this.legacy = legacy; this.changes = changes; this.topics = topics;
    }

    @Transactional
    public boolean migrateOne(long memoryId) {
        var source = legacy.lockAdoptedForMigration(memoryId);
        if (source == null || changes.findLegacyMigration(source.getTenantId(), memoryId) != null) return false;
        long tenantId = source.getTenantId();
        long owner = "ORG".equals(source.getScope()) ? 0 : source.getOwnerRef() == null ? -1 : source.getOwnerRef();
        String name = topics.ownerName(tenantId, source.getScope(), owner);
        long creator = source.getCreatorId() == null ? 0 : source.getCreatorId();
        var store = topics.ensureStore(tenantId, source.getScope(), owner, name, creator);
        String type = source.getType() == null ? "reference" : source.getType().toLowerCase(Locale.ROOT);
        if (!Set.of("user", "feedback", "project", "reference").contains(type)) type = "reference";
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("source_memory_id", memoryId);
        metadata.put("legacy_type", source.getType());
        metadata.put("legacy_source", source.getSource());
        if (source.getSourceRef() != null) metadata.put("legacy_source_ref", source.getSourceRef());
        String title = source.getTitle() == null || source.getTitle().isBlank() ? "历史记忆 " + memoryId : source.getTitle();
        var request = new MemoryTopicRequest(null, type, title, title,
                source.getContentMd(), null, "legacy-adopted:" + memoryId);
        topics.writeTopic(tenantId, store.getId(), creator, "legacy_" + memoryId + ".md", request,
                metadata, "legacy-adopted:" + memoryId, true, "LEGACY_MIGRATION");
        return true;
    }

    public record Progress(long migrated, long pending) {}

    @Transactional(readOnly = true)
    public Progress progress(long tenantId) {
        long eligible = legacy.countMigrationEligible(tenantId);
        long pending = legacy.countMigrationPending(tenantId);
        return new Progress(eligible - pending, pending);
    }
}
