package com.aliyun.autowonder.log.metric.monitor;


import com.aliyun.autowonder.util.HostInfoUtils;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Metric;
import org.hyperic.sigar.CpuPerc;
import org.hyperic.sigar.Sigar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class CpuPercGaugeSet extends BaseSigarMetricSet {

    public CpuPercGaugeSet(Sigar sigar) {
        super(sigar);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(CpuPercGaugeSet.class);
    private static final Pattern SPACE_PATTERN = Pattern.compile("\\s+");
    private static final BigInteger nanoSecondsPerSecondClockTick = BigInteger.valueOf(10000000L);

    private CpuPerc cpu;

    @Override
    public Map<String, Metric> getMetrics() {
        final Map<String, Metric> gauges = new LinkedHashMap<>();
        if (HostInfoUtils.isContainerEnv()) {
            gauges.put("cpu_combined", new TotalCPUPercentGauge());
            gauges.put("cpu_user", new UserCPUPercentGauge());
            gauges.put("cpu_sys", new SysCPUPercentGauge());
        } else {
            gauges.put("cpu_idle", (Gauge<Double>)() -> fetchCpuPerc().getIdle());
            gauges.put("cpu_combined", (Gauge<Double>)() -> fetchCpuPerc().getCombined());
            gauges.put("cpu_irq", (Gauge<Double>)() -> fetchCpuPerc().getIrq());
            gauges.put("cpu_nice", (Gauge<Double>)() -> fetchCpuPerc().getNice());
            gauges.put("cpu_soft_irq", (Gauge<Double>)() -> fetchCpuPerc().getSoftIrq());
            gauges.put("cpu_stolen", (Gauge<Double>)() -> fetchCpuPerc().getStolen());
            gauges.put("cpu_sys", (Gauge<Double>)() -> fetchCpuPerc().getSys());
            gauges.put("cpu_user", (Gauge<Double>)() -> fetchCpuPerc().getUser());
            gauges.put("cpu_wait", (Gauge<Double>)() -> fetchCpuPerc().getWait());
        }
        return gauges;
    }

    private CpuPercGaugeSet fetchCpuPerc() {
        try {
            cpu = sigar.getCpuPerc();
        } catch (Throwable e) {
            cpu = null;
            LOGGER.info(e.getMessage(), e);
        }
        return this;
    }

    private double getUser() {
        return cpu == null ? 0 : cpu.getUser();
    }

    private double getSys() {
        return cpu == null ? 0 : cpu.getSys();
    }

    private double getNice() {
        return cpu == null ? 0 : cpu.getNice();
    }

    private double getIdle() {
        return cpu == null ? 0 : cpu.getIdle();
    }

    private double getWait() {
        return cpu == null ? 0 : cpu.getWait();
    }

    private double getIrq() {
        return cpu == null ? 0 : cpu.getIrq();
    }

    private double getSoftIrq() {
        return cpu == null ? 0 : cpu.getSoftIrq();
    }

    private double getStolen() {
        return cpu == null ? 0 : cpu.getStolen();
    }

    private double getCombined() {
        return cpu == null ? 0 : cpu.getCombined();
    }

    private abstract class AsiCPUPercentGauge implements Gauge<Double> {

        private BigInteger previousSystemCPU;
        private BigInteger previousCPU;

        @Override
        public Double getValue() {
            BigInteger systemCPU = getSystemCPUUsage();
            BigInteger gaugeCPU = getGaugeCPUUsage();
            BigDecimal systemDelta = BigDecimal.ZERO;
            if (this.previousSystemCPU != null) {
                systemDelta = new BigDecimal(systemCPU.subtract(this.previousSystemCPU));
            }
            this.previousSystemCPU = systemCPU;

            double cpuPercent = 0.0;
            if (this.previousCPU != null) {
                BigDecimal totalCPUDelta = new BigDecimal(gaugeCPU.subtract(this.previousCPU));
                if (!systemDelta.equals(BigDecimal.ZERO)) {
                    cpuPercent = totalCPUDelta.divide(systemDelta, 4, BigDecimal.ROUND_HALF_UP).doubleValue();
                }
            }
            this.previousCPU = gaugeCPU;
            return cpuPercent;
        }

        protected abstract BigInteger getGaugeCPUUsage();
    }

    private class TotalCPUPercentGauge extends AsiCPUPercentGauge {
        @Override
        protected BigInteger getGaugeCPUUsage() {
            return getTotalCPUUsage();
        }
    }

    private class UserCPUPercentGauge extends AsiCPUPercentGauge {
        @Override
        protected BigInteger getGaugeCPUUsage() {
            Map<String, BigInteger> cpu = getCPUUsage();
            BigInteger userCPU = cpu.getOrDefault("user", BigInteger.ZERO);
            return userCPU;
        }
    }

    private class SysCPUPercentGauge extends AsiCPUPercentGauge {
        @Override
        protected BigInteger getGaugeCPUUsage() {
            Map<String, BigInteger> cpu = getCPUUsage();
            BigInteger sysCPU = cpu.getOrDefault("system", BigInteger.ZERO);
            return sysCPU;
        }
    }

    private BigInteger getSystemCPUUsage() {
        List<String> lines = this.readFileLines("/proc/stat");
        if (lines != null) {
            for (String line : lines) {
                if (line == null) {
                    continue;
                }
                String[] cols = SPACE_PATTERN.split(line);
                if (cols.length < 2) {
                    continue;
                }
                if ("cpu".equals(cols[0])) {
                    long totalClockTicks = 0L;
                    for (int i = 1; i < cols.length; i++) {
                        totalClockTicks += Long.parseLong(cols[i]);
                    }
                    BigInteger value = BigInteger.valueOf(totalClockTicks).multiply(nanoSecondsPerSecondClockTick);
                    return value;
                }
            }
        }
        return null;
    }

    private BigInteger getTotalCPUUsage() {
        Map<String, BigInteger> cgroupV2 = readCgroupV2CpuStat();
        if (cgroupV2.containsKey("usage")) {
            return cgroupV2.get("usage");
        }
        List<String> lines = this.readFileLines("/sys/fs/cgroup/cpu/cpuacct.usage");
        BigInteger value = lines == null ? null : new BigInteger(lines.get(0));
        return value;
    }

    private Map<String, BigInteger> getCPUUsage() {
        Map<String, BigInteger> cgroupV2 = readCgroupV2CpuStat();
        if (!cgroupV2.isEmpty()) {
            cgroupV2.remove("usage");
            return cgroupV2;
        }
        Map<String, BigInteger> usageMap = new HashMap<>(4);
        List<String> lines = this.readFileLines("/sys/fs/cgroup/cpu/cpuacct.stat");
        if (lines != null) {
            for (String line : lines) {
                if (line == null) {
                    continue;
                }
                String[] cols = SPACE_PATTERN.split(line);
                if (cols.length != 2) {
                    continue;
                }
                BigInteger value = new BigInteger(cols[1]).multiply(nanoSecondsPerSecondClockTick);
                usageMap.put(cols[0], value);
            }
        }
        return usageMap;
    }

    private Map<String, BigInteger> readCgroupV2CpuStat() {
        Map<String, BigInteger> usage = new HashMap<>(4);
        List<String> lines = this.readFileLines("/sys/fs/cgroup/cpu.stat");
        if (lines == null) {
            return usage;
        }
        for (String line : lines) {
            String[] cols = SPACE_PATTERN.split(line == null ? "" : line.trim());
            if (cols.length != 2 || !cols[0].endsWith("_usec")) {
                continue;
            }
            String key = cols[0].substring(0, cols[0].length() - "_usec".length());
            usage.put(key, new BigInteger(cols[1]).multiply(BigInteger.valueOf(1000L)));
        }
        return usage;
    }

    private int getCPUCount() {
        List<String> lines = this.readFileLines("/sys/fs/cgroup/cpu/cpuacct.usage_percpu");
        if (lines == null) {
            throw new IllegalStateException("getCPUCount error");
        }
        String line = lines.get(0);
        String[] cols = SPACE_PATTERN.split(line);
        return cols.length;
    }

    protected List<String> readFileLines(final String filePath) {
        try {
            File file = new File(filePath);
            if (file.exists()) {
                return Files.readAllLines(file.toPath());
            }
        } catch (Exception e) {
            LOGGER.warn("readFileLines error", e);
        }
        return null;
    }

}
