package com.aliyun.autowonder.scheduledtask;

import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

@Component
public class ScheduledTaskScannerLifecycle implements SmartLifecycle {
    private final ScheduledTaskScheduler scanner;
    private final ScheduledTaskProperties properties;
    private volatile ThreadPoolTaskScheduler scheduler;

    public ScheduledTaskScannerLifecycle(ScheduledTaskScheduler scanner, ScheduledTaskProperties properties) {
        this.scanner = scanner;
        this.properties = properties;
    }

    @Override
    public void start() {
        if (isRunning()) return;
        ThreadPoolTaskScheduler dedicated = new ThreadPoolTaskScheduler();
        dedicated.setThreadNamePrefix("scheduled-task-scanner-");
        dedicated.setDaemon(true);
        dedicated.initialize();
        try {
            // Call the injected transaction proxy, outside the shared maintenance scheduler.
            dedicated.scheduleWithFixedDelay(scanner::scan, properties.getScanFixedDelayMs());
            scheduler = dedicated;
        } catch (RuntimeException failure) {
            dedicated.shutdown();
            throw failure;
        }
    }

    @Override
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
    }

    @Override
    public boolean isRunning() {
        return scheduler != null;
    }
}
