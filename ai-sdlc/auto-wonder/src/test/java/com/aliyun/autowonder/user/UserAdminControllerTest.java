package com.aliyun.autowonder.user;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.im.dto.UpdateUserImIdentityRequest;
import com.aliyun.autowonder.user.dto.AdminResetPasswordRequest;
import com.aliyun.autowonder.user.dto.DeactivationRequest;
import com.aliyun.autowonder.user.dto.UpdateProfileRequest;
import com.aliyun.autowonder.user.dto.UserWorkspaceAddRequest;
import com.aliyun.autowonder.user.dto.UserWorkspaceLevelRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Every route on the platform user-management surface is platform-admin gated: the guard runs
 * before any delegation, and the target/operator ids come from the path and the request context
 * rather than the body.
 */
class UserAdminControllerTest {

    private static final long ADMIN_ID = 10000L;
    private static final long TARGET_ID = 42L;
    private static final long WORKSPACE_ID = 100L;
    private static final String ADMIN_ACTION = "管理用户";
    private static final String ADMIN_DENIED_KEY = "access.user_admin.denied";

    private final UserAdminService userAdminService = mock(UserAdminService.class);
    private final SystemAdminService systemAdminService = mock(SystemAdminService.class);
    private final UserAdminController controller =
            new UserAdminController(userAdminService, systemAdminService);

    @AfterEach
    void cleanup() {
        AutoWonderContext.destroy();
    }

    @Test
    void theListIsGatedAndForwardsTheRawFilters() {
        AutoWonderContext.get().setUserId(ADMIN_ID);

        controller.list("ali", "deleted", "admin", 2, 50);

        InOrder order = inOrder(systemAdminService, userAdminService);
        order.verify(systemAdminService).requireSystemAdmin(ADMIN_ID, ADMIN_ACTION, ADMIN_DENIED_KEY);
        order.verify(userAdminService).page("ali", "deleted", "admin", 2, 50);
    }

    @Test
    void detailIsGatedAndReadsThePathId() {
        AutoWonderContext.get().setUserId(ADMIN_ID);

        controller.detail(TARGET_ID);

        InOrder order = inOrder(systemAdminService, userAdminService);
        order.verify(systemAdminService).requireSystemAdmin(ADMIN_ID, ADMIN_ACTION, ADMIN_DENIED_KEY);
        order.verify(userAdminService).detail(TARGET_ID);
    }

    @Test
    void updateProfileAndResetPasswordCarryTheAdminAsTheOperator() {
        AutoWonderContext.get().setUserId(ADMIN_ID);
        UpdateProfileRequest profile = new UpdateProfileRequest();
        AdminResetPasswordRequest password = new AdminResetPasswordRequest();

        controller.updateProfile(TARGET_ID, profile);
        controller.resetPassword(TARGET_ID, password);

        verify(userAdminService).updateProfile(TARGET_ID, profile, ADMIN_ID);
        verify(userAdminService).resetPassword(TARGET_ID, password, ADMIN_ID);
        verify(systemAdminService, org.mockito.Mockito.times(2))
                .requireSystemAdmin(ADMIN_ID, ADMIN_ACTION, ADMIN_DENIED_KEY);
    }

    @Test
    void deleteUnwrapsTheConfirmationAndTreatsAMissingBodyAsNoConfirmation() {
        AutoWonderContext.get().setUserId(ADMIN_ID);
        DeactivationRequest req = new DeactivationRequest();
        req.setConfirmUsername("alice");

        controller.delete(TARGET_ID, req);
        controller.delete(TARGET_ID, null);

        verify(userAdminService).delete(TARGET_ID, "alice", ADMIN_ID);
        verify(userAdminService).delete(TARGET_ID, null, ADMIN_ID);
    }

    @Test
    void restorePassesOnlyTheTargetAndTheAdmin() {
        AutoWonderContext.get().setUserId(ADMIN_ID);

        controller.restore(TARGET_ID);

        verify(userAdminService).restore(TARGET_ID, ADMIN_ID);
    }

    @Test
    void theWorkspaceRoutesForwardThePathIdsAndTheAdmin() {
        AutoWonderContext.get().setUserId(ADMIN_ID);
        UserWorkspaceAddRequest add = new UserWorkspaceAddRequest();
        add.setWorkspaceId(WORKSPACE_ID);
        UserWorkspaceLevelRequest level = new UserWorkspaceLevelRequest();
        level.setAccessLevel("admin");

        controller.listWorkspaces(TARGET_ID);
        controller.addWorkspace(TARGET_ID, add);
        controller.updateWorkspaceLevel(TARGET_ID, WORKSPACE_ID, level);
        controller.removeWorkspace(TARGET_ID, WORKSPACE_ID);

        verify(userAdminService).listWorkspaces(TARGET_ID);
        verify(userAdminService).addWorkspace(TARGET_ID, add, ADMIN_ID);
        verify(userAdminService).updateWorkspaceLevel(TARGET_ID, WORKSPACE_ID, "admin", ADMIN_ID);
        verify(userAdminService).removeWorkspace(TARGET_ID, WORKSPACE_ID, ADMIN_ID);
    }

    @Test
    void aMissingLevelBodyIsForwardedAsNull() {
        AutoWonderContext.get().setUserId(ADMIN_ID);

        controller.updateWorkspaceLevel(TARGET_ID, WORKSPACE_ID, null);

        verify(userAdminService).updateWorkspaceLevel(TARGET_ID, WORKSPACE_ID, null, ADMIN_ID);
    }

    @Test
    void theIdentityRoutesForwardTheProviderAndTheBody() {
        AutoWonderContext.get().setUserId(ADMIN_ID);
        UpdateUserImIdentityRequest req = new UpdateUserImIdentityRequest();
        req.setExternalUserId("emp-1");

        controller.listImIdentities(TARGET_ID);
        controller.updateImIdentity(TARGET_ID, "dingtalk", req);
        controller.sendTestIm(TARGET_ID, "dingtalk");

        verify(userAdminService).listImIdentities(TARGET_ID);
        verify(userAdminService).updateImIdentity(TARGET_ID, "dingtalk", "emp-1", ADMIN_ID);
        verify(userAdminService).sendTestIm(TARGET_ID, "dingtalk", ADMIN_ID);
    }

    @Test
    void aDeniedCallerNeverReachesTheService() {
        AutoWonderContext.get().setUserId(TARGET_ID);
        org.mockito.Mockito.doThrow(new BizException(ErrorCode.NO_PERMISSION))
                .when(systemAdminService).requireSystemAdmin(anyLong(), anyString(), anyString());

        assertThrows(BizException.class, () -> controller.list(null, null, null, 1, 20));
        assertThrows(BizException.class, () -> controller.detail(TARGET_ID));
        assertThrows(BizException.class, () -> controller.delete(TARGET_ID, null));
        assertThrows(BizException.class, () -> controller.restore(TARGET_ID));
        assertThrows(BizException.class, () -> controller.sendTestIm(TARGET_ID, "dingtalk"));

        verifyNoInteractions(userAdminService);
        verify(systemAdminService, org.mockito.Mockito.times(5))
                .requireSystemAdmin(TARGET_ID, ADMIN_ACTION, ADMIN_DENIED_KEY);
    }
}
