package com.aliyun.autowonder.log.metric.monitor;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.aliyun.autowonder.util.HostInfoUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricSet;

public class MemoryGaugeSet implements MetricSet {

    private MemoryStat stat = new MemoryStat();

    private long lastTs;

    private Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    public Map<String, Metric> getMetrics() {
        final Map<String, Metric> gauges = new LinkedHashMap<>();
        gauges.put("mem_total", (Gauge<Long>) () -> gatherMemory().getMemTotal());
        gauges.put("mem_available", (Gauge<Long>) () -> gatherMemory().getMemAvailable());
        gauges.put("mem_utilization", (Gauge<Double>) () -> gatherMemory().getMemUtilization());
        return gauges;
    }

    protected boolean isContainerEnv() {
        return HostInfoUtils.isContainerEnv();
    }
    protected boolean fileExists(String path) {
        return new File(path).exists();
    }
    protected String readFirstLine(String path) throws IOException {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            return r.readLine();
        }
    }
    protected java.util.List<String> readMeminfoLines(Path path) throws IOException {
        return Files.readAllLines(path);
    }

    protected MemoryStat gatherMemory() {
        if ((System.currentTimeMillis() - lastTs) < TimeUnit.SECONDS.toMillis(10)) {
            return stat;
        }
        lastTs = System.currentTimeMillis();
        stat = new MemoryStat();

        try {
            if (isContainerEnv()) {
                String limitPath = fileExists("/sys/fs/cgroup/memory.max")
                        ? "/sys/fs/cgroup/memory.max"
                        : "/sys/fs/cgroup/memory/memory.limit_in_bytes";
                String usagePath = fileExists("/sys/fs/cgroup/memory.current")
                        ? "/sys/fs/cgroup/memory.current"
                        : "/sys/fs/cgroup/memory/memory.usage_in_bytes";
                if (fileExists(limitPath) && fileExists(usagePath)) {
                    String limitLine = readFirstLine(limitPath);
                    if (limitLine != null && !"max".equals(limitLine)) {
                        stat.setMemTotal(Long.parseLong(limitLine));
                    }
                    String usageLine = readFirstLine(usagePath);
                    if (usageLine != null && stat.getMemTotal() > 0) {
                        stat.setMemAvailable(stat.getMemTotal() - Long.parseLong(usageLine));
                    }
                }
            } else {
                Path path = Paths.get("/proc/meminfo");
                if (fileExists("/proc/meminfo")) {
                    java.util.List<String> lines = readMeminfoLines(path);
                    if (lines != null) {
                        for (String line : lines) {
                            if (line == null) continue;
                            String[] cols = line.split("\\s+");
                            if (cols.length < 2) continue;
                            if ("MemTotal:".equals(cols[0])) {
                                stat.setMemTotal(getBytes(cols));
                            } else if ("MemAvailable:".equals(cols[0])) {
                                stat.setMemAvailable(getBytes(cols));
                            }
                        }
                    }
                }
            }

            stat.setMemUtilization(stat.getMemTotal() == 0 ? 0 : 1 - (stat.getMemAvailable() * 1.0 / stat.getMemTotal()));
        } catch (IOException | NumberFormatException e) {
            logger.warn("gatherMemory error", e);
        }
        return stat;
    }

    private long getBytes(String[] cols) {
        long value;
        try {
            value = Long.parseLong(cols[1]);
        } catch (NumberFormatException e) {
            logger.warn("getBytes error", e);
            value = 0;
        }
        return value * 1024;
    }

    static class MemoryStat {

        private long memTotal;

        private long memAvailable;

        private double memUtilization;

        public long getMemTotal() {
            return memTotal;
        }

        public void setMemTotal(long memTotal) {
            this.memTotal = memTotal;
        }

        public long getMemAvailable() {
            return memAvailable;
        }

        public void setMemAvailable(long memAvailable) {
            this.memAvailable = memAvailable;
        }

        public double getMemUtilization() {
            return memUtilization;
        }

        public void setMemUtilization(double memUtilization) {
            this.memUtilization = memUtilization;
        }

    }

}
