package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WorkitemOriginVO {
    private String type;
    private Long id;
    private Long scheduledTaskId;
    private String scheduledTaskName;
}
