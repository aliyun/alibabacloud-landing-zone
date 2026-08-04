package com.aliyun.autowonder.integration.dingtalk;

import com.alibaba.fastjson.JSON;
import com.aliyun.autowonder.redis.RedisManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class DingTalkStreamStatusStore {

    private static final long TTL_SECONDS = 7 * 24 * 3600L;
    private static final Logger log = LoggerFactory.getLogger(DingTalkStreamStatusStore.class);

    private final RedisManager redisManager;

    public DingTalkStreamStatusStore(RedisManager redisManager) {
        this.redisManager = redisManager;
    }

    public void markConnecting(DingtalkRobotBindingDO binding) {
        write(binding.getId(), new Status("CONNECTING", null, System.currentTimeMillis()));
    }

    public void markConnected(DingtalkRobotBindingDO binding) {
        write(binding.getId(), new Status("CONNECTED", null, System.currentTimeMillis()));
    }

    public void markFailed(DingtalkRobotBindingDO binding, String error) {
        write(binding.getId(), new Status("FAILED", error, System.currentTimeMillis()));
    }

    public void markNotConnected(DingtalkRobotBindingDO binding) {
        write(binding.getId(), Status.notConnected(System.currentTimeMillis()));
    }

    public Status get(Long bindingId) {
        if (bindingId == null) {
            return Status.notConnected();
        }
        try {
            String raw = redisManager.getString(key(bindingId));
            if (raw == null || raw.isBlank()) {
                return Status.notConnected();
            }
            Status status = JSON.parseObject(raw, Status.class);
            if (status == null || status.getStatus() == null || status.getStatus().isBlank()) {
                return Status.notConnected();
            }
            return status;
        } catch (RuntimeException e) {
            log.warn("failed to read DingTalk Stream status bindingId={}", bindingId, e);
            return Status.notConnected();
        }
    }

    private void write(Long bindingId, Status status) {
        redisManager.setWithExpire(key(bindingId), JSON.toJSONString(status), TTL_SECONDS);
    }

    static String key(Long bindingId) {
        return "dingtalk:stream:status:" + bindingId;
    }

    public static class Status {
        private String status;
        private String error;
        private Long updatedAt;

        public Status() {}

        public Status(String status, String error, Long updatedAt) {
            this.status = status;
            this.error = error;
            this.updatedAt = updatedAt;
        }

        public static Status notConnected() {
            return new Status("NOT_CONNECTED", null, null);
        }

        public static Status notConnected(Long updatedAt) {
            return new Status("NOT_CONNECTED", null, updatedAt);
        }

        public String getStatus() {
            return status;
        }

        public void setStatus(String status) {
            this.status = status;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }

        public Long getUpdatedAt() {
            return updatedAt;
        }

        public void setUpdatedAt(Long updatedAt) {
            this.updatedAt = updatedAt;
        }
    }
}
