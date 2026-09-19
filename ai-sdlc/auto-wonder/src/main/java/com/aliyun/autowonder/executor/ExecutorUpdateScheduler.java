package com.aliyun.autowonder.executor;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodic upgrade sweep. Runs on every node.
 *
 * <p>{@code listDeliverable} is a plain SELECT, so two nodes can read the same lapsed row; there is no
 * atomic claim. What keeps that safe is downstream: every terminal transition is guarded by the active
 * status predicate, so a task can never be closed twice, and the client dedupes a repeated command by
 * {@code requestId}, so a duplicate delivery costs one extra frame rather than a second upgrade. Two
 * nodes failing the same attempt concurrently do consume the retry budget faster than intended, which a
 * lease-based claim (UPDATE ... WHERE next_attempt_at &lt;= now, judged by affected rows) would fix.
 */
@Component
public class ExecutorUpdateScheduler {

    private final ExecutorUpdateService service;

    public ExecutorUpdateScheduler(ExecutorUpdateService service) {
        this.service = service;
    }

    @Scheduled(fixedDelayString = "${autowonder.executor-update.scan-fixed-delay-ms:60000}")
    public void scanUpgrades() {
        service.scanAndDeliver();
    }
}
