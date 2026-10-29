package com.aliyun.autowonder.configuration;

import com.aliyun.autowonder.log.BizLog;
import com.aliyun.autowonder.log.BizLogProducer;
import com.aliyun.autowonder.log.metric.ExceptionMetricReporter;
import com.aliyun.autowonder.log.metric.RpcInvokeExtendMetricReporter;
import com.aliyun.openservices.aliyun.log.producer.Producer;
import com.codahale.metrics.MetricRegistry;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelemetryIsolationTest {
    @AfterEach
    void clearStaticMetricRegistries() {
        ExceptionMetricReporter.setMetricRegistry(null);
        RpcInvokeExtendMetricReporter.setMetricRegistry(null);
    }

    private ApplicationContextRunner context(SlsProperties properties) {
        return new ApplicationContextRunner()
                .withUserConfiguration(MetricMonitorConfiguration.class, BizLogProducer.class,
                        SigarConfiguration.class)
                .withBean(SlsProperties.class, () -> properties)
                .withPropertyValues("autowonder.sls.enabled=" + properties.isEnabled(),
                        "autowonder.metrics.sigar.enabled=false");
    }

    @Test
    void disabledExportKeepsRegistryAndLocalBusinessLogsWithoutCredentialAccess() {
        SlsProperties properties = exportProperties(true);
        // An unrelated Producer must not accidentally enable the qualified metric exporter.
        Producer unrelated = mock(Producer.class);
        context(properties).withBean("unrelatedProducer", Producer.class, () -> unrelated)
                .withPropertyValues("autowonder.telemetry.sls-enabled=false").run(context -> {
                    assertNull(context.getStartupFailure());
                    assertFalse(context.containsBean("autowonderMonitorLogProducer"));
                    MetricMonitorConfiguration configuration = context.getBean(MetricMonitorConfiguration.class);
                    assertNull(ReflectionTestUtils.getField(configuration, "producer"));
                    assertNull(ReflectionTestUtils.getField(configuration, "reporter"));
                    MetricRegistry registry = context.getBean(MetricRegistry.class);
                    assertTrue(registry.getGauges().keySet().stream().anyMatch(name -> name.startsWith("jvm.")));
                    assertTrue(registry.getGauges().keySet().stream().anyMatch(name -> name.startsWith("biz_threadpool.")));
                    registry.meter("telemetry-test-business").mark();
                    assertEquals(1, registry.getMeters().get("telemetry-test-business").getCount());
                    int before = registry.getMetrics().size();
                    ExceptionMetricReporter.metric("Expected", "TelemetryTest", "invoke", "1");
                    RpcInvokeExtendMetricReporter.penetrateMetric("telemetry-test", false);
                    assertTrue(registry.getMetrics().size() > before);
                    BizLogProducer business = context.getBean(BizLogProducer.class);
                    assertNull(ReflectionTestUtils.getField(business, "producer"));
                    assertLocalBusinessEvent(business);
                    verify(properties, never()).getAccessKeyId();
                    verify(properties, never()).getAccessKeySecret();
                    verifyNoInteractions(unrelated);
                });
    }

    @Test
    void missingSwitchRetainsBothProducersAndMetricReporter() {
        assertExportEnabled();
    }

    @Test
    void explicitTrueRetainsBothProducersAndMetricReporter() {
        assertExportEnabled("autowonder.telemetry.sls-enabled=true");
    }

    private void assertExportEnabled(String... overrides) {
        SlsProperties properties = exportProperties(true);
        context(properties).withPropertyValues(overrides).run(context -> {
            assertNull(context.getStartupFailure());
            Producer producer = context.getBean("autowonderMonitorLogProducer", Producer.class);
            MetricMonitorConfiguration configuration = context.getBean(MetricMonitorConfiguration.class);
            assertSame(producer, ReflectionTestUtils.getField(configuration, "producer"));
            assertNotNull(ReflectionTestUtils.getField(configuration, "reporter"));
            assertNotNull(ReflectionTestUtils.getField(context.getBean(BizLogProducer.class), "producer"));
            verify(properties, times(2)).getAccessKeyId();
            verify(properties, times(2)).getAccessKeySecret();
        });
    }

    @Test
    void communitySlsDisabledKeepsBothExportersDisabledByDefault() {
        SlsProperties properties = exportProperties(false);
        context(properties).run(context -> {
            assertNull(context.getStartupFailure());
            assertFalse(context.containsBean("autowonderMonitorLogProducer"));
            assertNull(ReflectionTestUtils.getField(context.getBean(BizLogProducer.class), "producer"));
            verify(properties, never()).getAccessKeyId();
            verify(properties, never()).getAccessKeySecret();
            assertLocalBusinessEvent(context.getBean(BizLogProducer.class));
        });
    }

    private SlsProperties exportProperties(boolean enabled) {
        SlsProperties properties = new SlsProperties();
        properties.setEnabled(enabled);
        properties.setEndpoint("localhost");
        properties.setProject("telemetry-test");
        properties.setSysLogStore("system");
        properties.setMetricLogStore("metrics");
        properties.setBizLogStore("business");
        properties.setAccessKeyId("test-only-id");
        properties.setAccessKeySecret("test-only-secret");
        properties.setTopic("test");
        properties.setSource("test");
        return spy(properties);
    }

    private void assertLocalBusinessEvent(BizLogProducer producer) {
        List<LogEvent> events = new ArrayList<>();
        Logger logger = (Logger) LogManager.getLogger(BizLogProducer.class);
        Level level = logger.getLevel();
        AbstractAppender appender = new AbstractAppender("telemetry-test", null,
                PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY) {
            @Override public void append(LogEvent event) { events.add(event.toImmutable()); }
        };
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.INFO);
        try {
            BizLog event = new BizLog();
            event.setRequestId("telemetry-local-event");
            event.setOperation("test-local-operation");
            event.setSuccess(true);
            producer.send(event);
            assertEquals(1, events.size());
            assertEquals(Level.INFO, events.get(0).getLevel());
            String message = events.get(0).getMessage().getFormattedMessage();
            assertTrue(message.contains("telemetry-local-event"));
            assertTrue(message.contains("test-local-operation"));
        } finally {
            logger.removeAppender(appender);
            logger.setLevel(level);
            appender.stop();
        }
    }
}
