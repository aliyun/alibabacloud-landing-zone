package com.aliyun.autowonder.user;

import com.aliyun.autowonder.auth.federation.PlatformAuthConfigService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.user.dto.RegisterRequest;
import com.aliyun.autowonder.user.dto.RegisterResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthControllerRegisterTest {

    private final UserService userService = mock(UserService.class);
    private final PlatformAuthConfigService authConfigService = mock(PlatformAuthConfigService.class);
    private final AuthController controller = new AuthController(userService, authConfigService);

    @Test
    void disabledSelfRegistrationRejectsTheApiEvenWhenTheCallerSkipsTheHiddenPage() {
        when(authConfigService.registrationEnabled()).thenReturn(false);

        BizException error = assertThrows(BizException.class, () -> controller.register(new RegisterRequest()));

        assertEquals("37011", error.getCode());
        verify(userService, never()).register(any());
    }

    @Test
    void enabledSelfRegistrationForwardsToTheUserService() {
        RegisterRequest request = new RegisterRequest();
        RegisterResponse created = new RegisterResponse();
        when(authConfigService.registrationEnabled()).thenReturn(true);
        when(userService.register(request)).thenReturn(created);

        assertSame(created, controller.register(request).getData());
    }
}
