package com.aliyun.autowonder.workitem;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "autowonder.workitem.scheduled-start")
public class WorkitemScheduledStartConfig {
    private boolean scannerEnabled = true;
    private long scanFixedDelayMs = 10_000L;
    private int scanBatchSize = 50;
    private int lockTtlSeconds = 30;
}
