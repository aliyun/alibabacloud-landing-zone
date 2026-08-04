package com.aliyun.autowonder.integration.dingtalk;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "autowonder.dingtalk.stream")
public class DingTalkStreamProperties {
    private boolean enabled = true;
    private int consumeThreads = 8;
    private long connectTimeoutMs = 3000L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getConsumeThreads() {
        return consumeThreads;
    }

    public void setConsumeThreads(int consumeThreads) {
        this.consumeThreads = consumeThreads;
    }

    public long getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(long connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }
}
