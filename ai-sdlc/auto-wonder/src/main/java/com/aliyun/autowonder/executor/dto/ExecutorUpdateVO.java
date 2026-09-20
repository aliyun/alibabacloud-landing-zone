package com.aliyun.autowonder.executor.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/** Latest upgrade task of one executor, rendered as 待更新/排空中/升级中/成功/失败 in the panel. */
@Getter
@Setter
public class ExecutorUpdateVO {
    private Long taskId;
    private String requestId;
    private String status;        // PENDING/DRAINING/UPDATING/SUCCESS/FAILED
    private String currentVersion;
    private String targetVersion;
    private Integer attemptCount;
    private Integer maxAttempts;
    private String lastError;
    private String source;        // MANUAL/BATCH/AUTO
    private Date requestedAt;
    private Date nextAttemptAt;
    private Date completedAt;
}
