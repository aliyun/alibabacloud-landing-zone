package com.aliyun.autowonder.access;

import com.aliyun.autowonder.user.UserDao;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SystemAdminBootstrapTest {

    @Test
    void aFailedMigrationStopsBeforeAnyRosterCheck() {
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        UserDao userDao = mock(UserDao.class);
        when(systemAdminService.ensurePlatformAdminInitialized())
                .thenThrow(new IllegalStateException("platform_admin_init is missing"));
        SystemAdminBootstrap bootstrap = new SystemAdminBootstrap(systemAdminService, userDao);

        assertDoesNotThrow(() -> bootstrap.run(new DefaultApplicationArguments()));

        // Startup must never degrade into a second, silent migration attempt.
        verifyNoInteractions(userDao);
    }

    @Test
    void reportsAZeroAdminRosterInsteadOfHealingIt() {
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        UserDao userDao = mock(UserDao.class);
        when(systemAdminService.ensurePlatformAdminInitialized()).thenReturn(false);
        when(userDao.countSystemAdmins()).thenReturn(0L);
        SystemAdminBootstrap bootstrap = new SystemAdminBootstrap(systemAdminService, userDao);

        assertDoesNotThrow(() -> bootstrap.run(new DefaultApplicationArguments()));

        // The zero-admin state after a completed migration is reported for an operator to fix;
        // the bootstrap performs no promotion of its own.
        verify(userDao).countSystemAdmins();
        verify(systemAdminService, never()).ensureSystemAdmin();
    }

    @Test
    void aHealthyRosterEndsQuietly() {
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        UserDao userDao = mock(UserDao.class);
        when(systemAdminService.ensurePlatformAdminInitialized()).thenReturn(true);
        when(userDao.countSystemAdmins()).thenReturn(1L);
        SystemAdminBootstrap bootstrap = new SystemAdminBootstrap(systemAdminService, userDao);

        assertDoesNotThrow(() -> bootstrap.run(new DefaultApplicationArguments()));

        verify(userDao).countSystemAdmins();
    }

    @Test
    void aFailingRosterCheckDoesNotBreakStartup() {
        SystemAdminService systemAdminService = mock(SystemAdminService.class);
        UserDao userDao = mock(UserDao.class);
        when(systemAdminService.ensurePlatformAdminInitialized()).thenReturn(false);
        when(userDao.countSystemAdmins()).thenThrow(new IllegalStateException("database unavailable"));
        SystemAdminBootstrap bootstrap = new SystemAdminBootstrap(systemAdminService, userDao);

        assertDoesNotThrow(() -> bootstrap.run(new DefaultApplicationArguments()));
    }
}
