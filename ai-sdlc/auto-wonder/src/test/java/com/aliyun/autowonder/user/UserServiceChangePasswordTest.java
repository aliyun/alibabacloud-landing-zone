package com.aliyun.autowonder.user;

import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.session.SessionService;
import com.aliyun.autowonder.common.crypto.PasswordEncoderUtil;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.user.dto.ChangePasswordRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceChangePasswordTest {

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
        service = new UserService(userDao, jwtService, sessionService, props);
    }

    private UserDO userWithPassword(Long id, String password) {
        UserDO u = new UserDO();
        u.setId(id);
        u.setUsername("alice");
        u.setPasswordHash(PasswordEncoderUtil.encode(password));
        u.setStatus(0);
        return u;
    }

    @Test
    void changePassword_success() {
        UserDO u = userWithPassword(42L, "oldPass123");
        when(userDao.findById(42L)).thenReturn(u);

        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setOldPassword("oldPass123");
        req.setNewPassword("newPass456");

        service.changePassword(42L, req);

        verify(userDao).updatePasswordHash(eq(42L), argThat(hash ->
                PasswordEncoderUtil.matches("newPass456", hash)));
    }

    @Test
    void changePassword_wrong_old_password_throws_unauthorized() {
        UserDO u = userWithPassword(42L, "oldPass123");
        when(userDao.findById(42L)).thenReturn(u);

        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setOldPassword("wrong");
        req.setNewPassword("newPass456");

        BizException ex = assertThrows(BizException.class, () -> service.changePassword(42L, req));
        assertEquals("10401", ex.getCode());
        verify(userDao, never()).updatePasswordHash(anyLong(), anyString());
    }

    @Test
    void changePassword_user_not_found_throws_not_found() {
        when(userDao.findById(99L)).thenReturn(null);

        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setOldPassword("old");
        req.setNewPassword("new");

        BizException ex = assertThrows(BizException.class, () -> service.changePassword(99L, req));
        assertEquals("10404", ex.getCode());
    }

    @Test
    void changePassword_empty_old_password_throws_param_invalid() {
        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setOldPassword("");
        req.setNewPassword("new");

        BizException ex = assertThrows(BizException.class, () -> service.changePassword(42L, req));
        assertEquals("10001", ex.getCode());
    }

    @Test
    void changePassword_null_new_password_throws_param_invalid() {
        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setOldPassword("old");
        req.setNewPassword(null);

        BizException ex = assertThrows(BizException.class, () -> service.changePassword(42L, req));
        assertEquals("10001", ex.getCode());
    }

    @Test
    void changePassword_null_password_hash_throws_unauthorized() {
        UserDO u = new UserDO();
        u.setId(42L);
        u.setUsername("alice");
        u.setPasswordHash(null);
        u.setStatus(0);
        when(userDao.findById(42L)).thenReturn(u);

        ChangePasswordRequest req = new ChangePasswordRequest();
        req.setOldPassword("anything");
        req.setNewPassword("newPass");

        BizException ex = assertThrows(BizException.class, () -> service.changePassword(42L, req));
        assertEquals("10401", ex.getCode());
    }
}
