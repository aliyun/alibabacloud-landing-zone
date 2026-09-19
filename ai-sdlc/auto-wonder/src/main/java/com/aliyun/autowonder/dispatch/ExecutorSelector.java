package com.aliyun.autowonder.dispatch;

import com.aliyun.autowonder.executor.ExecutorRegistry;
import com.aliyun.autowonder.redis.RedisManager;
import com.aliyun.autowonder.websocket.PresenceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;

@Component
public class ExecutorSelector {

    private static final Logger log = LoggerFactory.getLogger(ExecutorSelector.class);
    private static final long ROUND_ROBIN_TTL_SECONDS = 7 * 24 * 60 * 60L;

    private final RedisManager redisManager;
    private final ExecutorRegistry executorRegistry;
    private final PresenceManager presenceManager;
    private final DispatchDao dispatchDao;
    public ExecutorSelector(RedisManager redisManager, ExecutorRegistry executorRegistry,
            PresenceManager presenceManager, DispatchDao dispatchDao) {
        this.redisManager = redisManager;
        this.executorRegistry = executorRegistry;
        this.presenceManager = presenceManager;
        this.dispatchDao = dispatchDao;
    }

    public static String execsKey(long agentId) {
        return "agent:execs:" + agentId;
    }

    static String roundRobinKey(long agentId) {
        return "agent:executor-round-robin:" + agentId;
    }

    public Long select(long agentId) {
        return select(agentId, null);
    }

    public Long select(long agentId, Long preferredExecutorId) {
        return select(agentId, preferredExecutorId, false, null);
    }

    public Long select(long agentId, Long preferredExecutorId, String requiredFeature) {
        return select(agentId, preferredExecutorId, false, requiredFeature);
    }

    public Long selectForInteraction(long agentId, Long preferredExecutorId) {
        return select(agentId, preferredExecutorId, true, null);
    }

    public Long selectForInteraction(long agentId, Long preferredExecutorId,
            String requiredFeature) {
        return select(agentId, preferredExecutorId, true, requiredFeature);
    }

    /** Explains a failed selection without changing the scheduling cursor. */
    public DispatchWaitingReason unavailableReason(long agentId) {
        try {
            Set<String> members = redisManager.smembers(execsKey(agentId));
            // agent:execs is an online-presence set: graceful disconnect removes the member.
            // Empty therefore means offline, never proof of permanent missing configuration.
            if (members == null || members.isEmpty()) {
                return presenceManager.currentAgentProtocolError(agentId) == null
                        ? DispatchWaitingReason.NO_EXECUTOR_ONLINE
                        : DispatchWaitingReason.RUNTIME_INCOMPATIBLE;
            }
            boolean online = false;
            boolean recovering = false;
            boolean compatible = false;
            for (String member : members) {
                try {
                    long id = Long.parseLong(member);
                    boolean available = executorRegistry.isAvailable(id);
                    if (available || executorRegistry.isOnline(id)) online = true;
                    if (!available) continue;
                    var snapshot = executorRegistry.currentDispatchSnapshot(id);
                    if (snapshot.isEmpty()) recovering = true;
                    else if (snapshot.get().authoritativeInventory()
                            && (!snapshot.get().inventoryReady()
                                    || snapshot.get().inventoryError() != null)) recovering = true;
                    else compatible = true;
                } catch (NumberFormatException ignored) { }
            }
            if (!online) {
                return presenceManager.currentAgentProtocolError(agentId) == null
                        ? DispatchWaitingReason.NO_EXECUTOR_ONLINE
                        : DispatchWaitingReason.RUNTIME_INCOMPATIBLE;
            }
            if (recovering) return DispatchWaitingReason.EXECUTOR_RECOVERING;
            if (!compatible && presenceManager.currentAgentProtocolError(agentId) != null) {
                return DispatchWaitingReason.RUNTIME_INCOMPATIBLE;
            }
            return DispatchWaitingReason.NO_EXECUTOR_CAPACITY;
        } catch (RuntimeException e) {
            log.error("executor selection diagnosis failed agentId={}", agentId, e);
            return DispatchWaitingReason.SELECTION_INTERNAL_ERROR;
        }
    }

    /**
     * A recovery must never infer that a persisted executor id is still usable.
     * Keep that check at the same boundary as normal selection so continuous Runs
     * can wait for their original runtime without exposing Runtime internals.
     */
    public boolean isAvailable(long executorId) {
        return hasCapacity(executorId, false);
    }

    /** Read-only availability probe; does not advance the scheduling cursor or reserve capacity. */
    public boolean hasAvailableExecutor(long agentId) {
        Set<String> members = redisManager.smembers(execsKey(agentId));
        if (members == null) {
            return false;
        }
        for (String member : members) {
            try {
                if (hasCapacity(Long.parseLong(member), false)) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // Match selection's handling of malformed members.
            }
        }
        return false;
    }

    /** Select only the requested executor. Continuous native sessions cannot fail over. */
    public Long selectStrict(long agentId, long executorId) {
        return selectStrict(agentId, executorId, null);
    }

