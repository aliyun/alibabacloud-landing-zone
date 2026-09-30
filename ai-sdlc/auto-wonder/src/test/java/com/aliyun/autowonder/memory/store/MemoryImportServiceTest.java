package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.memory.store.dto.MemoryImportManifestRequest;
import com.aliyun.autowonder.memory.store.dto.MemoryImportSnapshotRequest;
import com.aliyun.autowonder.memory.store.dto.MemoryImportCompletionRequest;
import com.aliyun.autowonder.memory.store.dto.MemoryImportClaimRequest;
import com.aliyun.autowonder.mcp.DispatchMcpTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemoryImportServiceTest {
    private MemoryImportDao dao;
    private MemoryStoreBootstrapService stores;
    private MemoryImportService service;
    private DispatchMcpTokenService.DispatchPrincipal principal;

    @BeforeEach
    void setUp() {
        dao = mock(MemoryImportDao.class);
        stores = mock(MemoryStoreBootstrapService.class);
        service = new MemoryImportService(dao, stores);
        principal = new DispatchMcpTokenService.DispatchPrincipal(7L, 9L, 100L, 42L, WorkspaceAccessLevel.READ_WRITE);
        MemoryStoreDO store = new MemoryStoreDO();
        store.setId(3L);
        when(stores.getOrCreate(7L, "AGENT", 42L, "Agent 42 memory", 9L)).thenReturn(store);
        doAnswer(invocation -> { ((MemoryImportSourceDO) invocation.getArgument(0)).setId(11L); return null; })
                .when(dao).upsertSource(any());
    }

    @Test
    void identityDoesNotDependOnExecutorAndDeduplicatesSanitizedHash() {
        String hash = "a".repeat(64);
        var request = new MemoryImportManifestRequest("qoder", "AGENTS.md", "installation-1", hash, 12);
        assertTrue(service.negotiate(principal, request).uploadRequired());
        verify(dao).upsertSource(argThat(source -> source.getAgentId() == 42L
                && source.getInstallationFingerprint().equals("installation-1")
                && source.getLastObservedExecutorId() == null));

        MemoryImportReceiptDO prior = new MemoryImportReceiptDO();
        when(dao.findSuccessfulReceiptByHash(7L, 42L, hash)).thenReturn(prior);
        assertFalse(service.negotiate(principal, request).uploadRequired());
    }

    @Test
    void honorsManagedPauseRetireAndRecurationStates() {
        String hash = "b".repeat(64);
        var request = new MemoryImportManifestRequest("qoder", "MEMORY.md", "installation-1", hash, 12);
        MemoryImportReceiptDO prior = new MemoryImportReceiptDO();
        when(dao.findSuccessfulReceiptByHash(7L, 42L, hash)).thenReturn(prior);
        MemoryImportSourceDO managed = new MemoryImportSourceDO();
        managed.setId(11L);
        managed.setAgentId(42L);
        managed.setStatus("PAUSED");
        when(dao.findSource(7L, 42L, "qoder", "MEMORY.md", "installation-1")).thenReturn(managed);

        var paused = service.negotiate(principal, request);
        assertFalse(paused.uploadRequired());
        assertEquals("SOURCE_PAUSED", paused.reason());

        managed.setStatus("RETIRED");
        var retired = service.negotiate(principal, request);
        assertFalse(retired.uploadRequired());
        assertEquals("SOURCE_RETIRED", retired.reason());

        managed.setStatus("RECURATE_REQUESTED");
        MemoryImportSnapshotDO saved = snapshot(21L, 11L, hash);
        saved.setSanitizedContent("saved body");
        when(dao.listLatestSnapshots(7L, 11L)).thenReturn(java.util.List.of(saved));
        var recurate = service.negotiate(principal, request);
        assertFalse(recurate.uploadRequired(), "re-curation must reuse the saved sanitized snapshot");
        assertTrue(recurate.curationRequired());
        assertEquals("RECURATION_REQUIRED", recurate.reason());
    }

    @Test
    void claimsQueuedSnapshotAtomicallyAndReturnsPreviousForComparison() {
        String hash = "c".repeat(64);
        MemoryImportSourceDO source = new MemoryImportSourceDO();
        source.setId(11L);
        source.setAgentId(42L);
        source.setStatus("OBSERVED");
        when(dao.findSourceById(7L, 11L)).thenReturn(source);
        MemoryImportSnapshotDO current = snapshot(22L, 11L, hash);
        current.setSanitizedContent("current");
        current.setScanSummaryJson("{\"sanitizedLocally\":true,\"redactionCount\":2}");
        MemoryImportSnapshotDO previous = snapshot(21L, 11L, "d".repeat(64));
        previous.setSanitizedContent("previous");
        when(dao.findSnapshotByHash(7L, 11L, hash)).thenReturn(current);
        when(dao.listLatestSnapshots(7L, 11L)).thenReturn(java.util.List.of(current, previous));
        when(dao.claimSnapshot(eq(7L), eq(22L), anyString(), any(), any())).thenReturn(1);

        var claim = service.claim(principal, new MemoryImportClaimRequest(11L, hash));

        assertTrue(claim.acquired());
        assertEquals("current", claim.currentContent());
        assertEquals("previous", claim.previousContent());
        assertEquals(2, claim.redactionCount());
        assertNotNull(claim.leaseId());
        when(dao.claimSnapshot(eq(7L), eq(22L), anyString(), any(), any())).thenReturn(0);
        assertFalse(service.claim(principal, new MemoryImportClaimRequest(11L, hash)).acquired());
    }

    @Test
    void recordsMissingAndOversizedLifecycleWithoutRequestingUpload() {
        var missing = new MemoryImportManifestRequest("qoder", "AGENTS.md", "installation-1", "", 0, "MISSING");
        var missingDecision = service.negotiate(principal, missing);
        assertFalse(missingDecision.uploadRequired());
        assertEquals("SOURCE_MISSING", missingDecision.reason());
        verify(dao).upsertSource(argThat(source -> "MISSING".equals(source.getStatus())
                && source.getLastObservedSanitizedSha256() == null));

        var oversized = new MemoryImportManifestRequest("qodercn", "MEMORY.md", "installation-2", "", 0, "TOO_LARGE");
        var oversizedDecision = service.negotiate(principal, oversized);
        assertFalse(oversizedDecision.uploadRequired());
        assertEquals("SOURCE_TOO_LARGE", oversizedDecision.reason());
    }

    @Test
    void staleImportLeaseCannotCompleteOrReleaseNewOwner() {
        String hash = "e".repeat(64);
        String staleLease = "12345678-1234-1234-1234-123456789abc";
        MemoryImportSourceDO source = new MemoryImportSourceDO();
        source.setId(11L);
        source.setAgentId(42L);
        source.setTargetStoreId(3L);
        source.setStatus("OBSERVED");
        when(dao.findSourceById(7L, 11L)).thenReturn(source);
        when(dao.findSnapshotByHash(7L, 11L, hash)).thenReturn(snapshot(22L, 11L, hash));
        when(dao.completeSnapshot(eq(7L), eq(22L), eq(staleLease), any())).thenReturn(0);

        assertThrows(IllegalArgumentException.class, () -> service.complete(principal,
                new MemoryImportCompletionRequest(11L, hash, 0, staleLease)));
        verify(dao, never()).markSourceImported(anyLong(), anyLong(), anyString());

        service.release(principal, new MemoryImportClaimRequest(11L, hash, staleLease));
        verify(dao).releaseSnapshot(7L, 22L, staleLease);
    }

    @Test
    void acceptsOnlyAuthorizedSanitizedBodyAndKeepsLatestTwo() throws Exception {
        String body = "sanitized legacy memory";
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(body.getBytes(StandardCharsets.UTF_8)));
        MemoryImportSourceDO source = new MemoryImportSourceDO();
        source.setId(11L);
        source.setAgentId(42L);
        source.setTargetStoreId(3L);
        source.setProviderFamily("qoder");
        source.setLogicalPath("AGENTS.md");
        source.setStatus("OBSERVED");
        when(dao.findSourceById(7L, 11L)).thenReturn(source);
        doAnswer(invocation -> { ((MemoryImportSnapshotDO) invocation.getArgument(0)).setId(21L); return null; })
                .when(dao).insertSnapshot(any());

        service.upload(principal, new MemoryImportSnapshotRequest(11L, hash, body,
                body.getBytes(StandardCharsets.UTF_8).length));

        verify(dao).deleteSnapshotsBeforeLatestTwo(7L, 11L);
        verify(dao).markSnapshotStatus(7L, 21L, "QUEUED");
        verify(dao, never()).markSourceImported(anyLong(), anyLong(), anyString());
        verify(dao, never()).upsertReceipt(any());

        when(dao.findSnapshotByHash(7L, 11L, hash)).thenReturn(snapshot(21L, 11L, hash));
        String leaseId = "12345678-1234-1234-1234-123456789abc";
        when(dao.completeSnapshot(eq(7L), eq(21L), eq(leaseId), any())).thenReturn(1);
        service.complete(principal, new MemoryImportCompletionRequest(11L, hash, 0, leaseId));
        verify(dao).markSourceImported(7L, 11L, hash);
        verify(dao).completeSnapshot(eq(7L), eq(21L), eq(leaseId), any());
        verify(dao).upsertReceipt(argThat(receipt -> receipt.getSnapshotId() == 21L
                && "SUCCEEDED".equals(receipt.getOutcome())));

        source.setAgentId(99L);
        assertThrows(IllegalArgumentException.class, () -> service.upload(principal,
                new MemoryImportSnapshotRequest(11L, hash, body, body.length())));
    }

    @Test
    void refusesUploadsForPausedOrRetiredSources() {
        String body = "sanitized legacy memory";
        String hash = sha256(body);
        MemoryImportSourceDO source = new MemoryImportSourceDO();
        source.setId(11L);
        source.setAgentId(42L);
        source.setStatus("PAUSED");
        when(dao.findSourceById(7L, 11L)).thenReturn(source);

        assertThrows(IllegalStateException.class, () -> service.upload(principal,
                new MemoryImportSnapshotRequest(11L, hash, body, body.length())));
        source.setStatus("RETIRED");
        assertThrows(IllegalStateException.class, () -> service.upload(principal,
                new MemoryImportSnapshotRequest(11L, hash, body, body.length())));
        verify(dao, never()).insertSnapshot(any());
    }

    @Test
    void explicitRecurationReprocessesAnAlreadySuccessfulHash() {
        String body = "sanitized legacy memory";
        String hash = sha256(body);
        MemoryImportSourceDO source = new MemoryImportSourceDO();
        source.setId(11L);
        source.setAgentId(42L);
        source.setTargetStoreId(3L);
        source.setProviderFamily("qoder");
        source.setLogicalPath("MEMORY.md");
        source.setStatus("RECURATE_REQUESTED");
        when(dao.findSourceById(7L, 11L)).thenReturn(source);
        when(dao.findSuccessfulReceiptByHash(7L, 42L, hash)).thenReturn(new MemoryImportReceiptDO());
        when(dao.findSnapshotByHash(7L, 11L, hash)).thenReturn(snapshot(21L, 11L, hash));

        service.upload(principal, new MemoryImportSnapshotRequest(11L, hash, body, body.length()));

        verify(dao, never()).insertSnapshot(any());
        verify(dao).markSnapshotStatus(7L, 21L, "QUEUED");
        verify(dao, never()).markSourceImported(anyLong(), anyLong(), anyString());
    }

    private String sha256(String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private MemoryImportSnapshotDO snapshot(long id, long sourceId, String hash) {
        MemoryImportSnapshotDO snapshot = new MemoryImportSnapshotDO();
        snapshot.setId(id);
        snapshot.setTenantId(7L);
        snapshot.setSourceId(sourceId);
        snapshot.setSanitizedContentSha256(hash);
        snapshot.setStatus("QUEUED");
        return snapshot;
    }
}
