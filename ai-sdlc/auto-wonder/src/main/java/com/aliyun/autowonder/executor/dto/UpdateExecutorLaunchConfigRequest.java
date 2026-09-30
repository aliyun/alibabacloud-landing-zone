package com.aliyun.autowonder.executor.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Write payload for the executor launch config. {@code version} is the optimistic-lock token the caller last read;
 * a mismatch means someone else saved in between and the write is rejected as a conflict.
 *
 * <p>PUT 为全量替换：本次请求中未出现的可选字段（model / reasoningEffort / contextWindow / memoryMode）
 * 按既有启动配置规则归一化。maxConcurrentDispatches 未传时保留已有值，以兼容旧客户端。
 *
 * <p>clientKind 仅用于历史缺失类型（client_kind 为 NULL/空白）执行器的恢复：此时必填且仅接受
 * QODER_CLI/QODER_CN_CLI，与启动配置一并落库；类型已存在的执行器携带不同的值会被拒绝，绝不静默改写。
 */
@Getter
@Setter
public class UpdateExecutorLaunchConfigRequest {
    private String clientKind;
    private String model;
    private String reasoningEffort;
    private String contextWindow;
    private String memoryMode;
    private Integer maxConcurrentDispatches;
    private Integer version;
}
