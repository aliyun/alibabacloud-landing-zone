package com.aliyun.autowonder.log.metric.monitor;

import java.util.LinkedHashMap;
import java.util.Map;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Metric;
import org.hyperic.sigar.Sigar;

public class LoadGaugeSet extends BaseSigarMetricSet {

    public LoadGaugeSet(Sigar sigar) {
        super(sigar);
    }

    private double[] loads;

    @Override
    public Map<String, Metric> getMetrics() {
        final Map<String, Metric> gauges = new LinkedHashMap<>();
        gauges.put("load1", (Gauge<Double>) () -> fetchLoads()[0]);
        gauges.put("load5", (Gauge<Double>) () -> fetchLoads()[1]);
        gauges.put("load15", (Gauge<Double>) () -> fetchLoads()[2]);
        gauges.put("core", (Gauge<Double>) () -> (double)Runtime.getRuntime().availableProcessors());
        return gauges;
    }

    private double[] fetchLoads() {
        try {
            loads = sigar.getLoadAverage();
        } catch (Throwable e) {
            loads = new double[] { 0, 0, 0 };
        }
        return loads;
    }

}
