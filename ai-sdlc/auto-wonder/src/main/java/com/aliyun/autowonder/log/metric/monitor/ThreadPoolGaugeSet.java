package com.aliyun.autowonder.log.metric.monitor;

import com.aliyun.autowonder.util.MetricUtils;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricSet;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.embedded.tomcat.TomcatWebServer;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;

import java.util.LinkedHashMap;
import java.util.Map;

public class ThreadPoolGaugeSet implements MetricSet {

    private Logger logger = LoggerFactory.getLogger(getClass());

    private ServletWebServerApplicationContext applicationContext;

    public ThreadPoolGaugeSet(ServletWebServerApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public Map<String, Metric> getMetrics() {
        final Map<String, Metric> gauges = new LinkedHashMap<>();

            try {
                gauges.put(MetricUtils.name("active_count"), (Gauge<Integer>) () -> {
                    if(applicationContext != null && applicationContext.getWebServer() != null) {
                        ThreadPoolExecutor executor = getExecutor(applicationContext);
                        return executor.getActiveCount();
                    }
                    return 0;
                });
                gauges.put(MetricUtils.name("pool_size"), (Gauge<Integer>) () -> {
                    if(applicationContext != null && applicationContext.getWebServer() != null) {
                        ThreadPoolExecutor executor = getExecutor(applicationContext);
                        return executor.getPoolSize();
                    }
                    return 0;});
                gauges.put(MetricUtils.name("queue_size"), (Gauge<Integer>) () -> {
                    if(applicationContext != null && applicationContext.getWebServer() != null) {
                        ThreadPoolExecutor executor = getExecutor(applicationContext);
                        return executor.getQueue().size();
                    }
                    return 0;});

            } catch (Throwable e) {
                logger.error("register metrics error.", e);
            }

        return gauges;
    }


    private ThreadPoolExecutor getExecutor(ServletWebServerApplicationContext applicationContext) {
        org.apache.catalina.startup.Tomcat tomcat = ((TomcatWebServer) applicationContext.getWebServer()).getTomcat();
        ThreadPoolExecutor executor = (ThreadPoolExecutor)tomcat.getConnector().getProtocolHandler().getExecutor();
        return executor;
    }


}
