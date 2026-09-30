package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.memory.MemoryDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Bounded all-space sweep; one bad source cannot prevent later sources from migrating. */
@Component
public class LegacyMemoryMigrationTask {
    private static final Logger LOG = LoggerFactory.getLogger(LegacyMemoryMigrationTask.class);
    private final MemoryDao legacy;
    private final LegacyMemoryMigrationService migration;
    private volatile boolean ready;
    private long cursor;

    public LegacyMemoryMigrationTask(MemoryDao legacy, LegacyMemoryMigrationService migration) {
        this.legacy = legacy; this.migration = migration;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() { ready = true; }

    @Scheduled(initialDelayString = "${autowonder.memory.legacy-migration-initial-delay-ms:1000}",
            fixedDelayString = "${autowonder.memory.legacy-migration-fixed-delay-ms:30000}")
    public synchronized void migrateBatch() {
        if (!ready) return;
        var batch = legacy.listAdoptedForMigration(cursor, 100);
        int migrated = 0;
        for (var source : batch) {
            try {
                if (migration.migrateOne(source.getId())) migrated++;
            } catch (RuntimeException error) {
                LOG.warn("Legacy memory migration pending tenant={} memory={} cause={}",
                        source.getTenantId(), source.getId(), error.getClass().getSimpleName());
            }
            cursor = source.getId();
        }
        if (batch.size() < 100) cursor = 0;
        if (!batch.isEmpty()) LOG.info("Legacy memory migration batch scanned={} migrated={}", batch.size(), migrated);
    }
}
