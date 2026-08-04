package com.aliyun.autowonder.user;

import com.aliyun.autowonder.common.error.BizException;

import com.aliyun.autowonder.user.dto.RegisterRequest;
import com.aliyun.autowonder.user.dto.UserVO;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserServiceRegisterTest {

    private UserService newService(UserDao dao) {
        return new UserService(dao, null, null, null);
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
    }
}
