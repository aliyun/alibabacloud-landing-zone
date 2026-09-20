package com.aliyun.autowonder.scheduledtask;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduledTaskRunFrozenSnapshotTest {
    private static ScheduledTaskRunDO runWithSnapshot(String snapshotJson) {
        ScheduledTaskRunDO run = new ScheduledTaskRunDO();
        run.setId(501L);
        run.setWorkspaceId(1L);
        run.setExecutionSnapshotJson(snapshotJson);
        return run;
    }

    @Test
    void nullRunContributesNoFrozenAgents() {
        assertTrue(ScheduledTaskRunFrozenSnapshot.frozenAgentIds(null).isEmpty());
        assertFalse(ScheduledTaskRunFrozenSnapshot.isFrozenParticipant(null, 21L));
    }

    @Test
    void collectsDeduplicatedAgentIdsFromSnapshot() {
        ScheduledTaskRunDO run = runWithSnapshot(
                "{\"agentContexts\":[{\"agentId\":21},{\"agentId\":21},{\"agentId\":22}]}");

        Set<Long> frozen = ScheduledTaskRunFrozenSnapshot.frozenAgentIds(run);

        assertEquals(Set.of(21L, 22L), frozen);
        assertTrue(ScheduledTaskRunFrozenSnapshot.isFrozenParticipant(run, 21L));
        assertFalse(ScheduledTaskRunFrozenSnapshot.isFrozenParticipant(run, 99L));
    }

    @Test
    void toleratesMissingSnapshotMissingContextsAndNullAgentIds() {
        assertTrue(ScheduledTaskRunFrozenSnapshot.frozenAgentIds(runWithSnapshot(null)).isEmpty());
        assertTrue(ScheduledTaskRunFrozenSnapshot.frozenAgentIds(runWithSnapshot("{\"other\":1}")).isEmpty());
        assertEquals(Set.of(23L), ScheduledTaskRunFrozenSnapshot.frozenAgentIds(
                runWithSnapshot("{\"agentContexts\":[null,{\"agentId\":null},{\"agentId\":23}]}")));
    }

    @Test
    void malformedSnapshotDegradesToEmptyInsteadOfFailing() {
        assertTrue(ScheduledTaskRunFrozenSnapshot.frozenAgentIds(runWithSnapshot("{not-json")).isEmpty());
        assertTrue(ScheduledTaskRunFrozenSnapshot.frozenAgentIds(runWithSnapshot("[1,2,3]")).isEmpty());
    }
}
