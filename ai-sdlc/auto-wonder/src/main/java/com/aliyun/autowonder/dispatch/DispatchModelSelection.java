package com.aliyun.autowonder.dispatch;

import lombok.Getter;
import lombok.Setter;

/** Immutable once stored for a dispatch; reasons are protocol codes, not translated persisted text. */
@Getter
@Setter
public class DispatchModelSelection {
    private String model;
    private String defaultModel;
    private int entryCount;
    private Integer threshold;
    private int policyVersion;
    private String reasonCode;
}
