package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.branding.PlatformBrandingService;
import com.aliyun.autowonder.im.PlatformImChannelConfigDO;
import com.aliyun.autowonder.im.PlatformImChannelConfigService;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.common.error.KeyedIllegalArgumentException;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.integration.dingtalk.dto.BindingUpsertRequest;
import com.aliyun.autowonder.integration.dingtalk.dto.BindingView;
import com.aliyun.autowonder.access.WorkspaceAccessLevel;
import com.aliyun.autowonder.access.RequireWorkspaceAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/integrations/dingtalk/bindings")
@RequireWorkspaceAccess(value = WorkspaceAccessLevel.ADMIN, action = "管理钉钉绑定", actionKey = "access.action.manage_dingtalk_bindings")
public class DingTalkBindingController {

    private static final Logger log = LoggerFactory.getLogger(DingTalkBindingController.class);

    private final DingTalkBindingService service;
    private final DingTalkStreamClientManager streamClientManager;
    private final PlatformBrandingService brandingService;
    private final PlatformImChannelConfigService imConfigs;

    public DingTalkBindingController(DingTalkBindingService service,
            DingTalkStreamClientManager streamClientManager,
            PlatformBrandingService brandingService, PlatformImChannelConfigService imConfigs) {
        this.service = service;
        this.streamClientManager = streamClientManager;
        this.brandingService = brandingService;
        this.imConfigs = imConfigs;
    }

    @GetMapping
    public Result<List<BindingView>> list() {
        Long tenantId = AutoWonderContext.get().getCurrentWorkspaceId();
        List<BindingView> views = service.list(tenantId).stream()
                .map(this::toView).collect(Collectors.toList());
        return Result.ok(views);
    }

    @GetMapping("/{id}")
    public Result<BindingView> get(@PathVariable Long id) {
        Long tenantId = AutoWonderContext.get().getCurrentWorkspaceId();
        DingtalkRobotBindingDO row = service.get(tenantId, id);
        if (row == null) {
            throw new KeyedIllegalArgumentException("integration.dingtalk_binding_missing", new Object[]{String.valueOf(id)}, "binding not found: " + id);
        }
        return Result.ok(toView(row));
    }

    /** Only shared group-chat bindings can be created; legacy credentials are rejected explicitly. */
    @PostMapping
    public Result<BindingView> create(@RequestBody BindingUpsertRequest req) {
        imConfigs.requireSelected("DINGTALK");
        rejectLegacyPayload(req);
        Long tenantId = AutoWonderContext.get().getCurrentWorkspaceId();
        Long userId = AutoWonderContext.get().getUserId();
        DingtalkRobotBindingDO row = service.createShared(tenantId, userId, req.getGroupId(),
                req.getAgentId(), req.getStatus());
        return Result.ok(toView(row));
    }

    @PutMapping("/{id}")
    public Result<BindingView> update(@PathVariable Long id, @RequestBody BindingUpsertRequest req) {
        Long tenantId = AutoWonderContext.get().getCurrentWorkspaceId();
        Long userId = AutoWonderContext.get().getUserId();
        DingtalkRobotBindingDO oldRow = service.get(tenantId, id);
        if (oldRow == null) {
            throw new KeyedIllegalArgumentException("integration.dingtalk_binding_missing", new Object[]{String.valueOf(id)}, "binding not found: " + id);
        }
        if (service.isShared(oldRow)) {
            rejectLegacyPayload(req);
            DingtalkRobotBindingDO row = service.updateShared(tenantId, userId, id, req.getGroupId(),
                    req.getAgentId(), req.getStatus(), req.getVersion());
            return Result.ok(toView(row));
        }
        rejectSharedMode(req);
        DingtalkRobotBindingDO row = service.update(tenantId, userId, id, req.getAppKey(),
                req.getAppSecret(), req.getRobotCode(), req.getAgentId(), req.getTransportMode(),
                req.getStreamEnv(), req.getCallbackToken(), req.getBaseUrl(), req.getRegionId(), req.getStatus());
        stopStream(oldRow);
        startStreamIfEligible(row);
        return Result.ok(toView(row));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long tenantId = AutoWonderContext.get().getCurrentWorkspaceId();
        DingtalkRobotBindingDO oldRow = service.get(tenantId, id);
        service.delete(tenantId, id);
        if (oldRow != null && !service.isShared(oldRow)) {
            stopStream(oldRow);
        }
        return Result.ok(null);
    }

