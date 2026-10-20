package com.aliyun.autowonder.user;

import com.aliyun.autowonder.access.SystemAdminService;
import com.aliyun.autowonder.auth.session.SessionIssuanceService;
import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.i18n.SystemMessages;
import com.aliyun.autowonder.user.dto.RegisterRequest;
import com.aliyun.autowonder.user.dto.UserVO;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceRegisterTest {

    private UserService newService(UserDao dao) {
        return new UserService(dao, null, null, null, null, mock(SystemAdminService.class),
                new SessionIssuanceService(null, null, null));
    }

    @Test
    void registersNewUser() {
        UserDao dao = mock(UserDao.class);
        when(dao.findByUsername("alice")).thenReturn(null);
        doAnswer(inv -> { ((UserDO) inv.getArgument(0)).setId(1L); return null; })
                .when(dao).insert(any());
        UserService svc = newService(dao);

        RegisterRequest req = new RegisterRequest();
        req.setUsername("alice");
        req.setPassword("pw123456");
        req.setNickname("Alice");

        UserVO vo = svc.register(req);

        assertEquals(1L, vo.getId());
        assertEquals("alice", vo.getUsername());
        verify(dao).insert(argThat(u ->
                "alice".equals(u.getUsername())
                        && u.getPasswordHash() != null
                        && !"pw123456".equals(u.getPasswordHash())));
    }

    @Test
    void rejectsDuplicateUsername() {
        UserDao dao = mock(UserDao.class);
        UserDO existing = new UserDO();
        existing.setId(1L);
        existing.setUsername("alice");
        when(dao.findByUsername("alice")).thenReturn(existing);
        UserService svc = newService(dao);

        RegisterRequest req = new RegisterRequest();
        req.setUsername("alice");
        req.setPassword("pw123456");

        BizException ex = assertThrows(BizException.class, () -> svc.register(req));
        assertEquals("10409", ex.getCode());
        assertEquals("user.username_exists", ex.getMessageKey());
    }

    @Test
    void rejectsTheReservedTombstoneNamespaceInEveryCasingAndAccentVariant() {
        // Logically deleted rows keep a rewritten username in the #deleted-{id} namespace so the
        // unique key stays free. uk_username inherits the database default collation
        // (case/accent-insensitive), so a case-sensitive prefix check would let #DELETED-{id} or
        // #déleted-{id} squat on a tombstone value and make the later delete collide.
        for (String username : new String[]{
                "#deleted-42", "#DELETED-42", "#Deleted-42", "#déleted-42", "#42"}) {
            UserDao dao = mock(UserDao.class);
            UserService svc = newService(dao);

            RegisterRequest req = new RegisterRequest();
            req.setUsername(username);
            req.setPassword("pw123456");

            BizException ex = assertThrows(BizException.class, () -> svc.register(req), username);
            assertEquals("37008", ex.getCode(), username);
            verify(dao, never()).findByUsername(anyString());
            verify(dao, never()).insert(any());
        }
    }

    @Test
    void stillAllowsHashCharactersOutsideTheReservedLeadingNamespace() {
        UserDao dao = mock(UserDao.class);
        when(dao.findByUsername("alice#1")).thenReturn(null);
        doAnswer(inv -> { ((UserDO) inv.getArgument(0)).setId(1L); return null; })
                .when(dao).insert(any());
        UserService svc = newService(dao);

        RegisterRequest req = new RegisterRequest();
        req.setUsername("alice#1");
        req.setPassword("pw123456");

        assertEquals("alice#1", svc.register(req).getUsername());
        verify(dao).insert(argThat(u -> "alice#1".equals(u.getUsername())));
    }

    @Test
    void rejectsDuplicateEmailOnInsertConflict() {
        UserDao dao = mock(UserDao.class);
        when(dao.findByUsername("bob")).thenReturn(null);
        when(dao.insert(any())).thenThrow(new DuplicateKeyException(
                "Duplicate entry 'bob@example.com' for key 'user.uk_email'"));
        UserService svc = newService(dao);

        RegisterRequest req = new RegisterRequest();
        req.setUsername("bob");
        req.setPassword("pw123456");
        req.setNickname("Bob");
        req.setEmail("bob@example.com");

        BizException ex = assertThrows(BizException.class, () -> svc.register(req));
        assertEquals("10409", ex.getCode());
        assertEquals("user.email_in_use", ex.getMessageKey());
    }

    @Test
    void duplicateEmailHintRendersInRequestedLocale() {
        UserDao dao = mock(UserDao.class);
        when(dao.findByUsername("bob")).thenReturn(null);
        when(dao.insert(any())).thenThrow(new DuplicateKeyException(
                "Duplicate entry 'bob@example.com' for key 'user.uk_email'"));
        UserService svc = newService(dao);

        RegisterRequest req = new RegisterRequest();
        req.setUsername("bob");
        req.setPassword("pw123456");
        req.setEmail("bob@example.com");

        BizException ex = assertThrows(BizException.class, () -> svc.register(req));

        assertEquals("邮箱已被使用", SystemMessages.render(
                ex.getMessageKey(), ex.getMessageArgs(), ex.getMessage(), Locale.CHINA));
        assertEquals("Email is already in use.", SystemMessages.render(
                ex.getMessageKey(), ex.getMessageArgs(), ex.getMessage(), Locale.US));
    }

    @Test
    void otherIntegrityViolationsStillPropagate() {
        UserDao dao = mock(UserDao.class);
        when(dao.findByUsername("bob")).thenReturn(null);
        DataIntegrityViolationException notNullViolation =
                new DataIntegrityViolationException("Column 'nickname' cannot be null");
        when(dao.insert(any())).thenThrow(notNullViolation);
        UserService svc = newService(dao);

        RegisterRequest req = new RegisterRequest();
        req.setUsername("bob");
        req.setPassword("pw123456");

        DataIntegrityViolationException thrown = assertThrows(
                DataIntegrityViolationException.class, () -> svc.register(req));
        assertSame(notNullViolation, thrown);
    }
}
