package com.aliyun.autowonder.prd;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.prd.dto.ConfirmVersionRequest;
import com.aliyun.autowonder.prd.dto.PrdDesignVO;
import com.aliyun.autowonder.prd.dto.PrdDesignVersionVO;
import com.aliyun.autowonder.prd.dto.SaveDesignRequest;
import com.aliyun.autowonder.prd.dto.SelectTargetRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * PRD design endpoints, scoped to a workitem because the workitem is the carrier of requirement,
 * design and delivery.
 *
 * <p>Reads need READ_ONLY; every authoring, target-binding and confirming action needs READ_WRITE
 * and runs through the PRD functional domain, so a member whose PRD domain is read-only is refused
 * by {@code WorkspaceAccessAspect} before reaching the service. Space isolation is enforced twice:
 * the aspect for membership/level, and the service re-checking workitem ownership.
 *
 * <p>HTML is served as a sandboxed document, never inlined into the host page: the response carries
 * a strict CSP and {@code X-Frame-Options: ALLOWALL} is deliberately absent so the preview iframe's
 * own {@code sandbox} attribute (without allow-same-origin) is what governs isolation.
 */
@RestController
@RequestMapping("/api/workitems")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY,
        action = "查看 PRD 设计", actionKey = "access.action.view_prd_design")
public class PrdDesignController {

    private final PrdDesignService designService;
    private final PrdAcceptanceService acceptanceService;

    public PrdDesignController(PrdDesignService designService, PrdAcceptanceService acceptanceService) {
        this.designService = designService;
        this.acceptanceService = acceptanceService;
    }

    /** Open the design workbench: returns the aggregate, creating it on first access. */
    @GetMapping("/{workitemId}/prd")
    public Result<PrdDesignVO> getDesign(@PathVariable("workitemId") Long workitemId,
                                         @RequestParam(value = "versions", defaultValue = "true") boolean versions) {
        PrdDesignVO design = designService.getOrCreate(currentWorkspaceId(), workitemId, currentUserId());
        if (versions) {
            design.setVersions(designService.listVersions(currentWorkspaceId(), workitemId));
        }
        return Result.ok(design);
    }

    /**
     * Read-only summary for the workitem card: the design if one exists, else null. Browsing a workitem
     * must not create a design aggregate, so this never falls back to {@code getOrCreate}.
     */
    @GetMapping("/{workitemId}/prd/summary")
    public Result<PrdDesignVO> summary(@PathVariable("workitemId") Long workitemId) {
        return Result.ok(designService.peek(currentWorkspaceId(), workitemId));
    }

