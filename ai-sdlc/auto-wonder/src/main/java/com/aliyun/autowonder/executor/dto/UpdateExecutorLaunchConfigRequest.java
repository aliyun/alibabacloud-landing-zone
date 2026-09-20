package com.aliyun.autowonder.executor.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Write payload for the executor launch config. {@code version} is the optimistic-lock token the caller last read;
 * a mismatch means someone else saved in between and the write is rejected as a conflict.
 *
 * <p>PUT 为全量替换：本次请求中未出现的可选字段（model / reasoningEffort / contextWindow / memoryMode）
 * 按既有启动配置规则归一化。maxConcurrentDispatches 未传时保留已有值，以兼容旧客户端。
 */
@Getter
@Setter
public class UpdateExecutorLaunchConfigRequest {
    private String model;
    private String reasoningEffort;
    private String contextWindow;
    private String memoryMode;
    private Integer maxConcurrentDispatches;
    private Integer version;
}
