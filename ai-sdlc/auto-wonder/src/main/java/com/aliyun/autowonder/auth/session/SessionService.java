package com.aliyun.autowonder.auth.session;

import com.aliyun.autowonder.redis.RedisManager;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class SessionService {
    private static final String REFRESH_PREFIX = "auth:refresh:";
    private static final String RENEW_PREFIX = "auth:refresh:renew:";
    private static final String BLACKLIST_PREFIX = "jwt:blacklist:";

    /** 同一刷新会话最多每 2 小时顺延一次。 */
    public static final int RENEW_INTERVAL_SECONDS = 2 * 60 * 60;

    // 会话存在 + 2h 顺延标记 NX 成功才续期：登出/撤销后 refresh 键已删除，脚本直接放弃，
    // 不会复活会话；并发的前台续期只有第一个能设上标记，其余保持原到期时间。
    private static final String EXTEND_SCRIPT =
            "if redis.call('exists', KEYS[1]) == 0 then return 0 end "
            + "if not redis.call('set', KEYS[2], '1', 'NX', 'EX', ARGV[1]) then return 2 end "
            + "redis.call('expire', KEYS[1], ARGV[2]) "
            + "return 1";

    private final RedisManager redisManager;

    public SessionService(RedisManager redisManager) {
        this.redisManager = redisManager;
    }

    public enum RenewalOutcome {
        /** 会话已不存在（过期或撤销），调用方必须按未登录处理。 */
        SESSION_MISSING,
        /** 本次续期把会话到期时间顺延到 ttlSeconds 之后。 */
        EXTENDED,
        /** 距离上次顺延不足 2 小时，本次只换发访问令牌。 */
        THROTTLED
    }

    public void storeRefresh(String refreshToken, long userId, int ttlSeconds) {
        redisManager.set(REFRESH_PREFIX + refreshToken, userId, ttlSeconds);
    }

    public Long getUserIdByRefresh(String refreshToken) {
        return redisManager.get(REFRESH_PREFIX + refreshToken);
    }

    /**
     * 前台实际使用触发的续期：仅当刷新会话仍存在、且距上次顺延已满 2 小时时，
     * 才把会话到期时间顺延到当前时间加 {@code ttlSeconds}。
     */
    public RenewalOutcome extendRefreshSession(String refreshToken, int ttlSeconds) {
        Object result = redisManager.eval(EXTEND_SCRIPT,
                List.of(REFRESH_PREFIX + refreshToken, RENEW_PREFIX + refreshToken),
                List.of(String.valueOf(RENEW_INTERVAL_SECONDS), String.valueOf(ttlSeconds)));
        long code = result instanceof Number ? ((Number) result).longValue() : 0L;
        if (code == 1L) {
            return RenewalOutcome.EXTENDED;
        }
        if (code == 2L) {
            return RenewalOutcome.THROTTLED;
        }
        return RenewalOutcome.SESSION_MISSING;
    }

    public void revokeRefresh(String refreshToken) {
        // 先删会话再删标记：任何并发前台续期要么看到会话已消失而放弃，要么先续期再被删除，
        // 两种情况都不会让已登出的会话复活。
        redisManager.del(REFRESH_PREFIX + refreshToken);
        redisManager.del(RENEW_PREFIX + refreshToken);
    }

    public void blacklistJti(String jti, int ttlSeconds) {
        redisManager.set(BLACKLIST_PREFIX + jti, "1", ttlSeconds);
    }

    public boolean isBlacklisted(String jti) {
        return redisManager.exists(BLACKLIST_PREFIX + jti);
    }
}
