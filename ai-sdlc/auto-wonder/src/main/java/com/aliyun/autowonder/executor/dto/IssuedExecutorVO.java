package com.aliyun.autowonder.executor.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * What a successful create returns: the one-time plaintext token plus the launch config the server just persisted.
 * The echoed values come from the same validation the update path runs, so the caller can hand them straight to
 * build_executor_launch_command without re-deriving anything.
 */
@Getter
@Setter
public class IssuedExecutorVO {
    private Long id;
    private Long agentId;
    private String name;
    /** one-time plaintext token; not retrievable again */
    private String token;
    private String clientKind;
    private String memoryMode;
    private Integer maxConcurrentDispatches;
    /** Null for non-Qoder client kinds, whose launch config only carries a memory mode. */
    private String model;
    private String reasoningEffort;
    private String contextWindow;
    /** Optimistic-lock version of the persisted config; 1 for a freshly created executor. */
    private Integer configVersion;
}
