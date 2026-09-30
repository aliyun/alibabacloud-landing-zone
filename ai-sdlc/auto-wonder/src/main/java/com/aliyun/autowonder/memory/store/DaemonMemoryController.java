package com.aliyun.autowonder.memory.store;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.memory.store.dto.MemoryMutationRequest;
import com.aliyun.autowonder.memory.store.dto.MemoryImportManifestRequest;
import com.aliyun.autowonder.memory.store.dto.MemoryImportSnapshotRequest;
import com.aliyun.autowonder.memory.store.dto.MemoryImportCompletionRequest;
import com.aliyun.autowonder.memory.store.dto.MemoryImportClaimRequest;
import com.aliyun.autowonder.memory.store.dto.MemoryMaintenanceLeaseRequest;
import com.aliyun.autowonder.mcp.DispatchMcpTokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/daemon/dispatches/{dispatchId}/memory")
public class DaemonMemoryController {
    private static final Logger log = LoggerFactory.getLogger(DaemonMemoryController.class);
    private final DispatchMcpTokenService tokenService;
    private final MemoryStoreApplicationService memories;
    private final MemoryImportService imports;

    public DaemonMemoryController(DispatchMcpTokenService tokenService, MemoryStoreApplicationService memories,
                                  MemoryImportService imports) {
        this.tokenService = tokenService;
        this.memories = memories;
        this.imports = imports;
    }

