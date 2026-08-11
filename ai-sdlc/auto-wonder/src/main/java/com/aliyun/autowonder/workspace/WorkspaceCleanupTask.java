package com.aliyun.autowonder.workspace;

import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.websocket.PresenceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Component
public class WorkspaceCleanupTask {

    static final String PROTOCOL_FEATURE = "WORKITEM_WORKSPACE_CLEANUP";
    private static final Logger log = LoggerFactory.getLogger(WorkspaceCleanupTask.class);
    private static final String LOCK_KEY = "workspace:cleanup:sweep:lock";
    private static final long LOCK_TTL_MS = 60_000L;
    private static final long SENT_MARKER_TTL_SECONDS = 3_600L;

    private final WorkspaceCleanupDao dao;
    private final WorkspaceCleanupTransport transport;
    private final PresenceManager presence;
    private final RedisManager redis;
    private final Duration retention;
    private final int batchSize;

    public WorkspaceCleanupTask(WorkspaceCleanupDao dao, WorkspaceCleanupTransport transport,
            PresenceManager presence, RedisManager redis,
            @Value("${autowonder.workspace-cleanup.retention:3d}") Duration retention,
            @Value("${autowonder.workspace-cleanup.batch-size:200}") int batchSize) {
        this.dao = dao;
        this.transport = transport;
        this.presence = presence;
        this.redis = redis;
        this.retention = retention;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${autowonder.workspace-cleanup.fixed-delay-ms:3600000}")
    public void sweep() {
        String owner = UUID.randomUUID().toString();
        if (!redis.tryAcquireLock(LOCK_KEY, owner, LOCK_TTL_MS)) {
            return;
        }
        try {
            Date cutoff = new Date(System.currentTimeMillis() - retention.toMillis());
            List<WorkspaceCleanupCandidate> candidates = dao.listEligible(cutoff, batchSize);
            if (candidates == null) {
                return;
            }
            for (WorkspaceCleanupCandidate candidate : candidates) {
                deliver(candidate);
            }
        } finally {
            redis.releaseLock(LOCK_KEY, owner);
        }
    }

    private void deliver(WorkspaceCleanupCandidate candidate) {
        if (candidate == null || candidate.getExecutorId() == null
                || candidate.getWorkitemId() == null || candidate.getTenantId() == null
                || candidate.getWorkitemVersion() == null || candidate.getPublishedAt() == null) {
            return;
        }
        long executorId = candidate.getExecutorId();
        if (!presence.isExecutorOnline(executorId)
                || !presence.supportsProtocolFeature(executorId, PROTOCOL_FEATURE)) {
            return;
        }
        String marker = sentMarker(candidate);
        if (redis.exists(marker)) {
            return;
        }
        try {
            transport.send(candidate);
            redis.setWithExpire(marker, "1", SENT_MARKER_TTL_SECONDS);
            log.info("workspace cleanup requested tenantId={} workitemId={} executorId={} version={} publishedAt={}",
                    candidate.getTenantId(), candidate.getWorkitemId(), executorId,
                    candidate.getWorkitemVersion(), candidate.getPublishedAt());
        } catch (RuntimeException e) {
            log.warn("workspace cleanup request failed tenantId={} workitemId={} executorId={}",
                    candidate.getTenantId(), candidate.getWorkitemId(), executorId, e);
        }
    }

    private static String sentMarker(WorkspaceCleanupCandidate candidate) {
        return "workspace:cleanup:sent:" + candidate.getExecutorId() + ":"
                + candidate.getWorkitemId() + ":" + candidate.getWorkitemVersion();
    }
}
