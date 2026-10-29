package com.aliyun.autowonder.integration.feishu;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;

import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import org.springframework.web.bind.annotation.*;
import java.util.Date;
import java.util.List;

@RestController
@RequestMapping("/api/integrations/feishu/bindings")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "管理飞书绑定", actionKey = "access.action.manage_feishu_bindings")
public class FeishuBindingController {
    public record View(Long id, String bindingMode, Long platformConfigId, String groupId,
                       Long routeVersion, String appId, Long agentId, String status, Integer version,
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
        rejectLegacyPayload(req);
        return Result.ok(view(service.save(tenant(), AutoWonderContext.get().getUserId(), null, req)));
    }
    @PutMapping("/{id}")
    public Result<View> update(@PathVariable Long id, @RequestBody FeishuBindingService.Request req) {
        FeishuBinding existing = service.get(tenant(), id);
        if (service.isShared(existing)) {
            rejectLegacyPayload(req);
        } else {
            rejectSharedMode(req);
        }
        return Result.ok(view(service.save(tenant(), AutoWonderContext.get().getUserId(), id, req)));
    }
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) { service.delete(tenant(), id); return Result.ok(null); }
    View view(FeishuBinding b) {
        boolean shared = service.isShared(b);
        PlatformImChannelConfigDO config = shared
                ? imConfigs.findGroupChatById(b.getPlatformConfigId()) : null;
        // Shared bindings follow the platform configuration, so the list shows its current identity.
        return new View(b.getId(), shared ? "SHARED" : "LEGACY", b.getPlatformConfigId(), b.getGroupId(),
                b.getRouteVersion() == null ? 0L : b.getRouteVersion(),
                config == null ? b.getAppId() : config.getAppKey(),
                b.getAgentId(), b.getStatus(), b.getVersion(),
                service.encryptKeyConfigured(b),
                shared ? brandingService.effectivePublicBaseUrl()
                        + "/api/integrations/feishu/group-callback?configId=" + b.getPlatformConfigId()
                        : brandingService.effectivePublicBaseUrl()
                        + "/api/integrations/feishu/callback?bindingId=" + b.getId(),
                b.getLastSuccessAt(), b.getLastError());
    }
    private void rejectLegacyPayload(FeishuBindingService.Request req) {
        if (req == null) {
            return;
        }
        boolean legacyMode = hasText(req.bindingMode())
                && !"SHARED".equalsIgnoreCase(req.bindingMode().trim());
        if (legacyMode || hasText(req.appId()) || hasText(req.appSecret())
                || hasText(req.verificationToken()) || hasText(req.encryptKey())
                || req.clearEncryptKey()) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "integration.shared_fields_forbidden",
                    new Object[0], "共享群聊绑定不接受独立机器人凭据或旧模式参数");
        }
    }
    private void rejectSharedMode(FeishuBindingService.Request req) {
        if (req != null && hasText(req.bindingMode()) && !"LEGACY".equalsIgnoreCase(req.bindingMode().trim())) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "integration.binding_mode_immutable",
                    new Object[0], "不允许通过编辑转换绑定模式");
        }
    }
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
    private Long tenant() { return AutoWonderContext.get().getCurrentWorkspaceId(); }
}
