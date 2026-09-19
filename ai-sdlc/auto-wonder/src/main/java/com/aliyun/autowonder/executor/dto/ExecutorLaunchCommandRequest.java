package com.aliyun.autowonder.executor.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * Launch-command generation payload. It deliberately carries only output-formatting options: the launch values
 * themselves come from the persisted config, so a command can never be generated from an unsaved form state or a
 * per-request override.
 */
@Getter
@Setter
public class ExecutorLaunchCommandRequest {
    /** Target OS used for shell quoting: posix or windows; defaults to posix. */
    private String os;
    /** Appends --debug and tees the full log; defaults to false. */
    private Boolean debug;
    /** Debug shell, bash or powershell; only used when debug is true. */
    private String shell;
}
