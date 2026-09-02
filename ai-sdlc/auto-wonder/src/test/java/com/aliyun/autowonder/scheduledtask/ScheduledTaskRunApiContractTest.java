package com.aliyun.autowonder.scheduledtask;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledTaskRunApiContractTest {
    @Test
    void lifecycleMapperAllowsCancellation() throws Exception {
        String xml = Files.readString(Path.of("src/main/resources/mapping/ScheduledTaskRunDao.xml"));
        assertTrue(xml.contains("'CANCELED'"));
    }

    @Test
    void newRunCreationRecordsInitialStatusMetric() throws Exception {
        String source = Files.readString(Path.of("src/main/java/com/aliyun/autowonder/scheduledtask/ScheduledTaskTriggerService.java"));
        assertTrue(source.contains("metrics.status(persisted.getStatus(), persisted.getSkipReason())"));
    }
}
