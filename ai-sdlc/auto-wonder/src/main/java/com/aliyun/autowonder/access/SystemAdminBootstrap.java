package com.aliyun.autowonder.access;

import com.aliyun.autowonder.user.UserDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs the one-shot platform-admin init migration at startup and reports — never heals —
 * a database that still has no platform admin afterwards.
 *
 * <p>The marker row written by {@link SystemAdminService#ensurePlatformAdminInitialized}
 * guarantees the promotion runs at most once per database, so restarts and repeated
 * upgrades cannot restore a first user whose admin flag was revoked. A database whose
 * migration already completed but holds zero admins (manual SQL, restore) is an anomaly:
 * it is logged as an error for an operator to fix explicitly instead of being masked by
 * re-promoting some user at runtime.
 */
@Component
public class SystemAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SystemAdminBootstrap.class);

    private final SystemAdminService systemAdminService;
    private final UserDao userDao;

    public SystemAdminBootstrap(SystemAdminService systemAdminService, UserDao userDao) {
        this.systemAdminService = systemAdminService;
        this.userDao = userDao;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (systemAdminService.ensurePlatformAdminInitialized()) {
                log.info("Platform admin init migration promoted the first active user");
            }
        } catch (RuntimeException exception) {
            log.error("Platform admin init migration failed; no platform admin was granted. "
                    + "An administrator must review recovery; Community V067 only records initialization", exception);
            return;
        }
        try {
            if (userDao.countSystemAdmins() == 0) {
                log.error("No platform admin is configured and the one-shot init migration has "
                        + "already completed. Grant one manually via the user.is_admin flag; "
                        + "first-user privileges are no longer granted implicitly");
            }
        } catch (RuntimeException exception) {
            log.warn("Platform admin roster check skipped", exception);
        }
    }
}
