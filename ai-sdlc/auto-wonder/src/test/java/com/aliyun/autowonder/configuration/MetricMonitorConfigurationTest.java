package com.aliyun.autowonder.configuration;

import com.codahale.metrics.MetricRegistry;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetricMonitorConfigurationTest {

    @Test
    void emptySigarCapabilityKeepsNonNativeMetricsOnly() {
        MetricRegistry registry = new MetricRegistry();

        MetricMonitorConfiguration.registerSystemMetrics(registry, Optional.empty());

        assertTrue(registry.getMetrics().containsKey("sys.mem_total"));
        assertFalse(registry.getMetrics().containsKey("sys.load1"));
        assertFalse(registry.getMetrics().keySet().stream().anyMatch(name -> name.startsWith("sys.disk_")));
        assertFalse(registry.getMetrics().keySet().stream().anyMatch(name -> name.startsWith("sys.tx_bytes")));
    }

    @Test
    void localHostnameStillRegistersPortableSystemMetrics() {
        MetricRegistry registry = new MetricRegistry();

        MetricMonitorConfiguration.registerSystemMetrics(registry, Optional.empty());

        assertTrue(registry.getMetrics().containsKey("sys.mem_total"));
    }
}
