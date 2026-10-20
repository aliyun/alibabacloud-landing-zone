package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SubStepVO {
    private String name;
    /** Present only for an owned fallback, not runtime-reported text. */
    private String fallbackCode;
    private String status;
}
