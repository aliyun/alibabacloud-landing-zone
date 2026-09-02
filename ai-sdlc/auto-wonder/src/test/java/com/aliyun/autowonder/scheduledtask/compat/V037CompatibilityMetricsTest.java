package com.aliyun.autowonder.scheduledtask.compat;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class V037CompatibilityMetricsTest {

    @Test
    void registersBoundedImmutableModeAndSchemaAvailabilityGauges() {
        MetricRegistry registry = new MetricRegistry();
        V037SchemaCapability capability = new V037SchemaCapability(
                V037SchemaMode.V037_READY, V037MapperMode.SOURCE_AWARE,
                true, true, false, Set.of(), Instant.EPOCH);

        new V037CompatibilityMetrics(registry, capability);
        new V037CompatibilityMetrics(registry, capability);

        String modeName = "autowonder_schema_mode:,mode=V037_READY,mapper_mode=SOURCE_AWARE";
        assertEquals(1, ((Gauge<?>) registry.getGauges().get(modeName)).getValue());
        assertEquals(1, ((Gauge<?>) registry.getGauges()
                .get("scheduled_task_schema_available")).getValue());
        assertFalse(registry.getGauges().containsKey("scheduled_task_capability_available"));
    }

    @Test
    void registersFinalCapabilityOnlyWhenDeploymentGuardSuppliesIt() {
        MetricRegistry registry = new MetricRegistry();
        V037SchemaCapability capability = new V037SchemaCapability(
                V037SchemaMode.V037_READY, V037MapperMode.SOURCE_AWARE,
                true, true, false, Set.of(), Instant.EPOCH);
        V037CompatibilityMetrics metrics = new V037CompatibilityMetrics(registry, capability);

        metrics.registerScheduledTaskCapabilityAvailable(false);
        metrics.registerScheduledTaskCapabilityAvailable(false);

        assertEquals(0, ((Gauge<?>) registry.getGauges()
                .get("scheduled_task_capability_available")).getValue());
        assertThrows(IllegalStateException.class,
                () -> metrics.registerScheduledTaskCapabilityAvailable(true));
    }

    @Test
    void mapsUnknownSchemaNotReadyEntryToOtherAndReusesMeter() {
        MetricRegistry registry = new MetricRegistry();
        V037SchemaCapability capability = new V037SchemaCapability(
                V037SchemaMode.V037_PARTIAL, V037MapperMode.SOURCE_AWARE,
                true, false, false, Set.of("scheduled_task"), Instant.EPOCH);
        V037CompatibilityMetrics metrics = new V037CompatibilityMetrics(registry, capability);

        Meter first = metrics.schemaNotReady("untrusted-cardinality");
        Meter second = metrics.schemaNotReady(null);

        String other = "scheduled_task_schema_not_ready_total:,entry=other";
        assertSame(first, second);
        assertEquals(2, registry.getMeters().get(other).getCount());
        assertEquals(Set.of(other), registry.getMeters().keySet());
    }

    @Test
    void preservesAllowedEntry() {
        MetricRegistry registry = new MetricRegistry();
        V037CompatibilityMetrics metrics = new V037CompatibilityMetrics(registry,
                new V037SchemaCapability(V037SchemaMode.LEGACY, V037MapperMode.LEGACY,
                        false, false, false, Set.of(), Instant.EPOCH));

        metrics.schemaNotReady("scheduler");

        assertEquals(1, registry.getMeters()
                .get("scheduled_task_schema_not_ready_total:,entry=scheduler").getCount());
    }

    @Test
    void rejectsConflictingCapabilityReuseInsteadOfLeavingStaleGauge() {
        MetricRegistry registry = new MetricRegistry();
        V037SchemaCapability ready = new V037SchemaCapability(
                V037SchemaMode.V037_READY, V037MapperMode.SOURCE_AWARE,
                true, true, false, Set.of(), Instant.EPOCH);
        V037SchemaCapability legacy = new V037SchemaCapability(
                V037SchemaMode.LEGACY, V037MapperMode.LEGACY,
                false, false, false, Set.of(), Instant.EPOCH);

        new V037CompatibilityMetrics(registry, ready);

        assertThrows(IllegalStateException.class,
                () -> new V037CompatibilityMetrics(registry, legacy));
        assertEquals(1, ((Gauge<?>) registry.getGauges()
                .get("scheduled_task_schema_available")).getValue());
        assertFalse(registry.getGauges().containsKey(
                "autowonder_schema_mode:,mode=LEGACY,mapper_mode=LEGACY"));
    }

    @Test
    void rejectsDifferentModeReuseEvenWhenAvailabilityValueMatches() {
        MetricRegistry registry = new MetricRegistry();
        V037SchemaCapability legacy = new V037SchemaCapability(
                V037SchemaMode.LEGACY, V037MapperMode.LEGACY,
                false, false, false, Set.of(), Instant.EPOCH);
        V037SchemaCapability partial = new V037SchemaCapability(
                V037SchemaMode.V037_PARTIAL, V037MapperMode.SOURCE_AWARE,
                true, false, false, Set.of("scheduled_task"), Instant.EPOCH);

        new V037CompatibilityMetrics(registry, legacy);

        assertThrows(IllegalStateException.class,
                () -> new V037CompatibilityMetrics(registry, partial));
        assertEquals(1, ((Gauge<?>) registry.getGauges().get(
                "autowonder_schema_mode:,mode=LEGACY,mapper_mode=LEGACY")).getValue());
        assertFalse(registry.getGauges().containsKey(
                "autowonder_schema_mode:,mode=V037_PARTIAL,mapper_mode=SOURCE_AWARE"));
        assertEquals(0, ((Gauge<?>) registry.getGauges()
                .get("scheduled_task_schema_available")).getValue());
    }
}
