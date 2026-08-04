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
        return select(agentId, preferredExecutorId, false);
    }

    public Long selectForInteraction(long agentId, Long preferredExecutorId) {
        return select(agentId, preferredExecutorId, true);
    }

    private Long select(long agentId, Long preferredExecutorId, boolean interaction) {
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
        if (preferredExecutorId != null && ids.contains(preferredExecutorId)
                && hasCapacity(preferredExecutorId, interaction)) {
            log.info("executor selected preferred agentId={} executorId={}", agentId, preferredExecutorId);
            return preferredExecutorId;
        }
        List<Long> eligible = new ArrayList<>();
        for (Long id : ids) {
            if (id.equals(preferredExecutorId)) {
                continue;
            }
            if (!executorRegistry.isAvailable(id)) {
                continue;
            }
            int capacity = presenceManager.capacity(id);
            int capacityLimit = capacityLimit(capacity, interaction);
            long active = dispatchDao.countActiveByExecutor(id);
            if (active >= capacityLimit) {
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
        log.info("executor select none agentId={} (none online)", agentId);
        return null;
    }

    private boolean hasCapacity(long executorId, boolean interaction) {
        if (!executorRegistry.isAvailable(executorId)) {
            return false;
        }
        int capacity = presenceManager.capacity(executorId);
        int capacityLimit = capacityLimit(capacity, interaction);
        return capacityLimit > 0 && dispatchDao.countActiveByExecutor(executorId) < capacityLimit;
    }

    private int capacityLimit(int capacity, boolean interaction) {
        if (interaction || capacity <= 1) {
            return capacity;
        }
        return capacity - 1;
    }
}
