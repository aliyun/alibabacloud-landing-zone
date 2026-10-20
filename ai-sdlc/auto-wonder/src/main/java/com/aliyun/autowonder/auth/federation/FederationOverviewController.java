package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.auth.federation.dto.FederationOverviewVO;
import com.aliyun.autowonder.common.result.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已登录用户的 SSO 只读概览：不做平台管理员校验，仅返回白名单投影。
 * 配置的增删改与密钥相关字段仍只存在于 {@link FederationAdminController}。
 */
@RestController
@RequestMapping("/api/platform/federation")
public class FederationOverviewController {

    private final FederationRegistryService registryService;

    public FederationOverviewController(FederationRegistryService registryService) {
        this.registryService = registryService;
    }

    @GetMapping("/overview")
    public Result<FederationOverviewVO> overview() {
        return Result.ok(registryService.overview());
    }
}
