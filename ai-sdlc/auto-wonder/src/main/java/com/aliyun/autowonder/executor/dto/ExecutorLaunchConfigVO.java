package com.aliyun.autowonder.executor.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * The launch config shared by the page's 启动命令 dialog and the MCP build_executor_launch_command tool.
 * A never-configured executor reports every field null at version 1, so the first write can carry version=1.
 */
@Getter
@Setter
public class ExecutorLaunchConfigVO {
    private String model;
    private String reasoningEffort;
    private String contextWindow;
    private String memoryMode;
    private Integer maxConcurrentDispatches;
    private Integer version;
}
