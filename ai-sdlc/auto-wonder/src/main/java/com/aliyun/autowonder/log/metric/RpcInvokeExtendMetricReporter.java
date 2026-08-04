package com.aliyun.autowonder.log.metric;

import com.aliyun.autowonder.util.MetricUtils;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.SlidingTimeWindowArrayReservoir;
import com.codahale.metrics.Timer;

import java.util.concurrent.TimeUnit;

public class RpcInvokeExtendMetricReporter {
    private static MetricRegistry metricRegistry;

    public static void setMetricRegistry(MetricRegistry m){
        metricRegistry = m;
    }

    public static void penetrateMetric(String serviceName, boolean penetrate) {
        if(metricRegistry == null) {
            return;
        }
        metricRegistry.meter(MetricUtils.name("rpc_service_invoke","service", serviceName, "penetrate", String.valueOf(penetrate))).mark();
    }

    public static Timer.Context penetrateWholeRtCostMetric(String serviceName, boolean penetrate, boolean localProcess) {
        if(metricRegistry == null) {
            return null;
        }
        return metricRegistry.timer(MetricUtils.name("rpc_service_invoke","service", serviceName, "penetrate", String.valueOf(penetrate), "localProcess", String.valueOf(localProcess)), () -> new Timer(new SlidingTimeWindowArrayReservoir(60, TimeUnit.SECONDS))).time();
    }

}
