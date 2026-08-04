package com.aliyun.autowonder.log.metric.monitor;


import java.util.LinkedHashMap;
import java.util.Map;

import com.aliyun.autowonder.util.MetricUtils;
import org.hyperic.sigar.FileSystem;
import org.hyperic.sigar.FileSystemUsage;
import org.hyperic.sigar.SigarException;
import org.hyperic.sigar.Sigar;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Metric;

public class DiskGaugeSet extends BaseSigarMetricSet {

    public DiskGaugeSet(Sigar sigar) {
        super(sigar);
    }

    @Override
    public Map<String, Metric> getMetrics() {
        final Map<String, Metric> gauges = new LinkedHashMap<>();
        for (FileSystem fs : getFslist()) {
            if (FileSystem.TYPE_LOCAL_DISK == fs.getType()) {
                gauges.put(getGaugeName("disk_total", fs), (Gauge<Long>) () -> this.getFileSystemUsage(fs.getDirName()).getTotal());
                gauges.put(getGaugeName("disk_free", fs), (Gauge<Long>) () -> this.getFileSystemUsage(fs.getDirName()).getFree());
                gauges.put(getGaugeName("disk_avail", fs), (Gauge<Long>) () -> this.getFileSystemUsage(fs.getDirName()).getAvail());
                gauges.put(getGaugeName("disk_used", fs), (Gauge<Long>) () -> this.getFileSystemUsage(fs.getDirName()).getUsed());
                gauges.put(getGaugeName("disk_usePercent", fs), (Gauge<Double>) () -> this.getFileSystemUsage(fs.getDirName()).getUsePercent());
            }
        }
        return gauges;
    }

    private FileSystem[] getFslist() {
        try {
            return sigar.getFileSystemList();
        } catch (SigarException e) {
            return new FileSystem[] {};
        }
    }

    private FileSystemUsage getFileSystemUsage(String dirName) {
        try {
            return sigar.getFileSystemUsage(dirName);
        } catch (SigarException e) {
            return new FileSystemUsage();
        }
    }

    private String getGaugeName(String name, FileSystem fs) {
        return MetricUtils.name(name,
                "type_name", fs.getTypeName(),
                "sys_type_name", fs.getSysTypeName(),
                "dev_name", fs.getDevName(),
                "dir_name", fs.getDirName()
        );
    }

}
