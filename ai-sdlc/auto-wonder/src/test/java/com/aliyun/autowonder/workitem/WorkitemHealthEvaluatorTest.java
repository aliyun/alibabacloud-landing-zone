package com.aliyun.autowonder.workitem;

import com.aliyun.autowonder.dispatch.DispatchDO;
import com.aliyun.autowonder.dispatch.DispatchStatus;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class WorkitemHealthEvaluatorTest {

    private static final long NOW = 1_000_000_000L;
    private static final long THRESHOLD = 3_600_000L; // 60m

    private DispatchDO dispatch(String status, long modifiedMs) {
        DispatchDO d = new DispatchDO();
        d.setStatus(status);
        d.setGmtModified(new Date(modifiedMs));
        return d;
    }

    @Test
    void okWhenNoDispatch() {
        WorkitemHealthEvaluator.Result r =
                WorkitemHealthEvaluator.evaluate("IN_PROGRESS", null, NOW, THRESHOLD);
        assertEquals(WorkitemHealthEvaluator.OK, r.health());
        assertNull(r.reason());
    }

    @Test
    void okWhenNotInProgressEvenIfDispatchFailed() {
        DispatchDO d = dispatch(DispatchStatus.FAILED, NOW);
        assertEquals(WorkitemHealthEvaluator.OK,
                WorkitemHealthEvaluator.evaluate("DONE", d, NOW, THRESHOLD).health());
        assertEquals(WorkitemHealthEvaluator.OK,
                WorkitemHealthEvaluator.evaluate("INIT", d, NOW, THRESHOLD).health());
        assertEquals(WorkitemHealthEvaluator.OK,
                WorkitemHealthEvaluator.evaluate(null, d, NOW, THRESHOLD).health());
    }

    @Test
    void stuckWhenInProgressAndLatestDispatchFailed() {
        WorkitemHealthEvaluator.Result r = WorkitemHealthEvaluator.evaluate(
                "IN_PROGRESS", dispatch(DispatchStatus.FAILED, NOW), NOW, THRESHOLD);
        assertEquals(WorkitemHealthEvaluator.STUCK, r.health());
        assertNotNull(r.reason());
    }

    @Test
    void stuckWhenInProgressAndLatestDispatchTimedOut() {
        WorkitemHealthEvaluator.Result r = WorkitemHealthEvaluator.evaluate(
                "IN_PROGRESS", dispatch(DispatchStatus.TIMEOUT, NOW), NOW, THRESHOLD);
        assertEquals(WorkitemHealthEvaluator.STUCK, r.health());
        assertNotNull(r.reason());
    }

    @Test
    void stuckWhenInProgressAndLatestDispatchCanceled() {
        WorkitemHealthEvaluator.Result r = WorkitemHealthEvaluator.evaluate(
                "IN_PROGRESS", dispatch(DispatchStatus.CANCELED, NOW), NOW, THRESHOLD);
        assertEquals(WorkitemHealthEvaluator.STUCK, r.health());
    }

    @Test
    void okWhenRunningDispatchRecentlyProgressed() {
        DispatchDO d = dispatch(DispatchStatus.RUNNING, NOW - THRESHOLD / 2);
        assertEquals(WorkitemHealthEvaluator.OK,
                WorkitemHealthEvaluator.evaluate("IN_PROGRESS", d, NOW, THRESHOLD).health());
    }

    @Test
    void stuckWhenRunningDispatchStalledBeyondThreshold() {
        DispatchDO d = dispatch(DispatchStatus.RUNNING, NOW - THRESHOLD - 60_000L);
        WorkitemHealthEvaluator.Result r =
                WorkitemHealthEvaluator.evaluate("IN_PROGRESS", d, NOW, THRESHOLD);
        assertEquals(WorkitemHealthEvaluator.STUCK, r.health());
        assertNotNull(r.reason());
    }

    @Test
    void okWhenLatestDispatchSucceeded() {
        DispatchDO d = dispatch(DispatchStatus.SUCCEEDED, NOW - THRESHOLD - 60_000L);
        assertEquals(WorkitemHealthEvaluator.OK,
                WorkitemHealthEvaluator.evaluate("IN_PROGRESS", d, NOW, THRESHOLD).health());
    }

    @Test
    void okWhenPendingDispatchStillWithinThreshold() {
        DispatchDO d = dispatch(DispatchStatus.PENDING, NOW - THRESHOLD / 2);
        assertEquals(WorkitemHealthEvaluator.OK,
                WorkitemHealthEvaluator.evaluate("IN_PROGRESS", d, NOW, THRESHOLD).health());
    }
}
