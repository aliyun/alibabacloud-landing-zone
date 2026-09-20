package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.util.MetricUtils;
import com.codahale.metrics.MetricRegistry;
import org.springframework.stereotype.Service;

import java.util.Locale;

/**
 * Observability for the live activity pipeline: how late runtime events arrive, whether realtime
 * fan-out failed, how often clients had to backfill after a reconnect, and how much the recent
 * window dropped.
 */
@Service
public class DispatchLiveActivityMetrics {

    private final MetricRegistry registry;

    public DispatchLiveActivityMetrics(MetricRegistry registry) {
        this.registry = registry;
    }

    /** Lag between the runtime reported event time and the moment the server persisted it. */
    public void reportLatency(long millis) {
        registry.histogram("dispatch_live_activity_report_latency_ms").update(Math.max(0L, millis));
    }

    public void published(String actionType) {
        registry.meter(MetricUtils.name("dispatch_live_activity_published_total",
                "action_type", safe(actionType))).mark();
    }

    public void publishFailed(String reason) {
        registry.meter(MetricUtils.name("dispatch_live_activity_publish_failed_total",
                "reason", safe(reason))).mark();
    }

    /** A read that supplied {@code afterSeq}, i.e. a client reconnect compensating a gap. */
    public void backfill() {
        registry.meter("dispatch_live_activity_backfill_total").mark();
    }

    /** Actions dropped because the recent window is bounded. */
    public void truncated(int dropped) {
        if (dropped > 0) {
            registry.meter("dispatch_live_activity_truncated_total").mark(dropped);
        }
    }

    /** Runtime-reported events dropped at ingestion, before any realtime fan-out. */
    public void filteredIngest(String eventType) {
        registry.meter(MetricUtils.name("dispatch_live_activity_filtered_ingest_total",
                "event_bucket", bucket(eventType))).mark();
    }

    /** Persisted events skipped while projecting a read (raw model output, thinking, guidance). */
    public void filteredRead(String eventType) {
        registry.meter(MetricUtils.name("dispatch_live_activity_filtered_read_total",
                "event_bucket", bucket(eventType))).mark();
    }

    /**
     * Collapses an arbitrary runtime event type into a small fixed label set, so a runtime that
     * reports unbounded event type names cannot inflate metric cardinality.
     */
    static String bucket(String eventType) {
        if (eventType == null || eventType.isBlank()) {
            return "none";
        }
        String type = eventType.toLowerCase(Locale.ROOT);
        if (type.startsWith("llm.")) {
            return "llm";
        }
        if (type.startsWith("guidance.")) {
            return "guidance";
        }
        if (type.startsWith("comment.")) {
            return "comment";
        }
        if (type.startsWith("agent.message")) {
            return "agent_message";
        }
        if (type.contains("thinking") || type.contains("reasoning") || type.contains("chain_of_thought")) {
            return "reasoning";
        }
        return "other";
    }

    private String safe(String value) {
        return value == null || value.isBlank() ? "none" : value;
    }
}
