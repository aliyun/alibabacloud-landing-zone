package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.audit.AuditLogService;
import com.aliyun.autowonder.common.error.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DeliveryExternalOperationServiceTest {
    WorkflowSnapshotFixture f;
    DispatchRecoveryService recovery;
    DeliveryExternalOperationService service;
    AuditLogService audit;
    DispatchDO first;
    static final String HASH = "a".repeat(64);

    @BeforeEach void setup() throws Exception {
        f = new WorkflowSnapshotFixture();
        first = f.dispatch(100L, "RUNNING"); f.freeze(first);
        recovery = mock(DispatchRecoveryService.class);
        doAnswer(call -> {
            DispatchDO d = call.getArgument(0);
            f.jdbc.queryForObject("SELECT workitem_id FROM test_control WHERE tenant_id=? AND workitem_id=? FOR UPDATE",
                    Long.class, d.getTenantId(), d.getWorkitemId()); return null;
        }).when(recovery).lockWorkitemControl(any());
        audit = mock(AuditLogService.class);
        service = new DeliveryExternalOperationService(f.jdbc, f.dispatches, recovery, f.snapshots, audit);
    }

    Map<String, Object> claim(DispatchDO owner, String key, String kind, String hash) {
        return f.tx.execute(tx -> service.claim(owner, key, kind, hash));
    }

    @ParameterizedTest @ValueSource(strings = {"PR_CREATE", "COMMENT_CREATE", "CLOUD_MUTATION"})
    void successfulBusinessOperationIsReusedAcrossDeliveryRounds(String kind) {
        String key = kind + ":repo/branch/intent";
        Map<String, Object> original = claim(first, key, kind, HASH);
        assertEquals(true, original.get("mayExecute"));
        f.tx.executeWithoutResult(tx -> service.complete(first, key, (String) original.get("claimToken"), "SUCCEEDED", "resource:123"));
        first.setStatus("CANCELED");
        DispatchDO next = f.dispatch(101L, "RUNNING"); f.freeze(next);

        Map<String, Object> reused = claim(next, key, kind, HASH);

        assertEquals(false, reused.get("mayExecute"));
        assertEquals("SUCCEEDED", reused.get("status"));
        assertEquals("resource:123", reused.get("receiptRef"));
        assertEquals(1, service.list(1, 10).size());
        assertFalse(service.list(1, 10).get(0).containsKey("claimToken"));
        assertTrue(service.list(2, 10).isEmpty());
    }

    @Test void timeoutAndLostClaimResponsesNeverAllowBlindReplay() {
        var original = claim(first, "cloud:request-1", "CLOUD_MUTATION", HASH);
        assertEquals(false, claim(first, "cloud:request-1", "CLOUD_MUTATION", HASH).get("mayExecute"));
        f.tx.executeWithoutResult(tx -> service.complete(first, "cloud:request-1", (String) original.get("claimToken"), "UNKNOWN", null));
        first.setStatus("CANCELED");
        DispatchDO next = f.dispatch(101L, "RUNNING"); f.freeze(next);
        assertEquals(false, claim(next, "cloud:request-1", "CLOUD_MUTATION", HASH).get("mayExecute"));
        f.tx.executeWithoutResult(tx -> service.reconcile(next, "cloud:request-1", 1, "SUCCEEDED", "cloud:request-result-1"));
        assertEquals("SUCCEEDED", claim(next, "cloud:request-1", "CLOUD_MUTATION", HASH).get("status"));
        assertThrows(BizException.class, () -> f.tx.executeWithoutResult(tx ->
                service.reconcile(next, "cloud:request-1", 1, "NOT_FOUND", "stale-absence")));
    }

    @Test void confirmedAbsenceAllowsExactlyOneNewClaimAndRetainsAuditEvidence() {
        claim(first, "pr:repo/branch", "PR_CREATE", HASH);
        first.setStatus("CANCELED");
        DispatchDO next = f.dispatch(101L, "RUNNING"); f.freeze(next);
        f.tx.executeWithoutResult(tx -> service.reconcile(next, "pr:repo/branch", 0, "NOT_FOUND", "repo:authoritative-query-1"));
        assertEquals(true, claim(next, "pr:repo/branch", "PR_CREATE", HASH).get("mayExecute"));
        assertEquals(false, claim(next, "pr:repo/branch", "PR_CREATE", HASH).get("mayExecute"));
        verify(audit, times(3)).recordRequired(any());
        assertEquals("repo:authoritative-query-1", service.list(1, 10).get(0).get("readbackRef"));
    }

    @Test void staleOrStillRunningOwnersCannotBeReconciledOrReplayAnotherPayload() {
        var claim = claim(first, "comment:acceptance", "COMMENT_CREATE", HASH);
        assertThrows(BizException.class, () -> claim(first, "comment:acceptance", "COMMENT_CREATE", "b".repeat(64)));
        DispatchDO next = f.dispatch(101L, "RUNNING"); f.freeze(next);
        assertThrows(BizException.class, () -> f.tx.executeWithoutResult(tx -> service.reconcile(next,
                "comment:acceptance", 0, "NOT_FOUND", "external-query")));
        assertThrows(BizException.class, () -> f.tx.executeWithoutResult(tx -> service.complete(next,
                "comment:acceptance", (String) claim.get("claimToken"), "SUCCEEDED", "comment:1")));
        first.setStatus("CANCELED");
        assertThrows(BizException.class, () -> claim(first, "comment:second", "COMMENT_CREATE", HASH));
        assertEquals(1, service.list(1, 10).size());
    }

    @Test void concurrentDuplicateClicksHaveOnlyOneExecutionPermit() throws Exception {
        try (var pool = Executors.newFixedThreadPool(2)) {
            var tasks = List.<java.util.concurrent.Callable<Map<String, Object>>>of(
                    () -> claim(first, "cloud:concurrent", "CLOUD_MUTATION", HASH),
                    () -> claim(first, "cloud:concurrent", "CLOUD_MUTATION", HASH));
            int permitted = 0;
            for (var result : pool.invokeAll(tasks)) if (Boolean.TRUE.equals(result.get().get("mayExecute"))) permitted++;
            assertEquals(1, permitted);
        }
    }

    @Test void receiptAndRequiredAuditCommitOrRollbackTogether() {
        doThrow(new IllegalStateException("audit unavailable")).when(audit).recordRequired(any());
        assertThrows(IllegalStateException.class, () -> claim(first, "pr:audit", "PR_CREATE", HASH));
        assertTrue(service.list(1, 10).isEmpty());
    }
}
