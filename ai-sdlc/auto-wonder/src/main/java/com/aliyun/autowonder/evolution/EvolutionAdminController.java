package com.aliyun.autowonder.evolution;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/evolution/admin")
@RequireOrgAccess(value = OrgAccessLevel.READ_ONLY, action = "查看演进管理信息")
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
        return Result.ok(adminQueryService.overview(currentOrgId(), limit));
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
        return Result.ok(assetManifestService.manifest(currentOrgId(), query));
    }

    private long currentOrgId() {
        Long orgId = AutoWonderContext.get().getCurrentOrgId();
        if (orgId == null) {
            throw new BizException(ErrorCode.ORG_NOT_MEMBER);
        }
        return orgId;
    }
}
