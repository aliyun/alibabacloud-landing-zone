package com.aliyun.autowonder.access;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.user.UserDao;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SystemAdminServiceTest {

    @Test
    void allowsFirstActiveUser() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.findFirstActiveUserId()).thenReturn(10000L);
        SystemAdminService service = new SystemAdminService(userDao);

        assertTrue(service.isFirstActiveUser(10000L));
        assertDoesNotThrow(() -> service.requireFirstActiveUser(10000L, "更新平台品牌配置"));
    }

    @Test
    void rejectsNonFirstActiveUser() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.findFirstActiveUserId()).thenReturn(10000L);
        SystemAdminService service = new SystemAdminService(userDao);

        assertFalse(service.isFirstActiveUser(10001L));
        BizException error = assertThrows(BizException.class,
                () -> service.requireFirstActiveUser(10001L, "更新平台品牌配置"));
        assertTrue(error.getMessage().contains("仅系统第一个用户可以管理品牌配置"));
    }

    @Test
    void rejectsWhenNoActiveUserExists() {
        UserDao userDao = mock(UserDao.class);
        when(userDao.findFirstActiveUserId()).thenReturn(null);
        SystemAdminService service = new SystemAdminService(userDao);

        assertFalse(service.isFirstActiveUser(10000L));
        assertThrows(BizException.class,
                () -> service.requireFirstActiveUser(10000L, "更新平台品牌配置"));
    }
}
