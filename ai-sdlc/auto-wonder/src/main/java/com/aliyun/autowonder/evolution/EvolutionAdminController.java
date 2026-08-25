package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evolution/admin")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.READ_ONLY, action = "查看演进管理信息")
public class EvolutionAdminController {

    private final EvolutionAdminQueryLiteService adminQueryService;
    private final EvolutionAssetManifestLiteService assetManifestService;

    public EvolutionAdminController(EvolutionAdminQueryLiteService adminQueryService,
                                    EvolutionAssetManifestLiteService assetManifestService) {
        this.adminQueryService = adminQueryService;
        this.assetManifestService = assetManifestService;
    }

    @GetMapping("/overview")
    public Result<EvolutionAdminOverviewVO> overview(@RequestParam(value = "limit", required = false) Integer limit) {
        return Result.ok(adminQueryService.overview(currentWorkspaceId(), limit));
    }

    @GetMapping("/asset-manifest")
    public Result<EvolutionAssetManifestVO> assetManifest(
            @RequestParam(value = "assetType", required = false) String assetType,
            @RequestParam(value = "contextKey", required = false) String contextKey,
            @RequestParam(value = "limit", required = false) Integer limit) {
        EvolutionAssetManifestQuery query = new EvolutionAssetManifestQuery();
        query.setAssetType(assetType);
        query.setContextKey(contextKey);
        query.setLimit(limit);
        return Result.ok(assetManifestService.manifest(currentWorkspaceId(), query));
    }

    private long currentWorkspaceId() {
        Long workspaceId = AutoWonderContext.get().getCurrentWorkspaceId();
        if (workspaceId == null) {
            throw new BizException(ErrorCode.WORKSPACE_NOT_MEMBER);
        }
        return workspaceId;
    }
}
