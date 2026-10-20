package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.memory.store.dto.MemoryMutationRequest;
import com.aliyun.autowonder.memory.store.dto.MemoryMaintenanceLeaseRequest;
import com.aliyun.autowonder.memory.store.dto.MemoryMaintenanceLeaseVO;
import com.aliyun.autowonder.memory.store.dto.MemorySnapshotVO;
import com.aliyun.autowonder.mcp.DispatchMcpTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.*;

class DaemonMemoryControllerTest {
    private DispatchMcpTokenService tokens;
    private MemoryStoreApplicationService memories;
    private DaemonMemoryController controller;
    private DispatchMcpTokenService.DispatchPrincipal principal;

    @BeforeEach
    void setUp() {
        tokens = mock(DispatchMcpTokenService.class);
        memories = mock(MemoryStoreApplicationService.class);
        controller = new DaemonMemoryController(tokens, memories, mock(MemoryImportService.class));
        principal = new DispatchMcpTokenService.DispatchPrincipal(
                7L, 9L, 99L, 42L, WorkspaceAccessLevel.READ_WRITE);
        when(tokens.authenticateDispatch("awdispatch_token")).thenReturn(principal);
        when(tokens.authenticateMemoryDispatch("awdispatch_token")).thenReturn(principal);
    }

    @Test
    void returnsOnlyDispatchAgentsAuthorizedSnapshot() {
        MemorySnapshotVO snapshot = new MemorySnapshotVO("index", List.of());
        when(memories.snapshotForAgent(principal)).thenReturn(snapshot);

        ResponseEntity<?> response = controller.snapshot(99L, "Bearer awdispatch_token");

        assertEquals(200, response.getStatusCode().value());
        assertSame(snapshot, response.getBody());
        verify(memories).snapshotForAgent(principal);
    }

    @Test
    void rejectsTokenIssuedForAnotherDispatch() {
        ResponseEntity<?> response = controller.snapshot(100L, "Bearer awdispatch_token");

        assertEquals(401, response.getStatusCode().value());
        verifyNoInteractions(memories);
    }

    @Test
    void mapsOptimisticMutationConflictToHttp409() {
        MemoryMutationRequest request = MemoryMutationRequest.update("feedback.md", "body", 3, "request-1");
        when(memories.mutateForAgent(principal, 11L, request)).thenThrow(
                new MemoryDocumentService.MemoryConflictException("changed"));

        ResponseEntity<?> response = controller.mutate(99L, 11L, "Bearer awdispatch_token", request);

        assertEquals(409, response.getStatusCode().value());
        verify(tokens).authenticateDispatch("awdispatch_token");
    }

    @Test
    void mapsTemporaryMaintenanceOwnershipToRetryableHttp423() {
        MemoryMutationRequest request = MemoryMutationRequest.update("feedback.md", "body", 3, "request-1");
        when(memories.mutateForAgent(principal, 11L, request)).thenThrow(
                new MemoryStoreApplicationService.MemoryMaintenanceLeaseConflictException());

        ResponseEntity<?> response = controller.mutate(99L, 11L, "Bearer awdispatch_token", request);

        assertEquals(423, response.getStatusCode().value());
    }

    @Test
    void terminalDispatchCannotStartAnUnleasedMutation() {
        when(tokens.authenticateDispatch("awdispatch_token")).thenReturn(null);
        MemoryMutationRequest request = MemoryMutationRequest.update("feedback.md", "body", 3, "request-1");

        ResponseEntity<?> response = controller.mutate(99L, 11L, "Bearer awdispatch_token", request);

        assertEquals(401, response.getStatusCode().value());
        verify(tokens).authenticateMemoryDispatch("awdispatch_token");
        verifyNoInteractions(memories);
    }

