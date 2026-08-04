package com.aliyun.autowonder.log.metric;

import com.aliyun.autowonder.util.MetricUtils;
import com.codahale.metrics.MetricRegistry;


public class ExceptionMetricReporter {
    private static MetricRegistry metricRegistry;

    public static void setMetricRegistry(MetricRegistry m){
        metricRegistry = m;
    }

    public static void metric(String exceptionName, String fileName, String method, String line) {
        if(metricRegistry == null) {
            return;
        }
        metricRegistry.meter(MetricUtils.namePrefix("sys","class", "class", fileName,"exception", exceptionName)).mark();
        metricRegistry.meter(MetricUtils.namePrefix("sys","method", "method", method,"exception", exceptionName)).mark();
        metricRegistry.meter(MetricUtils.namePrefix("sys","line", "line", line,"exception", exceptionName)).mark();
    }

    public static void metricLoaderError(String loaderPhase, String exceptionName, String line) {
        if(metricRegistry == null) {
            return;
        }
        metricRegistry.meter(MetricUtils.namePrefix("sys","loaderErr", "phase", loaderPhase,"exception", exceptionName, "line", line)).mark();
    }
}
