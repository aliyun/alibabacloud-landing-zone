package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AssignRequest {
    private String assigneeType;
    private Long assigneeRef;
    private Long sdlcId;
    private Long squadId;
}