    /** Bind the target frontend application detected by probing. */
    @PutMapping("/{workitemId}/prd/target")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE,
            action = "设计 PRD", actionKey = "access.action.design_prd")
    public Result<PrdDesignVO> selectTarget(@PathVariable("workitemId") Long workitemId,
                                            @RequestBody SelectTargetRequest request) {
        requireProfileId(request);
        return Result.ok(designService.selectTarget(currentWorkspaceId(), workitemId, request, currentUserId()));
    }

    /** Save a design draft. A null versionId appends a new version; otherwise that draft is rewritten. */
    @PostMapping("/{workitemId}/prd/versions")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE,
            action = "设计 PRD", actionKey = "access.action.design_prd")
    public Result<PrdDesignVersionVO> saveDraft(@PathVariable("workitemId") Long workitemId,
                                                @RequestBody SaveDesignRequest request) {
        if (request.getDesignJson() == null || request.getDesignJson().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return Result.ok(designService.saveDraft(currentWorkspaceId(), workitemId, request, currentUserId()));
    }

    /** Create a new draft derived from an existing version, used when editing a confirmed one. */
    @PostMapping("/{workitemId}/prd/versions/{versionId}/draft")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE,
            action = "设计 PRD", actionKey = "access.action.design_prd")
    public Result<PrdDesignVersionVO> createDraft(@PathVariable("workitemId") Long workitemId,
                                                  @PathVariable("versionId") Long versionId,
                                                  @RequestParam(value = "changeSummary", required = false)
                                                  String changeSummary) {
        return Result.ok(designService.createDraftFrom(currentWorkspaceId(), workitemId, versionId,
                changeSummary, currentUserId()));
    }

    @GetMapping("/{workitemId}/prd/versions")
    public Result<List<PrdDesignVersionVO>> listVersions(@PathVariable("workitemId") Long workitemId) {
        return Result.ok(designService.listVersions(currentWorkspaceId(), workitemId));
    }

    @GetMapping("/{workitemId}/prd/versions/{versionId}")
    public Result<PrdDesignVersionVO> getVersion(@PathVariable("workitemId") Long workitemId,
                                                 @PathVariable("versionId") Long versionId) {
        requireSameWorkitem(workitemId, versionId);
        return Result.ok(designService.getVersion(currentWorkspaceId(), versionId));
    }

    /** Move a draft to pending review. */
    @PostMapping("/{workitemId}/prd/versions/{versionId}/review")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE,
            action = "设计 PRD", actionKey = "access.action.design_prd")
    public Result<PrdDesignVersionVO> submitForReview(@PathVariable("workitemId") Long workitemId,
                                                      @PathVariable("versionId") Long versionId) {
        requireSameWorkitem(workitemId, versionId);
        return Result.ok(designService.submitForReview(currentWorkspaceId(), versionId, currentUserId()));
    }

    /** Confirm a reviewed version; unresolved questions require explicit acknowledgement. */
    @PostMapping("/{workitemId}/prd/confirm")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE,
            action = "设计 PRD", actionKey = "access.action.design_prd")
    public Result<PrdDesignVersionVO> confirm(@PathVariable("workitemId") Long workitemId,
                                              @RequestBody ConfirmVersionRequest request) {
        if (request.getVersionId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        requireSameWorkitem(workitemId, request.getVersionId());
        return Result.ok(designService.confirm(currentWorkspaceId(), workitemId, request, currentUserId()));
    }

    /** The rendered HTML document, served for the isolated preview iframe. */
    @GetMapping("/{workitemId}/prd/versions/{versionId}/html")
    public ResponseEntity<byte[]> html(@PathVariable("workitemId") Long workitemId,
                                       @PathVariable("versionId") Long versionId) {
        requireSameWorkitem(workitemId, versionId);
        byte[] html = designService.readHtml(currentWorkspaceId(), versionId);
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                // The document is design content, never a trusted first-party page: block framing
                // and cross-origin reads so it cannot be embedded elsewhere or reach host state.
                .header("Content-Security-Policy", "sandbox allow-scripts; default-src 'none'; "
                        + "style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src data: blob:; "
                        + "frame-src about:; connect-src 'none'; base-uri 'none'; form-action 'none'")
                .header("X-Content-Type-Options", "nosniff")
                .header("X-Frame-Options", "SAMEORIGIN")
                .header("Cross-Origin-Resource-Policy", "same-origin")
                .header("Cache-Control", "private, max-age=60")
                .body(html);
    }

    /**
     * Deliveries of this workitem that were driven by a confirmed PRD version, newest first. Used by
     * the workitem to list acceptance per delivery and to open the requirement-to-evidence view.
     */
    @GetMapping("/{workitemId}/prd/deliveries")
    public Result<List<com.aliyun.autowonder.prd.dto.PrdDeliveryBindingVO>> deliveries(
            @PathVariable("workitemId") Long workitemId,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return Result.ok(acceptanceService.listDeliveryBindings(currentWorkspaceId(), workitemId, limit));
    }

    /**
     * Per-requirement acceptance results for one delivery, with counts and the frozen version
     * identity. A requirement the user confirmed always has a row here; unverified stays unverified.
     */
    @GetMapping("/{workitemId}/prd/deliveries/{dispatchId}/acceptance")
    public Result<com.aliyun.autowonder.prd.dto.PrdDeliveryAcceptanceVO> acceptance(
            @PathVariable("workitemId") Long workitemId,
            @PathVariable("dispatchId") Long dispatchId) {
        return Result.ok(acceptanceService.deliveryAcceptance(currentWorkspaceId(), workitemId, dispatchId));
    }

    private void requireProfileId(SelectTargetRequest request) {
        if (request == null || (request.getProfileId() == null && request.getRepoId() == null)) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
    }

    /** The version must belong to this workitem's design, not just to this workspace. */
    private void requireSameWorkitem(Long workitemId, Long versionId) {
        PrdDesignVO design = designService.get(currentWorkspaceId(), workitemId);
        PrdDesignVersionVO version = designService.getVersion(currentWorkspaceId(), versionId);
        if (!version.getDesignId().equals(design.getId())) {
            throw new BizException(ErrorCode.PRD_VERSION_NOT_FOUND);
        }
    }

    private long currentUserId() {
        Long uid = AutoWonderContext.get().getUserId();
        if (uid == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return uid;
    }

    private long currentWorkspaceId() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return workspaceId;
    }
}
