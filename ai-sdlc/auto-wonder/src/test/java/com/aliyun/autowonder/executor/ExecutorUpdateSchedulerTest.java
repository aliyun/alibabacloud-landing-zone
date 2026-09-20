package com.aliyun.autowonder.executor;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ExecutorUpdateSchedulerTest {

    @Test
    void everyTickHandsTheWholeSweepToTheUpdateService() {
        ExecutorUpdateService service = mock(ExecutorUpdateService.class);

        new ExecutorUpdateScheduler(service).scanUpgrades();

        verify(service).scanAndDeliver();
    }

    /**
     * The sweep runs on every node, so its cadence is a platform property rather than an implementation
     * detail: it decides how long a silent attempt waits before it is failed, and how often an outdated
     * executor is picked up without a heartbeat. A fixed delay also waits for the previous sweep to
     * finish, which {@code fixedRate} would not.
     */
    @Test
    void theSweepRunsOnAConfigurableFixedDelayOfOneMinuteByDefault() throws Exception {
        Method scan = ExecutorUpdateScheduler.class.getMethod("scanUpgrades");

        Scheduled scheduled = scan.getAnnotation(Scheduled.class);

        assertEquals("${autowonder.executor-update.scan-fixed-delay-ms:60000}", scheduled.fixedDelayString());
    }
}
