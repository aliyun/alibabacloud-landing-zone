package com.aliyun.autowonder.configuration;

import com.aliyun.autowonder.log.metric.ExceptionMetricReporter;
import com.aliyun.autowonder.log.metric.RpcInvokeExtendMetricReporter;
import com.aliyun.autowonder.log.metric.monitor.*;
import com.aliyun.openservices.aliyun.log.producer.LogProducer;
import com.aliyun.openservices.aliyun.log.producer.Producer;
import com.aliyun.openservices.aliyun.log.producer.ProducerConfig;
import com.aliyun.autowonder.log.metric.MetricReporter;
import com.aliyun.autowonder.util.HostInfoUtils;
import com.aliyun.openservices.aliyun.log.producer.ProjectConfig;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.jvm.BufferPoolMetricSet;
import com.codahale.metrics.jvm.ClassLoadingGaugeSet;
import com.codahale.metrics.jvm.GarbageCollectorMetricSet;
import com.codahale.metrics.jvm.MemoryUsageGaugeSet;
import org.hyperic.sigar.Sigar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.annotation.PreDestroy;
import java.lang.management.ManagementFactory;
import java.util.Map;
import java.util.TreeMap;
import java.util.Optional;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Configuration
public class MetricMonitorConfiguration {
    private Logger logger = LoggerFactory.getLogger(getClass());

    private final SlsProperties properties;
    private final SigarConfiguration sigarConfiguration;

    @Autowired(required = false)
    private ServletWebServerApplicationContext applicationContext;

    private MetricReporter reporter;
    private Producer producer;

    public MetricMonitorConfiguration(SlsProperties properties, SigarConfiguration sigarConfiguration) {
        this.properties = properties;
        this.sigarConfiguration = sigarConfiguration;
    }

    @Primary
    @Bean(name = "autowonderMonitorRegistry")
    public MetricRegistry getMetricRegistry() {
        MetricRegistry registry = new MetricRegistry();

        if (properties.isEnabled()) {
            properties.validate();
            ProducerConfig producerConfig = new ProducerConfig();
            producerConfig.setMaxBlockMs(1000);
            producer = new LogProducer(producerConfig);
            producer.putProjectConfig(new ProjectConfig(properties.getProject(), properties.getEndpoint(),
                    properties.getAccessKeyId(), properties.getAccessKeySecret()));
            reporter = MetricReporter.forRegistry(registry)
                    .convertRatesTo(TimeUnit.SECONDS)
                    .convertDurationsTo(TimeUnit.NANOSECONDS)
                    .prefixedWith("group_host")
                    .producer(producer)
                    .project(properties.getProject())
                    .logstore(properties.getMetricLogStore())
                    .defaultLabels(this.getHostLabels())
                    .build();
            reporter.start(30, TimeUnit.SECONDS);
        }

        registry.gauge("mtrs_cnt", () -> (new Gauge<Integer>() {
            @Override
            public Integer getValue() {
                return registry.getMetrics().size();
            }}));

        // JVM
        registry.registerAll("jvm", new MemoryUsageGaugeSet());
        registry.registerAll("jvm", new GarbageCollectorMetricSet());
        registry.registerAll("jvm", new ClassLoadingGaugeSet());
        registry.registerAll("jvm.buffers", new BufferPoolMetricSet(ManagementFactory.getPlatformMBeanServer()));

        registerSystemMetrics(registry, sigarConfiguration.createSigar());

        // web tomcat thread pool
        registry.registerAll("sys", new ThreadPoolGaugeSet(applicationContext));

        // business thread pool
        BizThreadPoolGaugeSet bizThreadPoolGaugeSet = new BizThreadPoolGaugeSet(registry);
        bizThreadPoolGaugeSet.register("AsyncTaskThreadPool", (ThreadPoolExecutor) ThreadPoolManager.asyncTaskThreadPool);
        bizThreadPoolGaugeSet.register("MetricCallBackThreadPool", (ThreadPoolExecutor) ThreadPoolManager.metricCallBackExecutor);

        ExceptionMetricReporter.setMetricRegistry(registry);
        RpcInvokeExtendMetricReporter.setMetricRegistry(registry);

        return registry;
    }

    static void registerSystemMetrics(MetricRegistry registry, Optional<Sigar> capability) {
        registry.registerAll("sys", new MemoryGaugeSet());
        capability.ifPresent(sigar -> {
            registry.registerAll("sys", new CpuPercGaugeSet(sigar));
            registry.registerAll("sys", new LoadGaugeSet(sigar));
            registry.registerAll("sys", new DiskGaugeSet(sigar));
            registry.registerAll("sys", new NetGaugeSet(sigar));
        });
    }

    private TreeMap<String, String> getHostLabels() {
        Map<String, String> hostInfo = HostInfoUtils.getHostInfo();
        TreeMap<String, String> labels = new TreeMap<>();
        labels.put("ip", hostInfo.get(HostInfoUtils.IP_KEY));
        labels.put("hostname", hostInfo.get(HostInfoUtils.HOST_NAME_KEY));
        labels.put("app_group", hostInfo.get(HostInfoUtils.APP_GROUP_KEY));
        labels.put("idc_name", hostInfo.get(HostInfoUtils.IDC_NAME_KEY));
        labels.put("use_type", hostInfo.get(HostInfoUtils.APP_USE_TYPE_KEY));
        labels.put("service_state", hostInfo.get(HostInfoUtils.SERVICE_STATE_KEY));
        labels.put("region_id", hostInfo.get(HostInfoUtils.DEPLOY_REGION_ID));
        return labels;
    }

    @PreDestroy
    public void destroy() {
        logger.info("Shutting down metric monitor components...");

        try {
            if (reporter != null) {
                logger.info("Stopping metric reporter...");
                reporter.stop();
                logger.info("Metric reporter stopped");
            }

            Thread.sleep(1000);

            if (producer != null) {
                logger.info("Closing log producer...");
                producer.close();
                logger.info("Log producer closed");
            }

        } catch (Exception e) {
            logger.error("Error shutting down metric monitor components", e);
        }

        logger.info("Metric monitor components shutdown complete");
    }
}
