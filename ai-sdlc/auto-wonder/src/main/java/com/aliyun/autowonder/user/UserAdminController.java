package com.aliyun.autowonder.user;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.im.dto.UpdateUserImIdentityRequest;
import com.aliyun.autowonder.im.dto.UserImIdentityVO;
import com.aliyun.autowonder.user.dto.AdminResetPasswordRequest;
import com.aliyun.autowonder.user.dto.DeactivationRequest;
import com.aliyun.autowonder.user.dto.UpdateProfileRequest;
import com.aliyun.autowonder.user.dto.UserAdminListVO;
import com.aliyun.autowonder.user.dto.UserAdminUserVO;
import com.aliyun.autowonder.user.dto.UserAdminWorkspaceVO;
import com.aliyun.autowonder.user.dto.UserWorkspaceAddRequest;
import com.aliyun.autowonder.user.dto.UserWorkspaceLevelRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 平台管理「用户管理」。与 {@code PlatformAdminController} 同类：全局平台能力，不走
 * {@code @RequireWorkspaceAccess}，而是对每个接口执行
 * {@link SystemAdminService#requireSystemAdmin}（非平台管理员连入口都看不到）。
 */
@RestController
@RequestMapping("/api/platform/users")
public class UserAdminController {

    private static final String ADMIN_ACTION = "管理用户";
    private static final String ADMIN_DENIED_KEY = "access.user_admin.denied";

    private final UserAdminService userAdminService;
    private final SystemAdminService systemAdminService;

    public UserAdminController(UserAdminService userAdminService, SystemAdminService systemAdminService) {
        this.userAdminService = userAdminService;
        this.systemAdminService = systemAdminService;
    }

    @GetMapping
    public Result<UserAdminListVO> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        requireAdmin();
        return Result.ok(userAdminService.page(keyword, status, type, page, size));
    }

    @GetMapping("/{id}")
    public Result<UserAdminUserVO> detail(@PathVariable("id") Long id) {
        requireAdmin();
        return Result.ok(userAdminService.detail(id));
    }

    @PutMapping("/{id}/profile")
    public Result<UserAdminUserVO> updateProfile(@PathVariable("id") Long id,
                                                 @RequestBody UpdateProfileRequest req) {
        requireAdmin();
        return Result.ok(userAdminService.updateProfile(id, req, currentUserId()));
    }

    @PostMapping("/{id}/password")
    public Result<Void> resetPassword(@PathVariable("id") Long id,
                                      @RequestBody AdminResetPasswordRequest req) {
        requireAdmin();
        userAdminService.resetPassword(id, req, currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/{id}/delete")
    public Result<Void> delete(@PathVariable("id") Long id,
                               @RequestBody DeactivationRequest req) {
        requireAdmin();
        userAdminService.delete(id, req == null ? null : req.getConfirmUsername(), currentUserId());
        return Result.ok(null);
    }

    @PostMapping("/{id}/restore")
    public Result<Void> restore(@PathVariable("id") Long id) {
        requireAdmin();
        userAdminService.restore(id, currentUserId());
        return Result.ok(null);
    }

    @GetMapping("/{id}/workspaces")
    public Result<List<UserAdminWorkspaceVO>> listWorkspaces(@PathVariable("id") Long id) {
        requireAdmin();
        return Result.ok(userAdminService.listWorkspaces(id));
    }

    @PostMapping("/{id}/workspaces")
    public Result<List<UserAdminWorkspaceVO>> addWorkspace(@PathVariable("id") Long id,
                                                           @RequestBody UserWorkspaceAddRequest req) {
        requireAdmin();
        return Result.ok(userAdminService.addWorkspace(id, req, currentUserId()));
    }

    @PutMapping("/{id}/workspaces/{workspaceId}")
    public Result<List<UserAdminWorkspaceVO>> updateWorkspaceLevel(
            @PathVariable("id") Long id,
            @PathVariable("workspaceId") Long workspaceId,
            @RequestBody UserWorkspaceLevelRequest req) {
        requireAdmin();
        return Result.ok(userAdminService.updateWorkspaceLevel(
                id, workspaceId, req == null ? null : req.getAccessLevel(), currentUserId()));
    }

    @DeleteMapping("/{id}/workspaces/{workspaceId}")
    public Result<List<UserAdminWorkspaceVO>> removeWorkspace(@PathVariable("id") Long id,
                                                              @PathVariable("workspaceId") Long workspaceId) {
        requireAdmin();
        return Result.ok(userAdminService.removeWorkspace(id, workspaceId, currentUserId()));
    }

    @GetMapping("/{id}/im-identity")
    public Result<List<UserImIdentityVO>> listImIdentities(@PathVariable("id") Long id) {
        requireAdmin();
        return Result.ok(userAdminService.listImIdentities(id));
    }

    @PutMapping("/{id}/im-identity/{provider}")
    public Result<UserImIdentityVO> updateImIdentity(
            @PathVariable("id") Long id,
            @PathVariable("provider") String provider,
            @Valid @RequestBody UpdateUserImIdentityRequest req) {
        requireAdmin();
        return Result.ok(userAdminService.updateImIdentity(
                id, provider, req.getExternalUserId(), currentUserId()));
    }

    @PostMapping("/{id}/im-identity/{provider}/test")
    public Result<Void> sendTestIm(@PathVariable("id") Long id,
                                   @PathVariable("provider") String provider) {
        requireAdmin();
        userAdminService.sendTestIm(id, provider, currentUserId());
        return Result.ok(null);
    }

    private void requireAdmin() {
        systemAdminService.requireSystemAdmin(currentUserId(), ADMIN_ACTION, ADMIN_DENIED_KEY);
    }

    private Long currentUserId() {
        return AutoWonderContext.get().getUserId();
    }
}
