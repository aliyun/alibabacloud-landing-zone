package com.aliyun.autowonder.log.metric.monitor;

import com.aliyun.autowonder.util.MetricUtils;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadPoolExecutor;

public class BizThreadPoolGaugeSet {

    private static final Logger LOGGER = LoggerFactory.getLogger(BizThreadPoolGaugeSet.class);

    private MetricRegistry metricRegistry;

    public static Map<String, ThreadPoolExecutor> executorMap = new ConcurrentHashMap<>();

    public void register(String name, ThreadPoolExecutor executorService) {
        if(executorMap.containsKey(name)) {
            LOGGER.warn("threadpool monitor executor {} already exists.", name);
        }
        executorMap.put(name, executorService);

        metricRegistry.registerAll("biz_threadpool", () -> {
            final Map<String, Metric> gauges = new LinkedHashMap<>();

            try {
                gauges.put(MetricUtils.name("active_count","pool_name", name), (Gauge<Integer>) () -> {
                    return executorService.getActiveCount();
                });
                gauges.put(MetricUtils.name("pool_size","pool_name", name), (Gauge<Integer>) () -> {
                    return executorService.getPoolSize();
                });
                gauges.put(MetricUtils.name("queue_size", "pool_name", name), (Gauge<Integer>) () -> {
                    return executorService.getQueue().size();
                });
            } catch (Throwable e) {
                LOGGER.error("thread pool register metrics error.", e);
            }
            return gauges;
        });
    }

    public BizThreadPoolGaugeSet(MetricRegistry metricRegistry) {
        this.metricRegistry = metricRegistry;
    }
}
