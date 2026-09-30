package com.aliyun.autowonder.memory.store;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;

@Component
public class MemoryImportLifecycleTask {
    static final Duration STALE_AFTER = Duration.ofDays(30);

    private final MemoryImportDao imports;
    private final Clock clock;

    @Autowired
    public MemoryImportLifecycleTask(MemoryImportDao imports) {
        this(imports, Clock.systemUTC());
    }

    MemoryImportLifecycleTask(MemoryImportDao imports, Clock clock) {
        this.imports = imports;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${autowonder.memory.import-lifecycle-fixed-delay-ms:3600000}")
    public void markStaleSources() {
        Instant cutoff = clock.instant().minus(STALE_AFTER);
        imports.markStaleBefore(Date.from(cutoff));
    }
}
