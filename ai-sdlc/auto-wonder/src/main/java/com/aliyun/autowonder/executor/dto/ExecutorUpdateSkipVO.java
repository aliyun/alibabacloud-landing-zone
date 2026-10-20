package com.aliyun.autowonder.executor.dto;

import lombok.Getter;
import lombok.Setter;

/** One executor the batch upgrade deliberately left alone, with the reason shown in the result dialog. */
@Getter
@Setter
public class ExecutorUpdateSkipVO {
    private Long executorId;
    private String executorName;
    private String reason;
    /** Present only for newly produced system skip reasons; absent/unknown reasons stay raw. */
    private String reasonCode;

    public ExecutorUpdateSkipVO(Long executorId, String executorName, String reason, String reasonCode) {
        this(executorId, executorName, reason);
        this.reasonCode = reasonCode;
    }

    public ExecutorUpdateSkipVO() {
    }

    public ExecutorUpdateSkipVO(Long executorId, String executorName, String reason) {
        this.executorId = executorId;
        this.executorName = executorName;
        this.reason = reason;
    }
}
