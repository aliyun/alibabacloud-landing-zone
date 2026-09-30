package com.aliyun.autowonder.user;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.session.SessionService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.user.dto.UpdateProfileRequest;
import com.aliyun.autowonder.user.dto.UserProfileVO;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceProfileTest {

    private static final long USER_ID = 42L;

    private UserDao userDao;
    private UserService service;

    @BeforeEach
    void setUp() {
        userDao = mock(UserDao.class);
        SessionService sessionService = mock(SessionService.class);
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties props = new JwtProperties(env);
        props.setSecret("test-secret-key-that-is-long-enough-32bytes!");
        JwtService jwtService = new JwtService(props);
        service = new UserService(userDao, jwtService, sessionService, props,
                mock(WorkspaceMemberDao.class), mock(SystemAdminService.class));
    }

    private UserDO existingUser() {
        UserDO u = new UserDO();
        u.setId(USER_ID);
        u.setUsername("alice");
        u.setNickname("爱丽丝");
        u.setEmail("alice@example.com");
        u.setPhone("+86 138-0000-0000");
        u.setStatus(0);
        return u;
    }

    private UpdateProfileRequest request(String nickname, String email, String phone) {
        UpdateProfileRequest req = new UpdateProfileRequest();
        req.setNickname(nickname);
        req.setEmail(email);
        req.setPhone(phone);
        return req;
    }

    @Test
    void getProfile_returns_whitelisted_fields_including_phone() {
        when(userDao.findById(USER_ID)).thenReturn(existingUser());

        UserProfileVO vo = service.getProfile(USER_ID);

        assertEquals(USER_ID, vo.getId());
        assertEquals("alice", vo.getUsername());
        assertEquals("爱丽丝", vo.getNickname());
        assertEquals("alice@example.com", vo.getEmail());
        assertEquals("+86 138-0000-0000", vo.getPhone());
    }

    @Test
    void getProfile_user_not_found_throws_not_found() {
        when(userDao.findById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.getProfile(99L));
        assertEquals("10404", ex.getCode());
    }

    @Test
    void updateProfile_trims_and_persists_only_profile_columns() {
        UserDO updated = existingUser();
        updated.setNickname("新昵称");
        updated.setEmail("new@example.com");
        updated.setPhone("+44 20 7946-0958");
        when(userDao.findById(USER_ID)).thenReturn(existingUser(), updated);

        UserProfileVO vo = service.updateProfile(USER_ID,
                request("  新昵称  ", " new@example.com ", " +44 20 7946-0958 "));

        ArgumentCaptor<String> nickname = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> email = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> phone = ArgumentCaptor.forClass(String.class);
        verify(userDao).updateProfile(eq(USER_ID), nickname.capture(), email.capture(), phone.capture());
        assertEquals("新昵称", nickname.getValue());
        assertEquals("new@example.com", email.getValue());
        assertEquals("+44 20 7946-0958", phone.getValue());
        assertEquals("新昵称", vo.getNickname());
        assertEquals("new@example.com", vo.getEmail());
    }

    @Test
    void updateProfile_clears_optional_fields_to_null() {
        when(userDao.findById(USER_ID)).thenReturn(existingUser());

        service.updateProfile(USER_ID, request("爱丽丝", "   ", ""));

        verify(userDao).updateProfile(eq(USER_ID), eq("爱丽丝"), isNull(), isNull());
    }

    @Test
    void updateProfile_accepts_international_phone_formats() {
        when(userDao.findById(USER_ID)).thenReturn(existingUser());

        service.updateProfile(USER_ID, request("爱丽丝", "alice@example.com", "+1 555 010-2030"));

        verify(userDao).updateProfile(eq(USER_ID), eq("爱丽丝"), eq("alice@example.com"), eq("+1 555 010-2030"));
    }

    @Test
    void updateProfile_blank_nickname_throws_param_invalid() {
        BizException ex = assertThrows(BizException.class,
                () -> service.updateProfile(USER_ID, request("   ", "a@b.com", null)));
        assertEquals("10001", ex.getCode());
        verify(userDao, never()).updateProfile(anyLong(), any(), any(), any());
    }

    @Test
    void updateProfile_null_nickname_throws_param_invalid() {
        BizException ex = assertThrows(BizException.class,
                () -> service.updateProfile(USER_ID, request(null, "a@b.com", null)));
        assertEquals("10001", ex.getCode());
        verify(userDao, never()).updateProfile(anyLong(), any(), any(), any());
    }

    @Test
    void updateProfile_nickname_longer_than_64_throws_param_invalid() {
        BizException ex = assertThrows(BizException.class,
                () -> service.updateProfile(USER_ID, request("长".repeat(65), "a@b.com", null)));
        assertEquals("10001", ex.getCode());
        verify(userDao, never()).updateProfile(anyLong(), any(), any(), any());
    }

    @Test
    void updateProfile_invalid_email_format_throws_param_invalid() {
        BizException ex = assertThrows(BizException.class,
                () -> service.updateProfile(USER_ID, request("爱丽丝", "not-an-email", null)));
        assertEquals("10001", ex.getCode());
        verify(userDao, never()).updateProfile(anyLong(), any(), any(), any());
    }

    @Test
    void updateProfile_email_longer_than_128_throws_param_invalid() {
        String longEmail = "a".repeat(117) + "@example.com";
        BizException ex = assertThrows(BizException.class,
                () -> service.updateProfile(USER_ID, request("爱丽丝", longEmail, null)));
        assertEquals("10001", ex.getCode());
        verify(userDao, never()).updateProfile(anyLong(), any(), any(), any());
    }

    @Test
    void updateProfile_email_with_only_tld_free_domain_is_rejected() {
        BizException ex = assertThrows(BizException.class,
                () -> service.updateProfile(USER_ID, request("爱丽丝", "a@localhost", null)));
        assertEquals("10001", ex.getCode());
        verify(userDao, never()).updateProfile(anyLong(), any(), any(), any());
    }

    @Test
    void updateProfile_phone_with_letters_throws_param_invalid() {
        BizException ex = assertThrows(BizException.class,
                () -> service.updateProfile(USER_ID, request("爱丽丝", "a@b.com", "13800phonen")));
        assertEquals("10001", ex.getCode());
        verify(userDao, never()).updateProfile(anyLong(), any(), any(), any());
    }

    @Test
    void updateProfile_phone_longer_than_32_throws_param_invalid() {
        String longPhone = "+1 " + "1".repeat(31);
        BizException ex = assertThrows(BizException.class,
                () -> service.updateProfile(USER_ID, request("爱丽丝", "a@b.com", longPhone)));
        assertEquals("10001", ex.getCode());
        verify(userDao, never()).updateProfile(anyLong(), any(), any(), any());
    }

    @Test
    void updateProfile_null_request_throws_param_invalid() {
        BizException ex = assertThrows(BizException.class,
                () -> service.updateProfile(USER_ID, null));
        assertEquals("10001", ex.getCode());
        verify(userDao, never()).updateProfile(anyLong(), any(), any(), any());
    }

    @Test
    void updateProfile_duplicate_email_translated_to_conflict() {
        when(userDao.findById(USER_ID)).thenReturn(existingUser());
        doThrow(new org.springframework.dao.DuplicateKeyException("uk_email"))
                .when(userDao).updateProfile(eq(USER_ID), any(), any(), any());

        BizException ex = assertThrows(BizException.class,
                () -> service.updateProfile(USER_ID, request("爱丽丝", "taken@example.com", null)));
        assertEquals("10409", ex.getCode());
        assertEquals("邮箱已被使用", ex.getMessage());
    }

    @Test
    void updateProfile_user_not_found_throws_not_found() {
        when(userDao.findById(99L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class,
                () -> service.updateProfile(99L, request("爱丽丝", "a@b.com", null)));
        assertEquals("10404", ex.getCode());
        verify(userDao, never()).updateProfile(anyLong(), any(), any(), any());
    }

    @Test
    void updateProfile_same_nickname_for_same_user_is_allowed() {
        when(userDao.findById(USER_ID)).thenReturn(existingUser());

        service.updateProfile(USER_ID, request("爱丽丝", null, null));

        verify(userDao).updateProfile(eq(USER_ID), eq("爱丽丝"), isNull(), isNull());
    }
}
