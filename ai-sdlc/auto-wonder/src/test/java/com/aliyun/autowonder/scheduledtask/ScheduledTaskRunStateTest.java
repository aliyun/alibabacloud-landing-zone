package com.aliyun.autowonder.scheduledtask;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledTaskRunStateTest {

    @Test
    void terminalRunStatesAreImmutable() {
        assertTrue(ScheduledTaskRunStatus.SUCCEEDED.isTerminal());
        assertTrue(ScheduledTaskRunStatus.SKIPPED.isTerminal());
        assertFalse(ScheduledTaskRunStatus.WAITING_EXECUTOR.isTerminal());
    }

    @Test
    void onlyCompletedRunStatesAreTerminal() {
        EnumSet<ScheduledTaskRunStatus> terminal = EnumSet.of(
                ScheduledTaskRunStatus.SUCCEEDED,
                ScheduledTaskRunStatus.FAILED,
                ScheduledTaskRunStatus.TIMED_OUT,
                ScheduledTaskRunStatus.CANCELED,
                ScheduledTaskRunStatus.SKIPPED);

        for (ScheduledTaskRunStatus status : ScheduledTaskRunStatus.values()) {
            assertTrue(status.isTerminal() == terminal.contains(status), status.name());
        }
    }

    @Test
    void taskLifecyclePredicatesDistinguishSchedulableAndRetiredStates() {
        assertTrue(ScheduledTaskStatus.ACTIVE.isSchedulable());
        assertFalse(ScheduledTaskStatus.PAUSED.isSchedulable());
        assertTrue(ScheduledTaskStatus.EXHAUSTED.isRetired());
        assertTrue(ScheduledTaskStatus.ARCHIVED.isRetired());
        assertFalse(ScheduledTaskStatus.PAUSED.isRetired());
    }
}
