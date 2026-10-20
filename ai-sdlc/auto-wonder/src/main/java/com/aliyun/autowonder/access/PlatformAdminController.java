package com.aliyun.autowonder.access;

import com.aliyun.autowonder.access.dto.AddPlatformAdminRequest;
import com.aliyun.autowonder.access.dto.PlatformAdminCandidateVO;
import com.aliyun.autowonder.access.dto.PlatformAdminListVO;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Platform-admin roster management. Global platform capability, so it sits outside the workspace
 * access ladder exactly like {@code PlatformBrandingController} and authorizes through
 * {@link SystemAdminService#requireSystemAdmin} instead of {@code @RequireWorkspaceAccess}.
 * Mutations return the refreshed roster so the panel re-renders the "last admin" state in one round
 * trip.
 */
@RestController
@RequestMapping("/api/platform/admins")
public class PlatformAdminController {

    private final SystemAdminService systemAdminService;

    public PlatformAdminController(SystemAdminService systemAdminService) {
        this.systemAdminService = systemAdminService;
    }

    @GetMapping
    public Result<PlatformAdminListVO> list() {
        return Result.ok(systemAdminService.listPlatformAdmins(currentUserId()));
    }

    @GetMapping("/candidates")
    public Result<List<PlatformAdminCandidateVO>> candidates(
            @RequestParam(name = "keyword", required = false) String keyword) {
        systemAdminService.requireSystemAdmin(currentUserId(), "搜索平台管理员候选人", "access.admin.search_denied");
        return Result.ok(systemAdminService.searchPlatformAdminCandidates(keyword));
    }

    @PostMapping
    public Result<PlatformAdminListVO> add(@RequestBody AddPlatformAdminRequest request) {
        Long operatorId = currentUserId();
        systemAdminService.addPlatformAdmin(operatorId, request.getUserId());
        return Result.ok(systemAdminService.listPlatformAdmins(operatorId));
    }

    @DeleteMapping("/{userId}")
    public Result<PlatformAdminListVO> remove(@PathVariable("userId") Long userId) {
        Long operatorId = currentUserId();
        systemAdminService.removePlatformAdmin(operatorId, userId);
        return Result.ok(systemAdminService.listPlatformAdmins(operatorId));
    }

    private Long currentUserId() {
        return AutoWonderContext.get().getUserId();
    }
}