    BindingView toView(DingtalkRobotBindingDO row) {
        boolean shared = service.isShared(row);
        BindingView v = new BindingView();
        v.setId(row.getId());
        v.setBindingMode(shared ? "SHARED" : "LEGACY");
        v.setPlatformConfigId(row.getPlatformConfigId());
        v.setGroupId(row.getGroupId());
        v.setRouteVersion(row.getRouteVersion() == null ? 0L : row.getRouteVersion());
        v.setVersion(row.getVersion());
        PlatformImChannelConfigDO config = shared && imConfigs != null
                ? imConfigs.findGroupChatById(row.getPlatformConfigId()) : null;
        // Shared bindings follow the platform configuration, so the list shows its current identity.
        v.setAppKey(config == null ? row.getAppKey() : config.getAppKey());
        v.setAppSecretMasked(shared ? null : "****");
        v.setRobotCode(config == null ? row.getRobotCode() : config.getRobotCode());
        v.setAgentId(row.getAgentId());
        v.setTransportMode(row.getTransportMode());
        v.setStreamEnv(row.getStreamEnv());
        v.setBaseUrl(row.getBaseUrl());
        v.setRegionId(row.getRegionId());
        v.setStatus(row.getStatus());
        v.setLastSuccessAt(row.getLastSuccessAt());
        v.setLastError(row.getLastError());
        if (!shared) {
            v.setCallbackUrl(buildCallbackUrl(row));
            service.applyStreamStatus(row, v);
        }
        return v;
    }

    private String buildCallbackUrl(DingtalkRobotBindingDO row) {
        String token = row.getCallbackToken() == null ? "" : row.getCallbackToken();
        return brandingService.effectivePublicBaseUrl()
                + "/api/integrations/dingtalk/callback?token=" + token;
    }

    private void rejectLegacyPayload(BindingUpsertRequest req) {
        if (req == null) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "integration.shared_fields_forbidden",
                    new Object[0], "共享群聊绑定不接受独立机器人凭据或旧模式参数");
        }
        boolean legacyMode = req.getBindingMode() != null
                && !req.getBindingMode().isBlank()
                && !"SHARED".equalsIgnoreCase(req.getBindingMode());
        if (legacyMode
                || hasText(req.getAppKey()) || hasText(req.getAppSecret())
                || hasText(req.getRobotCode()) || hasText(req.getTransportMode())
                || hasText(req.getStreamEnv()) || hasText(req.getCallbackToken())
                || hasText(req.getBaseUrl()) || hasText(req.getRegionId())) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "integration.shared_fields_forbidden",
                    new Object[0], "共享群聊绑定不接受独立机器人凭据或旧模式参数");
        }
    }

    private void rejectSharedMode(BindingUpsertRequest req) {
        if (req != null && hasText(req.getBindingMode()) && !"LEGACY".equalsIgnoreCase(req.getBindingMode().trim())) {
            throw BizException.keyed(ErrorCode.PARAM_INVALID, "integration.binding_mode_immutable",
                    new Object[0], "不允许通过编辑转换绑定模式");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private void startStreamIfEligible(DingtalkRobotBindingDO row) {
        if (!isEnabledLegacyStream(row)) {
            return;
        }
        try {
            streamClientManager.start(row);
        } catch (RuntimeException e) {
            log.warn("failed to start DingTalk Stream bindingId={} appKey={}",
                    row.getId(), row.getAppKey(), e);
        }
    }

    private void stopStream(DingtalkRobotBindingDO row) {
        if (row == null) {
            return;
        }
        try {
            streamClientManager.stop(row);
        } catch (RuntimeException e) {
            log.warn("failed to stop DingTalk Stream bindingId={} appKey={}",
                    row.getId(), row.getAppKey(), e);
        }
    }

    private boolean isEnabledLegacyStream(DingtalkRobotBindingDO row) {
        return row != null
                && !service.isShared(row)
                && "ENABLED".equals(row.getStatus())
                && "STREAM".equals(row.getTransportMode());
    }
}
