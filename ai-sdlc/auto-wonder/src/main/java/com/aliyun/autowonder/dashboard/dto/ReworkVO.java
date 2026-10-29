package com.aliyun.autowonder.dashboard.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReworkVO {
    private Double rate;
    private int reworkedWorkitems;
    private int executedWorkitems;
    private int retryCount;
}
