package com.aliyun.autowonder.log.metric.monitor;

import java.util.LinkedHashMap;
import java.util.Map;

import com.aliyun.autowonder.util.MetricUtils;
import org.hyperic.sigar.NetInterfaceStat;
import org.hyperic.sigar.SigarException;
import org.hyperic.sigar.Sigar;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Metric;

public class NetGaugeSet extends BaseSigarMetricSet {

    public NetGaugeSet(Sigar sigar) {
        super(sigar);
    }

    private Logger logger = LoggerFactory.getLogger(getClass());

    @Override
    public Map<String, Metric> getMetrics() {
        final Map<String, Metric> gauges = new LinkedHashMap<>();
        try {
            for (String i : this.sigar.getNetInterfaceList()) {
                gauges.put(MetricUtils.name("tx_bytes", "interface", i), (Gauge<Long>) () -> getNetInterfaceStat(i).getTxBytes());
                gauges.put(MetricUtils.name("tx_packets", "interface", i), (Gauge<Long>) () -> getNetInterfaceStat(i).getTxPackets());
                gauges.put(MetricUtils.name("rx_bytes", "interface", i), (Gauge<Long>) () -> getNetInterfaceStat(i).getRxBytes());
                gauges.put(MetricUtils.name("rx_packets", "interface", i), (Gauge<Long>) () -> getNetInterfaceStat(i).getRxPackets());
            }
        } catch (SigarException e) {
            logger.error("register metrics error.", e);
        }
        return gauges;
    }

    private NetInterfaceStat getNetInterfaceStat(String i) {
        NetInterfaceStat stat;
        try {
            stat = this.sigar.getNetInterfaceStat(i);
        } catch (SigarException e) {
            stat = new NetInterfaceStat();
        }
        return stat;
    }

}
