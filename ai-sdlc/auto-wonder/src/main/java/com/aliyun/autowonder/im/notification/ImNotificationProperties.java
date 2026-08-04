package com.aliyun.autowonder.im.notification;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "autowonder.im.notification")
public class ImNotificationProperties {
    private String streamKey = "autowonder:im-notification:stream";
    private String group = "autowonder-im-notification";
    private long maxLength = 10000L;
    private int batchSize = 10;
    private long claimIdleMs = 30000L;
    private int maxAttempts = 3;
    private long dedupeTtlSeconds = 604800L;
    private int blockMillis = 0;
    private long pollDelayMs = 1000L;
    private long recoveryDelayMs = 1000L;
    private String consumer = "autowonder-im-notification-worker";

    public String getStreamKey() {
        return streamKey;
    }

    public void setStreamKey(String streamKey) {
        this.streamKey = hasText(streamKey) ? streamKey : "autowonder:im-notification:stream";
    }

    public String getGroup() {
        return group;
    }

    public void setGroup(String group) {
        this.group = hasText(group) ? group : "autowonder-im-notification";
    }

    public long getMaxLength() {
        return maxLength;
    }

    public void setMaxLength(long maxLength) {
        this.maxLength = maxLength > 0 ? maxLength : 10000L;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize > 0 ? batchSize : 10;
    }

    public long getClaimIdleMs() {
        return claimIdleMs;
    }

    public void setClaimIdleMs(long claimIdleMs) {
        this.claimIdleMs = claimIdleMs > 0 ? claimIdleMs : 30000L;
    }

    public int getMaxAttempts() {
        return Math.max(maxAttempts, 3);
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = Math.max(maxAttempts, 3);
    }

    public long getDedupeTtlSeconds() {
        return dedupeTtlSeconds;
    }

    public void setDedupeTtlSeconds(long dedupeTtlSeconds) {
        this.dedupeTtlSeconds = dedupeTtlSeconds > 0 ? dedupeTtlSeconds : 604800L;
    }

    public int getBlockMillis() {
        return blockMillis;
    }

    public void setBlockMillis(int blockMillis) {
        this.blockMillis = Math.max(blockMillis, 0);
    }

    public long getPollDelayMs() {
        return pollDelayMs;
    }

    public void setPollDelayMs(long pollDelayMs) {
        this.pollDelayMs = pollDelayMs > 0 ? pollDelayMs : 1000L;
    }

    public long getRecoveryDelayMs() {
        return recoveryDelayMs;
    }

    public void setRecoveryDelayMs(long recoveryDelayMs) {
        this.recoveryDelayMs = recoveryDelayMs > 0 ? recoveryDelayMs : 1000L;
    }

    public String getConsumer() {
        return consumer;
    }

    public void setConsumer(String consumer) {
        this.consumer = hasText(consumer) ? consumer : "autowonder-im-notification-worker";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
