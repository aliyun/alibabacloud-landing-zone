package com.aliyun.autowonder.insights.participation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "autowonder.insights.human-agent")
public class HumanAgentParticipationProperties {

    private String timezone = "Asia/Shanghai";
    private String cron = "0 0 3 * * *";
    private long cacheTtlSeconds = 97200;
    private long lockTtlMillis = 3600000;
    private int refreshCorePoolSize = 1;
    private int refreshMaxPoolSize = 2;
    private int refreshQueueCapacity = 20;
    private long cacheMissWaitMs = 300000;
    private int refreshPageSize = 5000;
    private long inflightTtlSeconds = 600;
    private long waitPollIntervalMs = 2000;

    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getCron() { return cron; }
    public void setCron(String cron) { this.cron = cron; }
    public long getCacheTtlSeconds() { return cacheTtlSeconds; }
    public void setCacheTtlSeconds(long cacheTtlSeconds) { this.cacheTtlSeconds = cacheTtlSeconds; }
    public long getLockTtlMillis() { return lockTtlMillis; }
    public void setLockTtlMillis(long lockTtlMillis) { this.lockTtlMillis = lockTtlMillis; }
    public int getRefreshCorePoolSize() { return refreshCorePoolSize; }
    public void setRefreshCorePoolSize(int refreshCorePoolSize) { this.refreshCorePoolSize = refreshCorePoolSize; }
    public int getRefreshMaxPoolSize() { return refreshMaxPoolSize; }
    public void setRefreshMaxPoolSize(int refreshMaxPoolSize) { this.refreshMaxPoolSize = refreshMaxPoolSize; }
    public int getRefreshQueueCapacity() { return refreshQueueCapacity; }
    public void setRefreshQueueCapacity(int refreshQueueCapacity) { this.refreshQueueCapacity = refreshQueueCapacity; }
    public long getCacheMissWaitMs() { return cacheMissWaitMs; }
    public void setCacheMissWaitMs(long cacheMissWaitMs) { this.cacheMissWaitMs = cacheMissWaitMs; }
    public int getRefreshPageSize() { return refreshPageSize; }
    public void setRefreshPageSize(int refreshPageSize) { this.refreshPageSize = refreshPageSize; }
    public long getInflightTtlSeconds() { return inflightTtlSeconds; }
    public void setInflightTtlSeconds(long inflightTtlSeconds) { this.inflightTtlSeconds = inflightTtlSeconds; }
    public long getWaitPollIntervalMs() { return waitPollIntervalMs; }
    public void setWaitPollIntervalMs(long waitPollIntervalMs) { this.waitPollIntervalMs = waitPollIntervalMs; }
}
