package com.aliyun.autowonder.im;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

/**
 * Cross-instance send window shared with the platform group-chat config update. Senders run their
 * final route check and the external call inside one transaction that holds the config row lock,
 * so a committed identity change can never interleave with an in-flight send. The lock wait is
 * bounded; a config save that cannot get the lock in time fails and keeps the stored config.
 */
@Service
public class ImDeliveryFence {
    private static final Logger log = LoggerFactory.getLogger(ImDeliveryFence.class);
    static final int LOCK_WAIT_SECONDS = 10;
    private static final String READ_TIMEOUT_SQL = "SELECT @@SESSION.innodb_lock_wait_timeout";
    private static final String SET_TIMEOUT_SQL = "SET SESSION innodb_lock_wait_timeout = ";

    private final TransactionTemplate template;
    private final JdbcTemplate jdbc;

    @Autowired
    public ImDeliveryFence(PlatformTransactionManager transactionManager, JdbcTemplate jdbc) {
        TransactionTemplate configured = new TransactionTemplate(transactionManager);
        configured.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.template = configured;
        this.jdbc = jdbc;
    }

    public <T> T run(Supplier<T> work) {
        return template.execute(status -> {
            Integer previous = currentLockWaitSeconds();
            applyLockWaitSeconds(LOCK_WAIT_SECONDS);
            try {
                return work.get();
            } finally {
                applyLockWaitSeconds(previous == null ? 50 : previous);
            }
        });
    }

    private Integer currentLockWaitSeconds() {
        try {
            return jdbc.queryForObject(READ_TIMEOUT_SQL, Integer.class);
        } catch (RuntimeException e) {
            // Non-MySQL databases (test fixtures) have no such variable; keep their own bounds.
            return null;
        }
    }

    private void applyLockWaitSeconds(int seconds) {
        try {
            jdbc.execute(SET_TIMEOUT_SQL + seconds);
        } catch (RuntimeException e) {
            log.debug("delivery fence lock wait not configurable: {}", e.getClass().getSimpleName());
        }
    }
}
