package com.aliyun.autowonder.log.metric.monitor;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MemoryGaugeSetTest {

    @Test
    void readsCgroupV2MemoryLimitAndUsage() {
        MemoryGaugeSet gauges = new MemoryGaugeSet() {
            private final Map<String, String> values = Map.of(
                    "/sys/fs/cgroup/memory.max", "1048576",
                    "/sys/fs/cgroup/memory.current", "262144");

            protected boolean isContainerEnv() { return true; }
            protected boolean fileExists(String path) { return values.containsKey(path); }
            protected String readFirstLine(String path) throws IOException { return values.get(path); }
        };

        MemoryGaugeSet.MemoryStat stat = gauges.gatherMemory();

        assertEquals(1048576L, stat.getMemTotal());
        assertEquals(786432L, stat.getMemAvailable());
        assertEquals(0.25D, stat.getMemUtilization());
    }
}
