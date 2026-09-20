package com.aliyun.autowonder.executor.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A ready-to-paste executor startup command plus the resolved inputs that produced it, so an MCP
 * caller can verify what will run without re-deriving the shell quoting rules.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ExecutorLaunchCommandVO {
    private Long executorId;
    private String clientKind;
    private String provider;
    private String memoryMode;
    private Integer maxConcurrentDispatches;
    private String model;
    private String reasoningEffort;
    private String contextWindow;
    private String wsUrl;
    private String runtimeVersion;
    /** "posix" or "windows". */
    private String os;
    private boolean debug;
    /** "bash" or "powershell"; only set when debug is true. */
    private String shell;
    /** Debug log file name; only set when debug is true. */
    private String logFileName;
    private String command;
}
