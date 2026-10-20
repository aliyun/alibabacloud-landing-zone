package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class StepTimingVO {
    private Long stepId;
    private String name;
    private String status;
    /** Null means no measured start, not zero work. */
    private Long durationMs;
}
