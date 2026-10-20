package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.auth.federation.dto.CreateConnectionRequest;
import com.aliyun.autowonder.auth.federation.dto.CreateIdpRequest;
import com.aliyun.autowonder.auth.federation.dto.FederationConnectionVO;
import com.aliyun.autowonder.auth.federation.dto.FederationIdpVO;
import com.aliyun.autowonder.auth.federation.dto.UpdateConnectionRequest;
import com.aliyun.autowonder.auth.federation.dto.UpdateIdpRequest;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 平台管理员的 SSO 配置面：IdP 实例与协议连接的 CRUD。全局平台能力，
 * 与 {@code PlatformAdminController} 一样以 {@link SystemAdminService#requireSystemAdmin} 授权。
 */
@RestController
@RequestMapping("/api/platform/federation")
public class FederationAdminController {

    private final FederationRegistryService registryService;
    private final SystemAdminService systemAdminService;

    public FederationAdminController(FederationRegistryService registryService,
                                     SystemAdminService systemAdminService) {
        this.registryService = registryService;
        this.systemAdminService = systemAdminService;
    }

    @GetMapping("/idps")
    public Result<List<FederationIdpVO>> listIdps() {
        requireAdmin("查看 SSO 身份提供方");
        return Result.ok(registryService.listIdps());
    }

    @PostMapping("/idps")
    public Result<FederationIdpVO> createIdp(@RequestBody(required = false) CreateIdpRequest request) {
        return Result.ok(registryService.createIdp(request, requireAdmin("创建 SSO 身份提供方")));
    }

    @PutMapping("/idps/{id}")
    public Result<FederationIdpVO> updateIdp(@PathVariable("id") Long id,
                                             @RequestBody(required = false) UpdateIdpRequest request) {
        requireAdmin("更新 SSO 身份提供方");
        return Result.ok(registryService.updateIdp(id, request));
    }

    @DeleteMapping("/idps/{id}")
    public Result<Void> deleteIdp(@PathVariable("id") Long id) {
        requireAdmin("删除 SSO 身份提供方");
        registryService.deleteIdp(id);
        return Result.ok(null);
    }

    @GetMapping("/connections")
    public Result<List<FederationConnectionVO>> listConnections() {
        requireAdmin("查看 SSO 登录方式");
        return Result.ok(registryService.listConnections());
    }

    @PostMapping("/connections")
    public Result<FederationConnectionVO> createConnection(
            @RequestBody(required = false) CreateConnectionRequest request) {
        requireAdmin("创建 SSO 登录方式");
        return Result.ok(registryService.createConnection(request));
    }

    @PutMapping("/connections/{id}")
    public Result<FederationConnectionVO> updateConnection(
            @PathVariable("id") Long id,
            @RequestBody(required = false) UpdateConnectionRequest request) {
        requireAdmin("更新 SSO 登录方式");
        return Result.ok(registryService.updateConnection(id, request));
    }

    @DeleteMapping("/connections/{id}")
    public Result<Void> deleteConnection(@PathVariable("id") Long id) {
        requireAdmin("删除 SSO 登录方式");
        registryService.deleteConnection(id);
        return Result.ok(null);
    }

    private Long requireAdmin(String action) {
        Long operatorId = AutoWonderContext.get().getUserId();
        systemAdminService.requireSystemAdmin(operatorId, action, "access.admin.federation_denied");
        return operatorId;
    }
}
