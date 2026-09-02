package com.aliyun.autowonder.scheduledtask;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "autowonder.scheduled-task")
public class ScheduledTaskProperties {
    private boolean enabled = false;
    private boolean scannerEnabled = false;
    private boolean clusterReadyAttestation = false;
    private long scanFixedDelayMs = 10_000L;
    private int scanBatchSize = 100;
    private int lockTtlSeconds = 30;
}
