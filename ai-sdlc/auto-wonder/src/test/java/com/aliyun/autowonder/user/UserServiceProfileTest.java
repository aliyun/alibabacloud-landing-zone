package com.aliyun.autowonder.user;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.auth.jwt.JwtProperties;
import com.aliyun.autowonder.auth.jwt.JwtService;
import com.aliyun.autowonder.auth.session.SessionIssuanceService;
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
                mock(WorkspaceMemberDao.class), mock(SystemAdminService.class),
                new SessionIssuanceService(jwtService, sessionService, props));
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(delimiter = '|', value = {
        "registerEmpty|10001|用户名不能为空|Username is required.",
        "registerDuplicate|10409|用户名已存在|Username already exists.",
        "oldEmpty|10001|旧密码不能为空|Old password is required.",
        "newEmpty|10001|新密码不能为空|New password is required.",
        "passwordUserMissing|10404|用户不存在|User does not exist.",
        "oldWrong|10401|旧密码不正确|Incorrect old password.",
        "getUserMissing|10404|用户不存在|User does not exist.",
        "requestMissing|10001|请求不能为空|Request is required.",
        "nicknameEmpty|10001|昵称不能为空|Nickname is required.",
        "nicknameLong|10001|昵称最多 64 个字符|Nickname must not exceed 64 characters.",
        "emailLong|10001|邮箱最多 128 个字符|Email must not exceed 128 characters.",
        "emailInvalid|10001|邮箱格式不正确|Invalid email format.",
        "phoneLong|10001|联系方式最多 32 个字符|Contact information must not exceed 32 characters.",
        "phoneInvalid|10001|联系方式仅支持数字、空格、连字符，可带国际区号前缀 +|Contact information supports only digits, spaces and hyphens, with an optional + international dialing prefix.",
        "updateUserMissing|10404|用户不存在|User does not exist.",
        "emailDuplicate|10409|邮箱已被使用|Email is already in use."
    })
    void ownedUserGuardRendersEnglishWithoutChangingDiagnosticOrWrites(
            String branch, String code, String diagnostic, String english) {
        var register = new com.aliyun.autowonder.user.dto.RegisterRequest();
        register.setUsername(branch.equals("registerEmpty") ? " " : "alice");
        var password = new com.aliyun.autowonder.user.dto.ChangePasswordRequest();
        password.setOldPassword(branch.equals("oldEmpty") ? " " : "old");
        password.setNewPassword(branch.equals("newEmpty") ? " " : "new");
        if (branch.equals("registerDuplicate")) when(userDao.findByUsername("alice")).thenReturn(existingUser());
        if (branch.equals("oldWrong") || branch.equals("emailDuplicate")) {
            when(userDao.findById(USER_ID)).thenReturn(existingUser());
        }
        if (branch.equals("emailDuplicate")) {
            doThrow(new org.springframework.dao.DuplicateKeyException("raw database detail"))
                    .when(userDao).updateProfile(eq(USER_ID), any(), any(), any());
        }
        BizException error = assertThrows(BizException.class, () -> {
            switch (branch) {
                case "registerEmpty", "registerDuplicate" -> service.register(register);
                case "oldEmpty", "newEmpty", "passwordUserMissing", "oldWrong" -> service.changePassword(USER_ID, password);
                case "getUserMissing" -> service.getProfile(USER_ID);
                case "requestMissing" -> service.updateProfile(USER_ID, null);
                case "nicknameEmpty" -> service.updateProfile(USER_ID, request(" ", null, null));
                case "nicknameLong" -> service.updateProfile(USER_ID, request("长".repeat(65), null, null));
                case "emailLong" -> service.updateProfile(USER_ID, request("爱丽丝", "x".repeat(129), null));
                case "emailInvalid" -> service.updateProfile(USER_ID, request("爱丽丝", "<b>bad</b>", null));
                case "phoneLong" -> service.updateProfile(USER_ID, request("爱丽丝", null, "1".repeat(33)));
                case "phoneInvalid" -> service.updateProfile(USER_ID, request("爱丽丝", null, "<b>123</b>"));
                case "updateUserMissing", "emailDuplicate" -> service.updateProfile(USER_ID, request("爱丽丝", "taken@example.com", null));
                default -> throw new AssertionError(branch);
            }
        });
        assertEquals(BizException.class, error.getClass());
        assertEquals(code, error.getCode());
        assertEquals(diagnostic, error.getMessage());
        assertEquals(diagnostic, error.getLocalizedMessage());
        assertNull(error.getCause());
        verify(userDao, never()).insert(any());
        verify(userDao, never()).updatePasswordHash(anyLong(), any());
        if (!branch.equals("emailDuplicate")) verify(userDao, never()).updateProfile(anyLong(), any(), any(), any());
        int status = code.equals("10001") ? 400 : code.equals("10401") ? 401 : code.equals("10409") ? 409 : 200;
        assertEquals(status, new com.aliyun.autowonder.common.web.GlobalExceptionHandler().handleBiz(error).getStatusCodeValue());
        assertEquals(diagnostic, com.aliyun.autowonder.common.i18n.SystemMessages.render(
                error.getMessageKey(), error.getMessageArgs(), error.getMessage(), java.util.Locale.CHINA));
        assertEquals(english, com.aliyun.autowonder.common.i18n.SystemMessages.render(
                error.getMessageKey(), error.getMessageArgs(), error.getMessage(), java.util.Locale.US));
    }

}
