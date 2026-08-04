package com.aliyun.autowonder.integration.dingtalk;

import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.integration.dingtalk.dto.BindingUpsertRequest;
import com.aliyun.autowonder.integration.dingtalk.dto.BindingView;
import com.aliyun.autowonder.access.OrgAccessLevel;
import com.aliyun.autowonder.access.RequireOrgAccess;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/integrations/dingtalk/bindings")
@RequireOrgAccess(value = OrgAccessLevel.ADMIN, action = "管理钉钉绑定")
public class DingTalkBindingController {

    private static final Logger log = LoggerFactory.getLogger(DingTalkBindingController.class);

    private final DingTalkBindingService service;
    private final DingTalkStreamClientManager streamClientManager;
    private final String publicBaseUrl;

    public DingTalkBindingController(DingTalkBindingService service,
            DingTalkStreamClientManager streamClientManager,
            @Value("${autowonder.public-base-url:}") String publicBaseUrl) {
        this.service = service;
        this.streamClientManager = streamClientManager;
        this.publicBaseUrl = publicBaseUrl;
    }

    @GetMapping
    public Result<List<BindingView>> list() {
        Long tenantId = AutoWonderContext.get().getCurrentOrgId();
        List<BindingView> views = service.list(tenantId).stream()
                .map(this::toView).collect(Collectors.toList());
        return Result.ok(views);
    }

    @GetMapping("/{id}")
    public Result<BindingView> get(@PathVariable Long id) {
        Long tenantId = AutoWonderContext.get().getCurrentOrgId();
        DingtalkRobotBindingDO row = service.get(tenantId, id);
        if (row == null) {
            throw new IllegalArgumentException("binding not found: " + id);
        }
        return Result.ok(toView(row));
    }

    @PostMapping
    public Result<BindingView> create(@RequestBody BindingUpsertRequest req) {
        Long tenantId = AutoWonderContext.get().getCurrentOrgId();
        Long userId = AutoWonderContext.get().getUserId();
        DingtalkRobotBindingDO row = service.create(tenantId, userId, req.getAppKey(),
                req.getAppSecret(), req.getRobotCode(), req.getAgentId(), req.getTransportMode(),
                req.getStreamEnv(), req.getCallbackToken(), req.getBaseUrl(), req.getRegionId(),
                req.getStatus());
        startStreamIfEligible(row);
        return Result.ok(toView(row));
    }

    @PutMapping("/{id}")
    public Result<BindingView> update(@PathVariable Long id, @RequestBody BindingUpsertRequest req) {
        Long tenantId = AutoWonderContext.get().getCurrentOrgId();
        Long userId = AutoWonderContext.get().getUserId();
        DingtalkRobotBindingDO oldRow = service.get(tenantId, id);
        DingtalkRobotBindingDO row = service.update(tenantId, userId, id, req.getAppKey(),
                req.getAppSecret(), req.getRobotCode(), req.getAgentId(), req.getTransportMode(),
                req.getStreamEnv(), req.getCallbackToken(), req.getBaseUrl(), req.getRegionId(), req.getStatus());
        stopStream(oldRow);
        startStreamIfEligible(row);
        return Result.ok(toView(row));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        Long tenantId = AutoWonderContext.get().getCurrentOrgId();
        DingtalkRobotBindingDO oldRow = service.get(tenantId, id);
        service.delete(tenantId, id);
        stopStream(oldRow);
        return Result.ok(null);
    }

    BindingView toView(DingtalkRobotBindingDO row) {
        BindingView v = new BindingView();
        v.setId(row.getId());
        v.setAppKey(row.getAppKey());
        v.setAppSecretMasked("****");
        v.setRobotCode(row.getRobotCode());
        v.setAgentId(row.getAgentId());
        v.setTransportMode(row.getTransportMode());
        v.setStreamEnv(row.getStreamEnv());
        v.setBaseUrl(row.getBaseUrl());
        v.setRegionId(row.getRegionId());
        v.setStatus(row.getStatus());
        v.setLastSuccessAt(row.getLastSuccessAt());
        v.setLastError(row.getLastError());
        v.setCallbackUrl(buildCallbackUrl(row));
        service.applyStreamStatus(row, v);
        return v;
    }

    private String buildCallbackUrl(DingtalkRobotBindingDO row) {
        String token = row.getCallbackToken() == null ? "" : row.getCallbackToken();
        return publicBaseUrl + "/api/integrations/dingtalk/callback?token=" + token;
    }

    private void startStreamIfEligible(DingtalkRobotBindingDO row) {
        if (!isEnabledStream(row)) {
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

    private boolean isEnabledStream(DingtalkRobotBindingDO row) {
        return row != null
                && "ENABLED".equals(row.getStatus())
                && "STREAM".equals(row.getTransportMode());
    }
}
