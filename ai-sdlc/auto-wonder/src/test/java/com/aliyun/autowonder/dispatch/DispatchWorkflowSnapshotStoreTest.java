package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.taskpackage.PackageContext;
import com.aliyun.autowonder.taskpackage.TaskPackager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DispatchWorkflowSnapshotStoreTest {
    WorkflowSnapshotFixture f;
    @BeforeEach void setup() throws Exception { f = new WorkflowSnapshotFixture(); }

    @Test void retriesKeepOriginalWorkflowVersionAndRoundAfterDefinitionsChange() {
        DispatchDO source = f.dispatch(100L, "PAUSED");
        var original = f.freeze(source);
        f.step.setInstructionMd("changed after pause");
        f.step.setSdlcId(99L);
        DispatchDO retry = f.dispatch(101L, "PENDING");
        retry.setResumeMode("RECOVERY"); retry.setResumeFromDispatchId(100L); retry.setIdempotencyKey("continue:100");

        var resumed = f.freeze(retry);

        assertEquals(original.document(), resumed.document());
        assertEquals(original.sha256(), resumed.sha256());
        assertEquals(100L, resumed.roundId());
        assertEquals(100L, resumed.sourceDispatchId());
        assertEquals(70L, resumed.sdlcId());
        assertEquals(21L, retry.getAgentVersionId());
        assertTrue(resumed.document().contains("old instruction"));
    }

    @Test void importsLegacyPackageInsteadOfUsingTheCurrentWorkflow() {
        DispatchDO source = f.dispatch(100L, "PAUSED");
        source.setAgentVersionId(21L); source.setExecutorId(50L);
        var ctx = new PackageContext(); ctx.setTenantId(1L); ctx.setWorkitemId(10L); ctx.setDispatchId(100L);
        ctx.setAgentId(20L); ctx.setAgentVersionId(21L); ctx.setSdlcId(70L); ctx.setSdlcStepId(77L);
        ctx.setSdlc(Map.of("sdlcId", "70", "currentStepId", "77", "steps", java.util.List.of(Map.of("id", "77", "instruction", "original package"))));
        var result = new TaskPackager(f.storage, "test", "https://test.example").build(ctx);
        source.setPackageOssRef(result.getOssRef());
        f.step.setInstructionMd("new live definition");

        var imported = f.snapshots.require(source);

        assertTrue(imported.document().contains("original package"));
        assertFalse(imported.document().contains("new live definition"));
        assertEquals(21L, imported.agentVersionId());
    }

    @Test void missingHistoricalSnapshotDoesNotSilentlyUseCurrentDefaults() {
        DispatchDO source = f.dispatch(100L, "PAUSED"); source.setAgentVersionId(21L); source.setExecutorId(50L);
        BizException failure = assertThrows(BizException.class, () -> f.snapshots.require(source));
        assertEquals("dispatch.snapshot.unavailable", failure.getMessageKey());
        assertEquals("PAUSED", source.getStatus());
        assertNull(f.snapshots.find(1, 100));
    }

    @Test void restartIntentFreezesAtRequestAndFencesTheOriginalRound() {
        DispatchDO source = f.dispatch(100L, "PAUSED"); f.freeze(source);
        f.jdbc.update("INSERT INTO workitem_delivery_restart VALUES (1,10,1,'latest-workflow:100','STARTING')");
        f.snapshots.freezeRestart(1, 10, 1, 77L, 20L);
        f.step.setInstructionMd("updated while stopping old execution");
        DispatchDO restarted = f.dispatch(101L, "PENDING"); restarted.setIdempotencyKey("restart:1:10");

        var snapshot = f.freeze(restarted);

        assertTrue(snapshot.document().contains("old instruction"));
        assertFalse(snapshot.document().contains("updated while"));
        assertEquals(101L, snapshot.roundId());
        assertEquals(100L, snapshot.sourceDispatchId());
        assertNull(restarted.getResumeFromDispatchId());
        assertTrue(f.snapshots.fenced(source));
        assertFalse(f.snapshots.fenced(restarted));
    }

    @Test void supersededRestartCannotStopOrStartANewerRound() {
        f.dispatch(100L, "PAUSED");
        f.jdbc.update("INSERT INTO workitem_delivery_restart VALUES (1,10,1,'first','STARTING')");
        f.snapshots.freezeRestart(1, 10, 1, 77L, 20L);
        f.jdbc.update("UPDATE workitem_delivery_restart SET status='SUPERSEDED'");
        f.jdbc.update("INSERT INTO workitem_delivery_restart VALUES (1,10,2,'second','STARTING')");
        DispatchDO oldRequest = f.dispatch(101L, "PENDING"); oldRequest.setIdempotencyKey("restart:1:10");
        assertThrows(BizException.class, () -> f.snapshots.prepare(oldRequest));
        assertNull(f.snapshots.find(1, 101));
    }

    @Test void tenantCannotBorrowAnotherWorkitemsSnapshot() {
        f.freeze(f.dispatch(100L, "PAUSED"));
        assertNull(f.snapshots.find(2, 100));
        DispatchDO retry = f.dispatch(101L, "PENDING"); retry.setWorkitemId(11L);
        retry.setIdempotencyKey("continue:100"); retry.setResumeFromDispatchId(100L);
        assertThrows(BizException.class, () -> f.snapshots.prepare(retry));
    }

    @Test void workflowHashCoversTheCompleteUnmodifiedDocument() {
        var snapshot = f.freeze(f.dispatch(100L, "PENDING"));
        assertEquals(DispatchWorkflowSnapshotStore.digest(snapshot.document()), snapshot.sha256());
        assertEquals("77", JSON.parseObject(snapshot.document()).getString("currentStepId"));
    }

    @Test void newInteractionsDoNotReuseSessionsFromADifferentWorkflowSnapshot() {
        f.freeze(f.dispatch(100L, "SUCCEEDED"));
        f.step.setInstructionMd("changed workflow contract");
        DispatchDO interaction = f.dispatch(101L, "PENDING");
        interaction.setResumeMode("CANONICAL_INTERACTION"); interaction.setResumeFromDispatchId(100L);
        interaction.setIdempotencyKey("guidance:123");
        var snapshot = f.freeze(interaction);
        assertNull(interaction.getResumeFromDispatchId());
        assertEquals(100L, snapshot.sourceDispatchId());
        assertEquals(101L, snapshot.roundId());
    }

    @Test void malformedLegacyChecklistAndGatePolicyKeepTheExistingEmptyFallback() {
        long dispatchId = 100L;
        for (String json : java.util.List.of("{legacy-invalid", "null", "1")) {
            f.step.setChecklistJson(json);
            f.step.setGatePolicyJson(json);
            var snapshot = f.freeze(f.dispatch(dispatchId++, "PENDING"));
            var step = JSON.parseObject(snapshot.document()).getJSONArray("steps").getJSONObject(0);
            assertTrue(step.getJSONArray("checklist").isEmpty());
            assertTrue(step.getJSONObject("gatePolicy").isEmpty());
        }
    }
}
