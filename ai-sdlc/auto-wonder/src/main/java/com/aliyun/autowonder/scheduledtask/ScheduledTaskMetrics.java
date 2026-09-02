package com.aliyun.autowonder.scheduledtask;

import com.aliyun.autowonder.util.MetricUtils;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Gauge;
import org.springframework.stereotype.Service;
import java.util.concurrent.TimeUnit;
import com.aliyun.autowonder.scheduledtask.compat.ScheduledTaskCapabilityGuard;

@Service
public class ScheduledTaskMetrics {
    private final MetricRegistry registry;
    private final ScheduledTaskRunDao runDao;
    private final ScheduledTaskCapabilityGuard capabilityGuard;
    @org.springframework.beans.factory.annotation.Autowired
    public ScheduledTaskMetrics(MetricRegistry registry, ScheduledTaskRunDao runDao,
            ScheduledTaskCapabilityGuard capabilityGuard) {
        this.registry = registry;
        this.runDao = runDao;
        this.capabilityGuard = capabilityGuard;
        registry.register("scheduled_task_active_runs", (Gauge<Long>) this::activeRuns);
    }
    long activeRuns() { return capabilityGuard.isAvailable() ? runDao.countActive() : 0L; }
    public void created(String triggerType) { registry.meter(MetricUtils.name("scheduled_task_run_created_total", "trigger_type", safe(triggerType))).mark(); }
    public void status(String status, String reason) { registry.meter(MetricUtils.name("scheduled_task_run_status_total", "status", safe(status), "reason", statusReason(reason))).mark(); }
    public void dueLag(long seconds) { registry.histogram("scheduled_task_scheduler_due_lag_seconds").update(Math.max(0, seconds)); }
    public void queueWait(long millis) { registry.timer("scheduled_task_run_queue_wait_seconds").update(Math.max(0, millis), TimeUnit.MILLISECONDS); }
    public void duration(long millis) { registry.timer("scheduled_task_run_duration_seconds").update(Math.max(0, millis), TimeUnit.MILLISECONDS); }
    public void degraded(String reason) { registry.meter(MetricUtils.name("scheduled_task_resume_degraded_total", "reason", statusReason(reason))).mark(); }
    private String statusReason(String value) {
        if (value == null || value.isBlank()) return "none";
        return switch (value) { case "OVERLAP", "MISFIRE_POLICY", "START_DEADLINE", "OWNER_INACTIVE", "SOURCE_EXECUTOR_TIMEOUT" -> value; default -> "other"; };
    }
    private String safe(String value) { return value == null || value.isBlank() ? "none" : value; }
}
