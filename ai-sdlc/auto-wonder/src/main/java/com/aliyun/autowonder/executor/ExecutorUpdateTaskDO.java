package com.aliyun.autowonder.executor;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * One in-flight or finished upgrade instruction for a single executor. Persisted (rather than kept in
 * Redis like the restart state) because the panel has to show offline executors as 待升级 and retry
 * failures across restarts of the server itself.
 */
@Getter
@Setter
public class ExecutorUpdateTaskDO {
    private Long id;
    private Long tenantId;
    private Long executorId;
    private String requestId;
    private String currentVersion;
    private String targetVersion;
    private String status;        // PENDING/DRAINING/UPDATING/SUCCESS/FAILED
    private Integer attemptCount;
    private Integer maxAttempts;
    private Date nextAttemptAt;
    /** When the command was last sent; null while it is still waiting to be delivered. */
    private Date deliveredAt;
    private String lastError;
    private String source;        // MANUAL/BATCH/AUTO
    private Long requestedBy;
    private Date requestedAt;
    private Date completedAt;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
}
