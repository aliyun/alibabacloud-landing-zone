package com.aliyun.autowonder.agent;

import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.workspace.WorkspaceDO;
import com.aliyun.autowonder.workspace.WorkspaceDao;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** 启动时为存量工作空间补齐平台数字人；Redis 全局锁保证多副本部署只有一个实例执行。 */
@Component
public class PlatformAgentBackfillTask implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformAgentBackfillTask.class);

    static final String LOCK_KEY = "aw:platform-agent-backfill-lock";
    static final long LOCK_TTL_MILLIS = 10 * 60 * 1000L;
    private static final int PAGE_SIZE = 200;

    private final WorkspaceDao workspaceDao;
    private final PlatformAgentSeeder seeder;
    private final RedisManager redisManager;

    public PlatformAgentBackfillTask(WorkspaceDao workspaceDao, PlatformAgentSeeder seeder,
                                     RedisManager redisManager) {
        this.workspaceDao = workspaceDao;
        this.seeder = seeder;
        this.redisManager = redisManager;
    }

    @Override
    public void run(ApplicationArguments args) {
        String owner = UUID.randomUUID().toString();
        boolean locked;
        try {
            locked = redisManager.tryAcquireLock(LOCK_KEY, owner, LOCK_TTL_MILLIS);
        } catch (RuntimeException ex) {
            log.warn("platform agent backfill skipped: redis lock unavailable", ex);
            return;
        }
        if (!locked) {
            log.info("platform agent backfill skipped: another instance holds the lock");
            return;
        }
        int scanned = 0;
        int seeded = 0;
        int failed = 0;
        try {
            int offset = 0;
            while (true) {
                List<WorkspaceDO> page = workspaceDao.listAllPaged(null, offset, PAGE_SIZE);
                if (page == null || page.isEmpty()) {
                    break;
                }
                for (WorkspaceDO ws : page) {
                    scanned++;
                    try {
                        long creator = ws.getOwnerId() == null ? 0L : ws.getOwnerId();
                        if (seeder.seed(ws.getId(), creator)) {
                            seeded++;
                        }
                    } catch (RuntimeException ex) {
                        failed++;
                        log.error("platform agent backfill failed for workspace {}", ws.getId(), ex);
                    }
                }
                offset += page.size();
                if (page.size() < PAGE_SIZE) {
                    break;
                }
            }
        } finally {
            redisManager.releaseLock(LOCK_KEY, owner);
        }
        log.info("platform agent backfill done: scanned={} seeded={} failed={}", scanned, seeded, failed);
    }
}
