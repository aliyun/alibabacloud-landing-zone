package com.aliyun.autowonder.auth.federation;

import com.aliyun.autowonder.redis.RedisManager;
import org.springframework.stereotype.Service;

/**
 * 登录过程的一次性状态存储：发起时的 state 与回调后的票据都只能被消费一次，
 * 断言 ID 以 setNx 占用实现重放防护。
 */
@Service
public class FederationStateStore {
    static final String STATE_PREFIX = "federation:state:";
    static final String TICKET_PREFIX = "federation:ticket:";
    static final String REPLAY_PREFIX = "federation:replay:";

    private static final int SETNX_CREATED = 1;

    private final RedisManager redisManager;

    public FederationStateStore(RedisManager redisManager) {
        this.redisManager = redisManager;
    }

    public void saveLoginState(String state, FederationLoginState data, int ttlSeconds) {
        redisManager.set(STATE_PREFIX + state, data, ttlSeconds);
    }

    public FederationLoginState consumeLoginState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        return redisManager.getAndDelete(STATE_PREFIX + state);
    }

    public void saveTicket(String ticket, FederationTicket data, int ttlSeconds) {
        redisManager.set(TICKET_PREFIX + ticket, data, ttlSeconds);
    }

    public FederationTicket consumeTicket(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        return redisManager.getAndDelete(TICKET_PREFIX + ticket);
    }

    /**
     * 绑定票据按「读取 + 成功后删除」使用：绑定流程可能先要求补充凭据，
     * 凭据错误不能让用户重新跑一遍 IdP，所以失败时票据必须保留直到 TTL 到期。
     */
    public FederationTicket readTicket(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        return redisManager.get(TICKET_PREFIX + ticket);
    }

    public void deleteTicket(String ticket) {
        if (ticket != null && !ticket.isBlank()) {
            redisManager.del(TICKET_PREFIX + ticket);
        }
    }

    /** 断言重放防护：同一 provider 下同一个断言 ID 只允许占用一次。 */
    public boolean claimAssertion(Long providerId, String assertionId, int ttlSeconds) {
        if (providerId == null || assertionId == null || assertionId.isBlank()) {
            return false;
        }
        return redisManager.setNx(REPLAY_PREFIX + providerId + ":" + assertionId, "1", ttlSeconds) == SETNX_CREATED;
    }
}
