package com.aliyun.autowonder.user;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.session.SessionIssuanceService;
import com.aliyun.autowonder.auth.session.SessionService;
import com.aliyun.autowonder.common.crypto.PasswordEncoderUtil;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.workspace.WorkspaceMemberDao;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Admin-initiated password reset. There is no old-password check by design, so the two things that
 * must hold are the strength floor (same as registration) and that a target which stopped existing
 * mid-flight is reported instead of silently succeeding.
 */
class UserServiceResetPasswordTest {

    private UserDao userDao;
    private UserService service;

    @BeforeEach
    void setUp() {
        userDao = mock(UserDao.class);
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[]{"daily"});
        JwtProperties props = new JwtProperties(env);
        props.setSecret("test-secret-key-that-is-long-enough-32bytes!");
        service = new UserService(userDao, new JwtService(props), mock(SessionService.class), props,
                mock(WorkspaceMemberDao.class), mock(SystemAdminService.class),
                mock(SessionIssuanceService.class));
    }

    @Test
    void aBlankPasswordIsRejectedBeforeTouchingTheDatabase() {
        BizException ex = assertThrows(BizException.class, () -> service.resetPassword(42L, "   "));

        assertEquals("10001", ex.getCode());
        assertEquals("user.new_password_required", ex.getMessageKey());
        verify(userDao, never()).updatePasswordHash(anyLong(), anyString());
    }

    @Test
    void aNullPasswordIsRejected() {
        BizException ex = assertThrows(BizException.class, () -> service.resetPassword(42L, null));

        assertEquals("10001", ex.getCode());
        verify(userDao, never()).updatePasswordHash(anyLong(), anyString());
    }

    @Test
    void aPasswordBelowTheRegistrationFloorIsRejected() {
        BizException ex = assertThrows(BizException.class, () -> service.resetPassword(42L, "pw123"));

        assertEquals("10001", ex.getCode());
        assertEquals("user.new_password_too_short", ex.getMessageKey());
        assertEquals(6, ex.getMessageArgs()[0]);
        verify(userDao, never()).updatePasswordHash(anyLong(), anyString());
    }

    @Test
    void theExactFloorLengthIsAccepted() {
        when(userDao.updatePasswordHash(eq(42L), anyString())).thenReturn(1);

        service.resetPassword(42L, "pw1234");

        verify(userDao).updatePasswordHash(eq(42L), anyString());
    }

    @Test
    void aTargetThatNoLongerExistsIsReportedAsNotFound() {
        when(userDao.updatePasswordHash(eq(42L), anyString())).thenReturn(0);

        BizException ex = assertThrows(BizException.class,
                () -> service.resetPassword(42L, "brand-new-pw"));

        assertEquals("10404", ex.getCode());
        assertEquals("user.not_found", ex.getMessageKey());
    }

    @Test
    void theResetStoresAHashedCopyNotThePlaintext() {
        when(userDao.updatePasswordHash(eq(42L), anyString())).thenReturn(1);

        service.resetPassword(42L, "brand-new-pw");

        // The service must hash before it writes; the DAO never sees the plaintext.
        verify(userDao).updatePasswordHash(eq(42L),
                org.mockito.ArgumentMatchers.argThat(hash ->
                        !"brand-new-pw".equals(hash)
                                && PasswordEncoderUtil.matches("brand-new-pw", hash)));
    }
}
