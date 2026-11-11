package com.aliyun.autowonder.user;

import com.aliyun.autowonder.auth.federation.PlatformAuthConfigService;
import com.aliyun.autowonder.common.result.Result;
import com.aliyun.autowonder.user.dto.RefreshRequest;
import com.aliyun.autowonder.user.dto.RefreshResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthControllerRefreshTest {

    private static final long WORKSPACE_ID = 10002L;

    private RefreshRequest request(String refreshToken, Long workspaceId, boolean userActivity) {
        RefreshRequest req = new RefreshRequest();
        req.setRefreshToken(refreshToken);
        req.setWorkspaceId(workspaceId);
        req.setUserActivity(userActivity);
        return req;
    }

    @Test
    void refreshForwardsDeclaredWorkspaceSoTheNewTokenCanKeepItsClaim() {
        UserService userService = mock(UserService.class);
        when(userService.refreshAccessToken("rt", WORKSPACE_ID, true)).thenReturn("new-access");
        AuthController controller = new AuthController(userService, mock(PlatformAuthConfigService.class));

        Result<RefreshResponse> result = controller.refresh(request("rt", WORKSPACE_ID, true));

        assertTrue(result.isSuccess());
        assertEquals("new-access", result.getData().getAccessToken());
        verify(userService).refreshAccessToken("rt", WORKSPACE_ID, true);
    }

    @Test
    void refreshForwardsNullWorkspaceWhenTheClientDeclaresNone() {
        UserService userService = mock(UserService.class);
        when(userService.refreshAccessToken("rt", null, true)).thenReturn("new-access");
        AuthController controller = new AuthController(userService, mock(PlatformAuthConfigService.class));

        Result<RefreshResponse> result = controller.refresh(request("rt", null, true));

        assertTrue(result.isSuccess());
        assertEquals("new-access", result.getData().getAccessToken());
        verify(userService).refreshAccessToken("rt", null, true);
    }

    @Test
    void refreshForwardsBackgroundOriginSoOnlyForegroundUseCanExtendTheSession() {
        UserService userService = mock(UserService.class);
        when(userService.refreshAccessToken("rt", WORKSPACE_ID, false)).thenReturn("new-access");
        AuthController controller = new AuthController(userService, mock(PlatformAuthConfigService.class));

        controller.refresh(request("rt", WORKSPACE_ID, false));

        verify(userService).refreshAccessToken("rt", WORKSPACE_ID, false);
    }
}