    public Long selectStrict(long agentId, long executorId, String requiredFeature) {
        Set<String> members = redisManager.smembers(execsKey(agentId));
        if (members == null || !members.contains(String.valueOf(executorId))
                || !isRuntimeAvailable(executorId)) {
            return null;
        }
        requireFeature(executorId, requiredFeature);
        return hasRemainingCapacity(executorId, false) ? executorId : null;
    }

    private Long select(long agentId, Long preferredExecutorId, boolean interaction,
            String requiredFeature) {
        Set<String> members = redisManager.smembers(execsKey(agentId));
        if (members == null || members.isEmpty()) {
            log.info("executor select none agentId={} (no members)", agentId);
            return null;
        }
        List<Long> ids = new ArrayList<>();
        for (String m : members) {
            try {
                ids.add(Long.parseLong(m));
            } catch (NumberFormatException ignore) {
                // skip malformed member
            }
        }
        log.info("executor select agentId={} candidates={}", agentId, ids.size());
        Collections.sort(ids);
        boolean supportingRuntimeExists = false;
        boolean unsupportedWithCapacity = false;
        if (preferredExecutorId != null && ids.contains(preferredExecutorId)
                && isRuntimeAvailable(preferredExecutorId)) {
            boolean supports = supportsFeature(preferredExecutorId, requiredFeature);
            boolean hasCapacity = hasRemainingCapacity(preferredExecutorId, interaction);
            supportingRuntimeExists = supports;
            if (hasCapacity && supports) {
                log.info("executor selected preferred agentId={} executorId={}",
                        agentId, preferredExecutorId);
                return preferredExecutorId;
            }
            unsupportedWithCapacity = !supports && hasCapacity;
        }
        List<Long> eligible = new ArrayList<>();
        for (Long id : ids) {
            if (id.equals(preferredExecutorId)) {
                continue;
            }
            if (!isRuntimeAvailable(id)) {
                continue;
            }
            boolean supports = supportsFeature(id, requiredFeature);
            supportingRuntimeExists |= supports;
            if (!hasRemainingCapacity(id, interaction)) {
                continue;
            }
            if (!supports) {
                unsupportedWithCapacity = true;
                continue;
            }
            eligible.add(id);
        }
        if (!eligible.isEmpty()) {
            long sequence;
            try {
                sequence = redisManager.exIncrBy(roundRobinKey(agentId), 1L, ROUND_ROBIN_TTL_SECONDS);
            } catch (RuntimeException e) {
                log.warn("executor round-robin cursor unavailable agentId={}; using first eligible", agentId, e);
                sequence = 1L;
            }
            int index = sequence > 0 ? (int) ((sequence - 1) % eligible.size()) : 0;
            Long selected = eligible.get(index);
            log.info("executor selected round-robin agentId={} executorId={} sequence={} eligible={}",
                    agentId, selected, sequence, eligible.size());
            return selected;
        }
        if (!supportingRuntimeExists && unsupportedWithCapacity) {
            throw new ExecutorProtocolCompatibilityException(requiredFeature);
        }
        log.info("executor select none agentId={} (none online)", agentId);
        return null;
    }

    private boolean supportsFeature(long executorId, String requiredFeature) {
        return requiredFeature == null
                || presenceManager.supportsProtocolFeature(executorId, requiredFeature);
    }

    private void requireFeature(long executorId, String requiredFeature) {
        if (!supportsFeature(executorId, requiredFeature)) {
            throw new ExecutorProtocolCompatibilityException(requiredFeature);
        }
    }

    private boolean hasCapacity(long executorId, boolean interaction) {
        if (!isRuntimeAvailable(executorId)) {
            return false;
        }
        return hasRemainingCapacity(executorId, interaction);
    }

    private boolean isRuntimeAvailable(long executorId) {
        return executorRegistry.isAvailable(executorId);
    }

    private boolean hasRemainingCapacity(long executorId, boolean interaction) {
        var snapshot = executorRegistry.currentDispatchSnapshot(executorId);
        if (snapshot.isEmpty()) {
            return false;
        }
        if (snapshot.get().authoritativeInventory() && (!snapshot.get().inventoryReady()
                || snapshot.get().inventoryError() != null)) {
            return false;
        }
        int capacity = snapshot.get().capacity();
        int capacityLimit = capacityLimit(capacity, interaction);
        long active = usedCapacity(executorId, snapshot.get());
        return capacityLimit > 0 && active < capacityLimit;
    }

    private long usedCapacity(long executorId,
            com.aliyun.autowonder.executor.ExecutorDispatchSnapshot snapshot) {
        Set<Long> occupied = new LinkedHashSet<>();
        List<Long> persisted = dispatchDao.listCapacityOccupyingIds(executorId);
        if (persisted != null) {
            occupied.addAll(persisted);
        }
        occupied.addAll(snapshot.runningDispatchIds());
        return occupied.size() + snapshot.runningConversationTurnIds().size();
    }

    private int capacityLimit(int capacity, boolean interaction) {
        if (interaction || capacity <= 1) {
            return capacity;
        }
        return capacity - 1;
    }
}
