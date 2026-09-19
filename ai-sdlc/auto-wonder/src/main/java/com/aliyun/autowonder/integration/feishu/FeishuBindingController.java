package com.aliyun.autowonder.integration.feishu;

import com.aliyun.autowonder.im.PlatformImChannelConfigService;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.springframework.web.bind.annotation.*;
import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/api/integrations/feishu/bindings")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "管理飞书绑定")
public class FeishuBindingController {
    public record View(Long id, String appId, Long agentId, String status, Integer version,
                       boolean encryptKeyConfigured, String callbackUrl, Date lastSuccessAt, String lastError) {}
    private final FeishuBindingService service;
    private final PlatformBrandingService brandingService;
    private final PlatformImChannelConfigService imConfigs;
    public FeishuBindingController(FeishuBindingService service, PlatformBrandingService brandingService, PlatformImChannelConfigService imConfigs) {
        this.imConfigs = imConfigs;
        this.service = service; this.brandingService = brandingService;
    }
    @GetMapping
    public Result<List<View>> list() { return Result.ok(service.list(tenant()).stream().map(this::view).toList()); }
    @PostMapping
    public Result<View> create(@RequestBody FeishuBindingService.Request req) {
        imConfigs.requireSelected("FEISHU");
        return Result.ok(view(service.save(tenant(), AutoWonderContext.get().getUserId(), null, req)));
    }
    @PutMapping("/{id}")
    public Result<View> update(@PathVariable Long id, @RequestBody FeishuBindingService.Request req) {
        imConfigs.requireSelected("FEISHU");
        return Result.ok(view(service.save(tenant(), AutoWonderContext.get().getUserId(), id, req)));
    }
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) { service.delete(tenant(), id); return Result.ok(null); }
    View view(FeishuBinding b) {
        var key = service.secrets(b).encryptKey();
        return new View(b.getId(), b.getAppId(), b.getAgentId(), b.getStatus(), b.getVersion(),
                key != null && !key.isBlank(),
                brandingService.effectivePublicBaseUrl()
                        + "/api/integrations/feishu/callback?bindingId=" + b.getId(),
                b.getLastSuccessAt(), b.getLastError());
    }
    private Long tenant() { return AutoWonderContext.get().getCurrentWorkspaceId(); }
}
