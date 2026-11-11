package com.aliyun.autowonder.repo;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.frontendproject.FrontendProjectProbeService;
import com.aliyun.autowonder.frontendproject.dto.FrontendProjectProbeVO;
import com.aliyun.autowonder.frontendproject.dto.FrontendProjectProfileVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Frontend-project probing endpoints, mounted under {@code /api/repos/{id}} because probing is a
 * repo-scoped capability.
 *
 * <p>Lives in the {@code repo} package on purpose: {@code WorkspaceDomain.of(...)} maps a
 * controller's top-level package segment to a functional domain, and an unmapped package would
 * silently disable the read-only-role write guard. Under {@code repo} these endpoints resolve to
 * {@link com.aliyun.autowonder.access.WorkspaceDomain#REPO}, so a read-only member cannot start a
 * probe. Space isolation is enforced twice: {@link RequireWorkspaceAccess} for membership/level and
 * the service re-checking repo ownership against the current workspace.
 */
@RestController
@RequestMapping("/api/repos")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY,
        action = "查看前端项目", actionKey = "access.action.view_frontend_projects")
public class FrontendProjectController {

    private final FrontendProjectProbeService probeService;

    public FrontendProjectController(FrontendProjectProbeService probeService) {
        this.probeService = probeService;
    }

    @PostMapping("/frontend-projects/discover")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE,
            action = "探测前端项目", actionKey = "access.action.probe_frontend_projects")
    public Result<List<FrontendProjectProbeVO>> discover() {
        throw BizException.keyed(ErrorCode.PARAM_INVALID, "frontend.probe.retired", new Object[]{}, "Repository probing retired");
    }

    /** Enqueue a fresh probe. Rejects when one is already running for the repo. */
    @PostMapping("/{id}/frontend-projects/probe")
    @RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_WRITE,
            action = "探测前端项目", actionKey = "access.action.probe_frontend_projects")
    public Result<FrontendProjectProbeVO> probe(@PathVariable("id") Long repoId) {
        throw BizException.keyed(ErrorCode.PARAM_INVALID, "frontend.probe.retired", new Object[]{}, "Repository probing retired");
    }

    /** Latest probe run with its detected applications, or null when never probed. */
    @GetMapping("/{id}/frontend-projects/latest")
    public Result<FrontendProjectProbeVO> latest(@PathVariable("id") Long repoId) {
        return Result.ok(probeService.getLatestByRepo(currentWorkspaceId(), repoId));
    }

    /** All detected frontend applications cached for this repo, newest first. */
    @GetMapping("/{id}/frontend-projects")
    public Result<List<FrontendProjectProfileVO>> listProfiles(@PathVariable("id") Long repoId) {
        return Result.ok(probeService.listProfilesByRepo(currentWorkspaceId(), repoId));
    }

    /** Probe run history for this repo. */
    @GetMapping("/{id}/frontend-projects/probes")
    public Result<List<FrontendProjectProbeVO>> listProbes(@PathVariable("id") Long repoId) {
        return Result.ok(probeService.listProbesByRepo(currentWorkspaceId(), repoId));
    }

    /** One probe run by id, with its detected applications (pollable while RUNNING). */
    @GetMapping("/frontend-projects/probes/{probeId}")
    public Result<FrontendProjectProbeVO> getProbe(@PathVariable("probeId") Long probeId) {
        return Result.ok(probeService.getProbe(currentWorkspaceId(), probeId));
    }

    /** One detected application snapshot by id, with evidence. */
    @GetMapping("/frontend-projects/profiles/{profileId}")
    public Result<FrontendProjectProfileVO> getProfile(@PathVariable("profileId") Long profileId) {
        return Result.ok(probeService.getProfile(currentWorkspaceId(), profileId));
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
