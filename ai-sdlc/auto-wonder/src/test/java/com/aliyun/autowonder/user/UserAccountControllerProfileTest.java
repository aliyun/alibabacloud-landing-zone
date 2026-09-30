package com.aliyun.autowonder.user;

import com.aliyun.autowonder.context.AutoWonderContext;
import com.aliyun.autowonder.user.dto.UpdateProfileRequest;
import com.aliyun.autowonder.user.dto.UserProfileVO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserAccountControllerProfileTest {

    private static final long USER_ID = 9L;

    private final UserService userService = mock(UserService.class);
    private final AccountDeactivationService deactivationService = mock(AccountDeactivationService.class);
    private final UserAccountController controller =
            new UserAccountController(userService, deactivationService);

    @AfterEach
    void tearDown() {
        AutoWonderContext.destroy();
    }

    @Test
    void getProfile_reads_the_logged_in_users_own_profile() {
        AutoWonderContext.get().setUserId(USER_ID);
        UserProfileVO vo = new UserProfileVO();
        vo.setId(USER_ID);
        vo.setUsername("alice");
        vo.setNickname("爱丽丝");
        when(userService.getProfile(USER_ID)).thenReturn(vo);

        UserProfileVO data = controller.getProfile().getData();

        assertSame(vo, data);
        verify(userService).getProfile(USER_ID);
    }

    @Test
    void updateProfile_passes_the_logged_in_user_id_and_the_request_body() {
        AutoWonderContext.get().setUserId(USER_ID);
        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setNickname("新昵称");
        req.setEmail("new@example.com");
        req.setPhone("+86 138-0000-0000");
        UserProfileVO vo = new UserProfileVO();
        vo.setId(USER_ID);
        vo.setNickname("新昵称");
        when(userService.updateProfile(USER_ID, req)).thenReturn(vo);

        UserProfileVO data = controller.updateProfile(req).getData();

        assertSame(vo, data);
        verify(userService).updateProfile(USER_ID, req);
    }

    @Test
    void updateProfile_body_carries_only_nickname_email_phone_fields() {
        // The whitelist is structural: UpdateProfileRequest has no userId/username/isAdmin/status
        // setters, so a hostile payload cannot reach the account columns through this endpoint.
        java.util.Set<String> fields = new java.util.HashSet<>();
        for (java.lang.reflect.Field field : UpdateProfileRequest.class.getDeclaredFields()) {
            fields.add(field.getName());
        }
        assertEquals(java.util.Set.of("nickname", "email", "phone"), fields);
    }
}