    @Test
    void terminalDispatchCanOnlyUseLineageCheckedMaintenanceReclaim() {
        when(tokens.authenticateDispatch("awdispatch_token")).thenReturn(null);
        MemoryMaintenanceLeaseVO lease = new MemoryMaintenanceLeaseVO(true,
                "12345678-1234-1234-1234-123456789abc");
        when(memories.reclaimMaintenance(principal, 11L)).thenReturn(lease);

        ResponseEntity<?> response = controller.claimMaintenance(99L, "Bearer awdispatch_token",
                new MemoryMaintenanceLeaseRequest(11L));

        assertEquals(200, response.getStatusCode().value());
        assertSame(lease, response.getBody());
        verify(memories, never()).claimMaintenance(any(), anyLong());
        verify(memories).reclaimMaintenance(principal, 11L);
    }

    @Test
    void recallIsAcceptedOnlyAfterTheServerValidatesVisibleDocument() {
        ResponseEntity<?> response = controller.recall(99L, "Bearer awdispatch_token",
                Map.of("storeId", 11, "path", "feedback.md"));

        assertEquals(202, response.getStatusCode().value());
        verify(memories).validateRecall(principal, 11L, "feedback.md");
    }
    @Test void daemonSuccessPreservesTheEntireBaselineJsonIncludingExistingNullFields() throws Exception {
        var document = new MemoryDocumentDO(); document.setPath("raw.md");
        document.setContentMd("<b>原始内容</b>"); document.setVersion(7);
        var request = MemoryMutationRequest.create("raw.md", "<b>原始内容</b>", "key");
        when(memories.mutateForAgent(principal, 11L, request)).thenReturn(document);
        var response = controller.mutate(99L, 11L, "Bearer awdispatch_token", request);
        assertEquals(200, response.getStatusCode().value()); assertSame(document, response.getBody());
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var expected = json.readTree("{\"id\":null,\"tenantId\":null,\"storeId\":null,\"path\":\"raw.md\",\"contentMd\":\"<b>原始内容</b>\",\"contentSha256\":null,\"byteSize\":null,\"modifiedAt\":null,\"deletedAt\":null,\"creatorType\":null,\"creatorRef\":null,\"sourceDispatchId\":null,\"sourceSessionId\":null,\"creatorId\":null,\"modifierId\":null,\"version\":7,\"gmtCreate\":null,\"gmtModified\":null,\"title\":null,\"memoryType\":null,\"description\":null,\"body\":null}");
        assertEquals(expected, json.valueToTree(response.getBody()));
    }

    @Test void sharedKeyedValidationStillProducesRawDaemon400AndFixedRetryTokens() {
        var request = MemoryMutationRequest.create("../raw.md", "body", "key");
        IllegalArgumentException error = org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> MemoryPathValidator.requireSafe(request.path()));
        when(memories.mutateForAgent(principal, 11L, request)).thenThrow(error);
        try {
            org.springframework.context.i18n.LocaleContextHolder.setLocale(java.util.Locale.US);
            var response = controller.mutate(99L, 11L, "Bearer awdispatch_token", request);
            assertEquals(400, response.getStatusCode().value());
            assertEquals(Map.of("error", "unsafe memory path: empty or traversal segments are not allowed"), response.getBody());
            doThrow(new MemoryDocumentService.MemoryConflictException("<b>raw conflict</b>")).when(memories).mutateForAgent(principal, 11L, request);
            response = controller.mutate(99L, 11L, "Bearer awdispatch_token", request);
            assertEquals(409, response.getStatusCode().value()); assertEquals(Map.of("error", "MEMORY_VERSION_CONFLICT"), response.getBody());
            doThrow(new MemoryStoreApplicationService.MemoryMaintenanceLeaseConflictException()).when(memories).mutateForAgent(principal, 11L, request);
            response = controller.mutate(99L, 11L, "Bearer awdispatch_token", request);
            assertEquals(423, response.getStatusCode().value()); assertEquals(Map.of("error", "MEMORY_MAINTENANCE_LOCKED"), response.getBody());
        } finally { org.springframework.context.i18n.LocaleContextHolder.resetLocaleContext(); }
    }

}
