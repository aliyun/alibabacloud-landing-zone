package com.aliyun.autowonder.scheduledtask.compat;

import com.aliyun.autowonder.util.MetricUtils;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class V037CompatibilityMetrics {

    private static final String MODE_GAUGE_PREFIX = "autowonder_schema_mode:";
    private static final Set<String> READY_ENTRIES = Set.of(
            "http", "scheduler", "compensation", "mcp", "daemon", "realtime", "other");

    private final MetricRegistry registry;

    public V037CompatibilityMetrics(MetricRegistry registry, V037SchemaCapability capability) {
        this.registry = Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(capability, "capability");
        Map<String, Integer> gauges = new LinkedHashMap<>();
        String modeGaugeName = MetricUtils.name("autowonder_schema_mode",
                "mode", capability.mode().name(),
                "mapper_mode", capability.mapperMode().name());
        gauges.put(modeGaugeName, 1);
        gauges.put("scheduled_task_schema_available",
                capability.scheduledAvailable() ? 1 : 0);
        registerGauges(gauges, modeGaugeName);
    }

    void registerScheduledTaskCapabilityAvailable(boolean available) {
        synchronized (registry) {
            String name = "scheduled_task_capability_available";
            int value = available ? 1 : 0;
            verifyCompatible(name, value);
            if (!registry.getMetrics().containsKey(name)) {
                registry.register(name, new CapabilityGauge(value));
            }
        }
    }

    public Meter schemaNotReady(String entry) {
        String boundedEntry = entry != null && READY_ENTRIES.contains(entry) ? entry : "other";
        Meter meter = registry.meter(MetricUtils.name(
                "scheduled_task_schema_not_ready_total", "entry", boundedEntry));
        meter.mark();
        return meter;
    }

    private void registerGauges(Map<String, Integer> gauges, String modeGaugeName) {
        synchronized (registry) {
            registry.getMetrics().forEach((name, metric) -> {
                if (name.startsWith(MODE_GAUGE_PREFIX)
                        && !name.equals(modeGaugeName)
                        && metric instanceof CapabilityGauge) {
                    throw new IllegalStateException("different V037 mode metric already registered: " + name);
                }
            });
            gauges.forEach(this::verifyCompatible);
            gauges.forEach((name, value) -> {
                if (!registry.getMetrics().containsKey(name)) {
                    registry.register(name, new CapabilityGauge(value));
                }
            });
        }
    }

    private void verifyCompatible(String name, int value) {
        Metric existing = registry.getMetrics().get(name);
        if (existing != null
                && (!(existing instanceof CapabilityGauge gauge) || gauge.value != value)) {
            throw new IllegalStateException("conflicting metric already registered: " + name);
        }
    }

    private static final class CapabilityGauge implements Gauge<Integer> {
        private final int value;

        private CapabilityGauge(int value) {
            this.value = value;
        }

        @Override
        public Integer getValue() {
            return value;
        }
    }
}
