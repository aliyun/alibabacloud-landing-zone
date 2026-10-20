package com.aliyun.autowonder.executor.dto;

import lombok.Getter;
import lombok.Setter;

/** Unsaved form values used only to preview a command; never persisted or dispatched. */
@Getter
@Setter
public class ExecutorLaunchCommandPreviewRequest extends ExecutorLaunchCommandRequest {
    private UpdateExecutorLaunchConfigRequest config;
}
