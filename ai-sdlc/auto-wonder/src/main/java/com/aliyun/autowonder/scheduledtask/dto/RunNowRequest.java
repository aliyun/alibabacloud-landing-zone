package com.aliyun.autowonder.scheduledtask.dto;

import lombok.Getter;
import lombok.Setter;

/** Caller supplied idempotency key for an explicit manual occurrence. */
@Getter
@Setter
public class RunNowRequest {
    private Integer version;
    private String requestId;
}