    @PostMapping("/imports/manifest")
    public ResponseEntity<?> importManifest(@PathVariable long dispatchId,
                                            @RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody MemoryImportManifestRequest request) {
        DispatchMcpTokenService.DispatchPrincipal principal = authenticateActive(dispatchId, authorization);
        if (principal == null) return ResponseEntity.status(401).build();
        try {
            return ResponseEntity.ok(imports.negotiate(principal, request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/imports/snapshots")
    public ResponseEntity<?> importSnapshot(@PathVariable long dispatchId,
                                            @RequestHeader(value = "Authorization", required = false) String authorization,
                                            @RequestBody MemoryImportSnapshotRequest request) {
        DispatchMcpTokenService.DispatchPrincipal principal = authenticateActive(dispatchId, authorization);
        if (principal == null) return ResponseEntity.status(401).build();
        try {
            imports.upload(principal, request);
            return ResponseEntity.accepted().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/imports/completions")
    public ResponseEntity<?> importCompletion(@PathVariable long dispatchId,
                                              @RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestBody MemoryImportCompletionRequest request) {
        DispatchMcpTokenService.DispatchPrincipal principal = authenticateContinuation(dispatchId, authorization);
        if (principal == null) return ResponseEntity.status(401).build();
        try {
            imports.complete(principal, request);
            return ResponseEntity.accepted().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/imports/claims")
    public ResponseEntity<?> importClaim(@PathVariable long dispatchId,
                                         @RequestHeader(value = "Authorization", required = false) String authorization,
                                         @RequestBody MemoryImportClaimRequest request) {
        DispatchMcpTokenService.DispatchPrincipal principal = authenticateActive(dispatchId, authorization);
        if (principal == null) return ResponseEntity.status(401).build();
        try {
            return ResponseEntity.ok(imports.claim(principal, request));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/imports/releases")
    public ResponseEntity<?> importRelease(@PathVariable long dispatchId,
                                           @RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody MemoryImportClaimRequest request) {
        DispatchMcpTokenService.DispatchPrincipal principal = authenticateContinuation(dispatchId, authorization);
        if (principal == null) return ResponseEntity.status(401).build();
        try {
            imports.release(principal, request);
            return ResponseEntity.accepted().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/imports/renewals")
    public ResponseEntity<?> importRenewal(@PathVariable long dispatchId,
                                           @RequestHeader(value = "Authorization", required = false) String authorization,
                                           @RequestBody MemoryImportClaimRequest request) {
        DispatchMcpTokenService.DispatchPrincipal principal = authenticateContinuation(dispatchId, authorization);
        if (principal == null) return ResponseEntity.status(401).build();
        try {
            return imports.renew(principal, request)
                    ? ResponseEntity.accepted().build() : ResponseEntity.status(409).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/snapshot")
    public ResponseEntity<?> snapshot(@PathVariable long dispatchId,
                                      @RequestHeader(value = "Authorization", required = false) String authorization) {
        DispatchMcpTokenService.DispatchPrincipal principal = authenticateActive(dispatchId, authorization);
        if (principal == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(memories.snapshotForAgent(principal));
    }

    @PostMapping("/maintenance/claims")
    public ResponseEntity<?> claimMaintenance(@PathVariable long dispatchId,
                                              @RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestBody MemoryMaintenanceLeaseRequest request) {
        DispatchMcpTokenService.DispatchPrincipal principal = authenticateActive(dispatchId, authorization);
        try {
            if (principal != null) {
                return ResponseEntity.ok(memories.claimMaintenance(principal, request.storeId()));
            }
            principal = authenticateContinuation(dispatchId, authorization);
            return principal == null ? ResponseEntity.status(401).build()
                    : ResponseEntity.ok(memories.reclaimMaintenance(principal, request.storeId()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/maintenance/renewals")
    public ResponseEntity<?> renewMaintenance(@PathVariable long dispatchId,
                                              @RequestHeader(value = "Authorization", required = false) String authorization,
                                              @RequestBody MemoryMaintenanceLeaseRequest request) {
        DispatchMcpTokenService.DispatchPrincipal principal = authenticateContinuation(dispatchId, authorization);
        if (principal == null) return ResponseEntity.status(401).build();
        return memories.renewMaintenance(principal, request.storeId(), request.leaseId())
                ? ResponseEntity.accepted().build() : ResponseEntity.status(409).build();
    }

    @PostMapping("/maintenance/releases")
    public ResponseEntity<?> releaseMaintenance(@PathVariable long dispatchId,
                                                @RequestHeader(value = "Authorization", required = false) String authorization,
                                                @RequestBody MemoryMaintenanceLeaseRequest request) {
        DispatchMcpTokenService.DispatchPrincipal principal = authenticateContinuation(dispatchId, authorization);
        if (principal == null) return ResponseEntity.status(401).build();
        memories.releaseMaintenance(principal, request.storeId(), request.leaseId());
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/changes")
    public ResponseEntity<?> changes(@PathVariable long dispatchId, @RequestParam long storeId,
                                     @RequestParam(defaultValue = "0") long afterRevision,
                                     @RequestParam(defaultValue = "100") int limit,
                                     @RequestHeader(value = "Authorization", required = false) String authorization) {
        DispatchMcpTokenService.DispatchPrincipal principal = authenticateActive(dispatchId, authorization);
        if (principal == null) return ResponseEntity.status(401).build();
        try {
            return ResponseEntity.ok(memories.changesForAgent(principal, storeId, afterRevision, limit));
        } catch (MemoryStoreApplicationService.MemoryAccessDeniedException e) {
            return ResponseEntity.status(403).build();
        }
    }

    @PostMapping("/mutations")
    public ResponseEntity<?> mutate(@PathVariable long dispatchId, @RequestParam long storeId,
                                    @RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestBody MemoryMutationRequest request) {
        DispatchMcpTokenService.DispatchPrincipal principal = authenticateActive(dispatchId, authorization);
        if (principal == null) {
            principal = authenticateContinuation(dispatchId, authorization);
            if (principal == null || request == null || request.maintenanceLeaseId() == null
                    || request.maintenanceLeaseId().isBlank()) {
                return ResponseEntity.status(401).build();
            }
        }
        if (principal == null) return ResponseEntity.status(401).build();
        try {
            MemoryDocumentDO document = memories.mutateForAgent(principal, storeId, request);
            log.info("memory mutation accepted dispatchId={} agentId={} storeId={} operation={} path={} version={}",
                    dispatchId, principal.agentId(), storeId, request.operation(), request.path(), document.getVersion());
            return ResponseEntity.ok(document);
        } catch (MemoryStoreApplicationService.MemoryMaintenanceLeaseConflictException e) {
            return ResponseEntity.status(423).body(Map.of("error", "MEMORY_MAINTENANCE_LOCKED"));
        } catch (MemoryDocumentService.MemoryConflictException e) {
            return ResponseEntity.status(409).body(Map.of("error", "MEMORY_VERSION_CONFLICT"));
        } catch (MemoryStoreApplicationService.MemoryAccessDeniedException e) {
            return ResponseEntity.status(403).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/recalls")
    public ResponseEntity<?> recall(@PathVariable long dispatchId,
                                    @RequestHeader(value = "Authorization", required = false) String authorization,
                                    @RequestBody Map<String, Object> request) {
        DispatchMcpTokenService.DispatchPrincipal principal = authenticateActive(dispatchId, authorization);
        if (principal == null) return ResponseEntity.status(401).build();
        Object storeId = request == null ? null : request.get("storeId");
        Object path = request == null ? null : request.get("path");
        if (!(storeId instanceof Number) || !(path instanceof String)) {
            return ResponseEntity.badRequest().body(Map.of("error", "storeId and path are required"));
        }
        try {
            memories.validateRecall(principal, ((Number) storeId).longValue(), (String) path);
        } catch (MemoryStoreApplicationService.MemoryAccessDeniedException e) {
            return ResponseEntity.status(403).build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
        log.info("memory topic recalled dispatchId={} agentId={} storeId={} path={}",
                dispatchId, principal.agentId(), storeId, path);
        return ResponseEntity.accepted().build();
    }

    private DispatchMcpTokenService.DispatchPrincipal authenticateActive(long dispatchId, String authorization) {
        return authenticate(dispatchId, authorization, false);
    }

    private DispatchMcpTokenService.DispatchPrincipal authenticateContinuation(long dispatchId, String authorization) {
        return authenticate(dispatchId, authorization, true);
    }

    private DispatchMcpTokenService.DispatchPrincipal authenticate(long dispatchId, String authorization,
                                                                    boolean continuation) {
        try {
            if (authorization == null || !authorization.startsWith("Bearer ")) return null;
            String token = authorization.substring("Bearer ".length()).trim();
            DispatchMcpTokenService.DispatchPrincipal principal = continuation
                    ? tokenService.authenticateMemoryDispatch(token) : tokenService.authenticateDispatch(token);
            return principal != null && principal.dispatchId() == dispatchId && principal.agentId() != null
                    ? principal : null;
        } catch (BizException | IllegalArgumentException e) {
            return null;
        }
    }
}
