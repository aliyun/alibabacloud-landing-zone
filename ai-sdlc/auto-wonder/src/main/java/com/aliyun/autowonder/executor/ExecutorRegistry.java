package com.aliyun.autowonder.executor;

import com.aliyun.autowonder.redis.RedisManager;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

@Component
public class ExecutorRegistry {

    /**
     * Only fence the executor long enough for the current failover dispatch to pick
     * another online runtime. This is not a provider health cooldown: the executor
     * is eligible again on the next compensation round.
     */
    private static final long FAILOVER_MARKER_SECONDS = 1L;
    private static final String FAILOVER_MARKER_PREFIX = "failover:";
    private static final int RUNNING_DISPATCH_TTL_SECONDS = 60;
    static final long TOMBSTONE_TTL_SECONDS = 24 * 60 * 60L;

    private final RedisManager redisManager;

    public ExecutorRegistry(RedisManager redisManager) {
        this.redisManager = redisManager;
    }

    public static String onlineKey(long executorId) {
        return "exec:online:" + executorId;
    }

    public static String providerCooldownKey(long executorId) {
        return "exec:provider-cooldown:" + executorId;
    }

    public static String deletedKey(long executorId) {
        return "exec:deleted:" + executorId;
    }

    public boolean isDeleted(long executorId) {
        try {
            return redisManager.exists(deletedKey(executorId));
        } catch (Exception e) {
            return false;
        }
    }

    public static String runningDispatchesKey(long executorId) {
        return "exec:running-dispatches:" + executorId;
    }

    public void updateRunningDispatches(long executorId, List<Long> dispatchIds) {
        ArrayList<Long> bounded = dispatchIds == null ? new ArrayList<>()
                : new ArrayList<>(new LinkedHashSet<>(dispatchIds));
        if (bounded.size() > 50) {
            bounded = new ArrayList<>(bounded.subList(0, 50));
        }
        redisManager.set(runningDispatchesKey(executorId), bounded, RUNNING_DISPATCH_TTL_SECONDS);
    }

    public boolean isDispatchActive(long executorId, long dispatchId) {
        try {
            Object value = redisManager.get(runningDispatchesKey(executorId));
            if (!(value instanceof Collection<?> running)) {
                // Missing/unknown lease is not enough evidence to fence active work.
                return isOnline(executorId);
            }
            return running.stream().anyMatch(id -> id instanceof Number
                    && ((Number) id).longValue() == dispatchId);
        } catch (Exception e) {
            return isOnline(executorId);
        }
    }

    /** True if the executor has a live online heartbeat entry in Redis. */
    public boolean isOnline(long executorId) {
        try {
            return redisManager.exists(onlineKey(executorId));
        } catch (Exception e) {
            return false;
        }
    }

    /** Online executors are schedulable except when deleted or in a failover cooldown. */
    public boolean isAvailable(long executorId) {
        if (isDeleted(executorId)) {
            return false;
        }
        if (!isOnline(executorId)) {
            return false;
        }
        try {
            String cooldownKey = providerCooldownKey(executorId);
            if (!redisManager.exists(cooldownKey)) {
                return true;
            }
            String marker = redisManager.getString(cooldownKey);
            if (marker == null || !marker.startsWith(FAILOVER_MARKER_PREFIX)) {
                // Remove cooldowns written by older releases (up to six hours). They
                // must not keep a healthy, online runtime invisible to scheduling.
                redisManager.del(cooldownKey);
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    public void markProviderUnavailable(long executorId, String failureCategory) {
        if ("runtime_recovery".equals(failureCategory)) {
            return;
        }
        redisManager.setWithExpire(providerCooldownKey(executorId),
                FAILOVER_MARKER_PREFIX + failureCategory, FAILOVER_MARKER_SECONDS);
    }

    public void markProviderAvailable(long executorId) {
        redisManager.del(providerCooldownKey(executorId));
    }
}
