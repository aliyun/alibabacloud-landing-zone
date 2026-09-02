package com.aliyun.autowonder.scheduledtask.compat;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.scheduledtask.ScheduledTaskProperties;
import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.time.Instant;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledTaskCapabilityGuardTest {

    @ParameterizedTest
    @CsvSource({
            "false,true,true,false",
            "true,false,true,false",
            "true,true,false,false",
            "true,true,true,true"
    })
    void availabilityRequiresSchemaModuleAndCluster(boolean schema, boolean module,
                                                      boolean cluster, boolean expected) {
        Fixture fixture = fixture(schema, module, cluster, true);

        assertEquals(expected, fixture.guard.isAvailable());
        assertEquals(expected ? 1 : 0, fixture.registry.getGauges()
                .get("scheduled_task_capability_available").getValue());
        if (expected) {
            fixture.guard.requireAvailable();
        } else {
            BizException error = assertThrows(BizException.class, fixture.guard::requireAvailable);
            assertEquals("30006", error.getCode());
            assertEquals(1, fixture.registry.getMeters()
                    .get("scheduled_task_schema_not_ready_total:,entry=other").getCount());
        }
    }

    @Test
    void scannerRequiresItsOwnFlagInAdditionToAvailability() {
        assertFalse(fixture(true, true, true, false).guard.isScannerEnabled());
        assertTrue(fixture(true, true, true, true).guard.isScannerEnabled());
        assertFalse(fixture(false, true, true, true).guard.isScannerEnabled());
    }

    @Test
    void reasonUsesStableFailClosedPrecedence() {
        assertEquals("DATABASE_UPGRADE_REQUIRED",
                fixture(false, false, false, false).guard.snapshot().getReason());
        assertEquals("FEATURE_DISABLED",
                fixture(true, false, false, false).guard.snapshot().getReason());
        assertEquals("CLUSTER_NOT_READY",
                fixture(true, true, false, false).guard.snapshot().getReason());
        assertNull(fixture(true, true, true, false).guard.snapshot().getReason());
    }

    @Test
    void snapshotReportsFrozenSchemaModeAndConfiguredClusterReadiness() {
        ScheduledTaskCapabilityVO unavailable = fixture(false, true, true, false).guard.snapshot();
        ScheduledTaskCapabilityVO available = fixture(true, true, true, false).guard.snapshot();

        assertFalse(unavailable.isAvailable());
        assertEquals("LEGACY", unavailable.getMode());
        assertTrue(unavailable.isClusterReady());
        assertTrue(available.isAvailable());
        assertEquals("V037_READY", available.getMode());
    }

    @Test
    void freezesDeploymentFlagsSoAvailabilityAndSnapshotCannotDiverge() {
        ScheduledTaskProperties properties = new ScheduledTaskProperties();
        properties.setEnabled(true);
        properties.setScannerEnabled(true);
        properties.setClusterReadyAttestation(true);
        V037SchemaCapability capability = capability(true);
        MetricRegistry registry = new MetricRegistry();
        ScheduledTaskCapabilityGuard guard = new ScheduledTaskCapabilityGuard(
                capability, properties, new V037CompatibilityMetrics(registry, capability));

        properties.setEnabled(false);
        properties.setScannerEnabled(false);
        properties.setClusterReadyAttestation(false);

        assertTrue(guard.isAvailable());
        assertTrue(guard.isScannerEnabled());
        assertTrue(guard.snapshot().isAvailable());
        assertTrue(guard.snapshot().isClusterReady());
        assertNull(guard.snapshot().getReason());
        assertEquals(1, registry.getGauges()
                .get("scheduled_task_capability_available").getValue());
    }

    @Test
    void rejectedEntryIsBoundedAndAspectStopsInvocation() {
        Fixture fixture = fixture(false, true, true, false);
        AnnotatedService target = new AnnotatedService();
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(new ScheduledTaskCapabilityAspect(fixture.guard));
        AnnotatedService proxy = proxyFactory.getProxy();

        BizException error = assertThrows(BizException.class, proxy::invoke);

        assertEquals("30006", error.getCode());
        assertEquals(0, target.calls);
        assertEquals(1, fixture.registry.getMeters()
                .get("scheduled_task_schema_not_ready_total:,entry=other").getCount());
    }

    @Test
    void methodAnnotationOverridesClassEntry() {
        Fixture fixture = fixture(false, true, true, false);
        MethodEntryService target = new MethodEntryService();
        AspectJProxyFactory proxyFactory = new AspectJProxyFactory(target);
        proxyFactory.addAspect(new ScheduledTaskCapabilityAspect(fixture.guard));
        MethodEntryService proxy = proxyFactory.getProxy();

        assertThrows(BizException.class, proxy::invoke);

        assertEquals(1, fixture.registry.getMeters()
                .get("scheduled_task_schema_not_ready_total:,entry=mcp").getCount());
    }

    private Fixture fixture(boolean schema, boolean module, boolean cluster, boolean scanner) {
        ScheduledTaskProperties properties = new ScheduledTaskProperties();
        properties.setEnabled(module);
        properties.setClusterReadyAttestation(cluster);
        properties.setScannerEnabled(scanner);
        V037SchemaCapability capability = capability(schema);
        MetricRegistry registry = new MetricRegistry();
        V037CompatibilityMetrics metrics = new V037CompatibilityMetrics(registry, capability);
        return new Fixture(new ScheduledTaskCapabilityGuard(capability, properties, metrics), registry);
    }

    private V037SchemaCapability capability(boolean schema) {
        return new V037SchemaCapability(
                schema ? V037SchemaMode.V037_READY : V037SchemaMode.LEGACY,
                schema ? V037MapperMode.SOURCE_AWARE : V037MapperMode.LEGACY,
                schema, schema, false, Set.of(), Instant.EPOCH);
    }

    private record Fixture(ScheduledTaskCapabilityGuard guard, MetricRegistry registry) {
    }

    @RequiresScheduledTaskCapability(entry = "untrusted-entry")
    static class AnnotatedService {
        private int calls;

        public void invoke() {
            calls++;
        }
    }

    @RequiresScheduledTaskCapability(entry = "http")
    static class MethodEntryService {
        @RequiresScheduledTaskCapability(entry = "mcp")
        public void invoke() {
        }
    }
}
