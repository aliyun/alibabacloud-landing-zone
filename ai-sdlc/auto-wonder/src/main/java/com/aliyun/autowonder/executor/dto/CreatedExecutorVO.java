package com.aliyun.autowonder.executor.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * What the create-executor dialog holds after a successful create: the one-time plaintext token plus the launch
 * config the server just persisted on the executor row. These are echoed for confirmation only —
 * build_executor_launch_command reads the same values back from the database, so nothing has to be passed along.
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreatedExecutorVO {
    private Long id;
    private Long agentId;
    private String name;
    /** One-time plaintext token; it cannot be read back after this response unless it stays retrievable. */
    private String token;
    private String clientKind;
    private String memoryMode;
    private String model;
    private String reasoningEffort;
    private String contextWindow;
    private Integer maxConcurrentDispatches;
}
