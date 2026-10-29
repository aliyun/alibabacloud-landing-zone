package com.aliyun.autowonder.dashboard.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PipelineHealthVO {
    private String range;
    private Double successRate;
    private SuccessSubVO successSub = new SuccessSubVO();
    private int failedOrTimeout;
    private ReworkVO rework = new ReworkVO();
    private Integer avgWorkitemDurationMinutes;
    private String generatedAt;
}
