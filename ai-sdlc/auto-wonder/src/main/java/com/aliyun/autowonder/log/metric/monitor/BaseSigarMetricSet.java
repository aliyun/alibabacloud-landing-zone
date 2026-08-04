package com.aliyun.autowonder.log.metric.monitor;

import org.hyperic.sigar.SigarProxy;

import com.codahale.metrics.MetricSet;

import java.util.Objects;

public abstract class BaseSigarMetricSet implements MetricSet {

    protected SigarProxy sigar;

    protected BaseSigarMetricSet(SigarProxy sigar) {
        this.sigar = Objects.requireNonNull(sigar, "sigar");
    }

}
