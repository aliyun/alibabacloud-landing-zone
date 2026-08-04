package com.aliyun.autowonder.redis;

import com.aliyun.autowonder.util.MessageDigestUtil;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.*;
import redis.clients.jedis.exceptions.JedisDataException;
import redis.clients.jedis.params.XAddParams;
import redis.clients.jedis.params.XAutoClaimParams;
import redis.clients.jedis.params.XPendingParams;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.params.SetParams;
import redis.clients.jedis.util.SafeEncoder;

import java.io.Serializable;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RedisManager extends AbstractRedisManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(RedisManager.class);

    @Getter
    private JedisPool jedisPool;


    public RedisManager(JedisPool jedisPool, boolean testEnterprise) {
        this.jedisPool = jedisPool;
        if (testEnterprise) {
            LOGGER.info("Redis Enterprise version check start!");
            testEnterprise(jedisPool);
        }
        LOGGER.info("Redis Enterprise version check success!");
    }

    public <T> T get(Serializable key) {
        String strKey = stringifyKey(key);
        try (Jedis jedis = jedisPool.getResource()) {
            byte[] resp = jedis.get(strKey.getBytes(UTF8));
            if (resp == null) {
                return null;
            }
            return (T) deserialize(resp);
        }
    }

    public boolean set(Serializable key, Serializable value, int expireSec) {
        String strKey = stringifyKey(key);
        int maxRetries = 3;
        int retryDelay = 100;

        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try (Jedis jedis = jedisPool.getResource()) {
                byte[] valueByte = serialize(value);
                LOGGER.debug("redis put key={}, value hash: {}, attempt: {}", key, MessageDigestUtil.getMD5(valueByte), attempt);

                String result;
                if (expireSec == -1) {
                    result = jedis.set(strKey.getBytes(UTF8), valueByte);
                } else {
                    result = jedis.set(strKey.getBytes(UTF8), valueByte, SetParams.setParams().ex(expireSec));
                }

                if (RET_OK.equals(result)) {
                    if (attempt > 1) {
                        LOGGER.info("Redis set succeeded on attempt {}: key={}", attempt, strKey);
                    }
                    return true;
                }
                LOGGER.warn("redis set failed. key={}, result={}, attempt={}", strKey, result, attempt);

            } catch (Exception e) {
                LOGGER.warn("Redis set error on attempt {}: key={}, error: {}", attempt, strKey, e.getMessage());

                if (attempt == maxRetries) {
                    LOGGER.error("Redis set failed after {} attempts: key={}", maxRetries, strKey, e);
                    return false;
                }

                try {
                    Thread.sleep(retryDelay * attempt);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    LOGGER.error("Redis retry interrupted: key={}", strKey);
                    return false;
                }
            }
        }
        return false;
    }

    public int setNx(Serializable key, Serializable value, int expireSec) {
        String strKey = stringifyKey(key);
        String result;
        try (Jedis jedis = jedisPool.getResource()) {
            byte[] valueByte = serialize(value);
            result = jedis.set(strKey.getBytes(UTF8), valueByte, SetParams.setParams().ex(expireSec).nx());

            if (result == null) {
                return ALREADY_EXIST;
            } else if (RET_OK.equals(result)) {
                return SUCCESS;
            }
            LOGGER.error("redis setNx error. key={}, result={}", strKey, result);
            return ERROR;
        }
    }

    public int exSet(Serializable key, Serializable value, int version, int expireSec) {
        String strKey = stringifyKey(key);
        String result;
        try (Jedis jedis = jedisPool.getResource()) {
            ExtendParams params = ExtendParams.extendParams().ex(expireSec);
            if (version <= 0) {
                params.abs(0);
            } else {
                params.ver(version);
            }
            byte[] valueByte = serialize(value);
            byte[] resp = (byte[]) jedis.sendCommand(RedisExtendCommand.EXSET, params.getByteParams(strKey.getBytes(UTF8), valueByte));

            result = SafeEncoder.encode(resp);
            if (RET_OK.equals(result)) {
                return SUCCESS;
            }
            LOGGER.error("redis exSet error. key={}, result={}", strKey, result);
            return ERROR;
        } catch (Exception e) {
            if (e.getMessage().contains("version is stale")) {
                return VER_ERR;
            }
            LOGGER.error("redis exSet error. key={}", strKey, e);
            return ERROR;
        }
    }

    public <T> T exGet(Serializable key) {
        String strKey = stringifyKey(key);
        List<byte[]> resp;
        try (Jedis jedis = jedisPool.getResource()) {
            resp = (List<byte[]>) jedis.sendCommand(RedisExtendCommand.EXGET, strKey.getBytes(UTF8));
            if (null == resp) {
                return null;
            } else if (resp.size() == 2) {
                byte[] value = resp.get(0);
                return (T) deserialize(value);
            }
            LOGGER.error("redis exGet error. key={}, result={}", strKey, resp);
            return null;
        } catch (Exception e) {
            LOGGER.error("redis exGet error. key={}", strKey, e);
            return null;
        }
    }

    public Long del(Serializable key) {
        String strKey = stringifyKey(key);
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.del(strKey.getBytes(UTF8));
        }
    }

    public boolean set(Serializable key, Serializable value) {
        String strKey = stringifyKey(key);
        try (Jedis jedis = jedisPool.getResource()) {

            byte[] valueByte = serialize(value);
            LOGGER.info("redis put key={}, value hash: {}", key, MessageDigestUtil.getMD5(valueByte));

            String result;
            result = jedis.set(strKey.getBytes(UTF8), valueByte, SetParams.setParams());
            if (RET_OK.equals(result)) {
                return true;
            }
            LOGGER.error("redis set error. key={}, result={}", strKey, result);
            return false;
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public boolean hset(String key, String field, String value) {
        try (Jedis jedis = jedisPool.getResource()) {
            Long result = jedis.hset(key, field, value);

            if (1L == result || 0L == result) {
                return true;
            }
            LOGGER.error("redis hset error. key={},field={}, result={}", key, field, result);
            return false;
        }
    }

    public String hget(String key, String field) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.hget(key, field);
        }
    }

    public boolean hdel(String key, String field) {
        try (Jedis jedis = jedisPool.getResource()) {
            Long result = jedis.hdel(key, field);

            if (1L == result || 0L == result) {
                return true;
            }
            LOGGER.error("redis hdel error. key={},field={}, result={}", key, field, result);
            return false;
        }
    }

    public Map<String, String> hgetAll(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.hgetAll(key);
        }
    }

    public boolean exists(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.exists(key);
        }
    }

    public Object eval(String script, List<String> keys, List<String> args) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.eval(script, keys, args);
        }
    }

    private void testEnterprise(JedisPool jedisPool) {
        String testKey = "__testEnterprise__";
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.sendCommand(RedisExtendCommand.EXGET, testKey);
        } catch (Exception e) {
            if (e.getMessage().contains("unknown command")) {
            } else {
                throw new RuntimeException("redis enterprise test fail.", e);
            }
        }
    }

    public void setExpire(String key, long expireSec) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.expire(key, expireSec);
        }
    }

    public boolean setIfAbsent(String lockKey, String locked, Long lockExpireTime) {
        try (Jedis jedis = jedisPool.getResource()) {
            String result = jedis.set(lockKey, locked, SetParams.setParams().nx().ex(lockExpireTime));
            return "OK".equals(result);
        }
    }

    /** Acquire a distributed lock using an owner token and a millisecond TTL. */
    public boolean tryAcquireLock(String lockKey, String ownerToken, long ttlMillis) {
        if (lockKey == null || lockKey.isBlank() || ownerToken == null || ownerToken.isBlank() || ttlMillis <= 0) {
            return false;
        }
        try (Jedis jedis = jedisPool.getResource()) {
            String result = jedis.set(lockKey, ownerToken, SetParams.setParams().nx().px(ttlMillis));
            return RET_OK.equals(result);
        }
    }

    /** Release only the lock owned by {@code ownerToken}; stale owners cannot delete a renewed lock. */
    public boolean releaseLock(String lockKey, String ownerToken) {
        if (lockKey == null || ownerToken == null) {
            return false;
        }
        String script = "if redis.call('get', KEYS[1]) == ARGV[1] then "
                + "return redis.call('del', KEYS[1]) else return 0 end";
        try (Jedis jedis = jedisPool.getResource()) {
            Object result = jedis.eval(script, List.of(lockKey), List.of(ownerToken));
            return result instanceof Number && ((Number) result).longValue() == 1L;
        }
    }

    public String getString(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get(key);
        }
    }

    public long exIncrBy(String key, long increment, long expireSeconds) {
        try (Jedis jedis = jedisPool.getResource()) {
            long val = jedis.incrBy(key, increment);
            if (val == increment) {
                jedis.expire(key, expireSeconds);
            }
            return val;
        }
    }

    public void sadd(String key, String member) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.sadd(key, member);
        }
    }

    public void srem(String key, String member) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.srem(key, member);
        }
    }

    public java.util.Set<String> smembers(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.smembers(key);
        }
    }

    public void setWithExpire(String key, String value, long expireSec) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.set(key, value, SetParams.setParams().ex(expireSec));
        }
    }

    public void publish(String channel, String message) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.publish(channel, message);
        }
    }

    public void lpush(String key, String value) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.lpush(key, value);
        }
    }

    public String brpop(String key, int timeoutSec) {
        try (Jedis jedis = jedisPool.getResource()) {
            List<String> result = jedis.brpop(timeoutSec, key);
            return (result != null && result.size() == 2) ? result.get(1) : null;
        }
    }

    public long llen(String key) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.llen(key);
        }
    }

    public StreamEntryID xadd(String stream, Map<String, String> fields, long maxLen) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.xadd(stream, fields,
                    XAddParams.xAddParams().maxLen(maxLen).approximateTrimming());
        }
    }

    public void ensureConsumerGroup(String stream, String group) {
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.xgroupCreate(stream, group, StreamEntryID.LAST_ENTRY, true);
        } catch (JedisDataException e) {
            if (e.getMessage() != null && e.getMessage().contains("BUSYGROUP")) {
                return;
            }
            throw e;
        }
    }

    public List<StreamEntry> xreadGroup(String stream, String group, String consumer, int count, int blockMillis) {
        try (Jedis jedis = jedisPool.getResource()) {
            XReadGroupParams params = XReadGroupParams.xReadGroupParams().count(count);
            if (blockMillis > 0) {
                params.block(blockMillis);
            }
            List<Map.Entry<String, List<StreamEntry>>> result = jedis.xreadGroup(
                    group,
                    consumer,
                    params,
                    Map.of(stream, StreamEntryID.UNRECEIVED_ENTRY));
            return flattenStreamEntries(result);
        }
    }

    public long xack(String stream, String group, String messageId) {
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.xack(stream, group, new StreamEntryID(messageId));
        }
    }

    public List<StreamEntry> xautoClaim(String stream, String group, String consumer,
                                        long minIdleMillis, int count) {
        try (Jedis jedis = jedisPool.getResource()) {
            Map.Entry<StreamEntryID, List<StreamEntry>> result = jedis.xautoclaim(
                    stream,
                    group,
                    consumer,
                    minIdleMillis,
                    new StreamEntryID("0-0"),
                    XAutoClaimParams.xAutoClaimParams().count(count));
            return result == null || result.getValue() == null ? List.of() : result.getValue();
        }
    }

    public long xpendingDeliveryCount(String stream, String group, String messageId) {
        try (Jedis jedis = jedisPool.getResource()) {
            List<StreamPendingEntry> entries = jedis.xpending(
                    stream,
                    group,
                    XPendingParams.xPendingParams()
                            .start(new StreamEntryID(messageId))
                            .end(new StreamEntryID(messageId))
                            .count(1));
            return entries == null || entries.isEmpty() ? 1L : entries.get(0).getDeliveredTimes();
        }
    }

    private static List<StreamEntry> flattenStreamEntries(List<Map.Entry<String, List<StreamEntry>>> streams) {
        if (streams == null || streams.isEmpty()) {
            return List.of();
        }
        List<StreamEntry> entries = new ArrayList<>();
        for (Map.Entry<String, List<StreamEntry>> stream : streams) {
            if (stream != null && stream.getValue() != null) {
                entries.addAll(stream.getValue());
            }
        }
        return entries;
    }
}
