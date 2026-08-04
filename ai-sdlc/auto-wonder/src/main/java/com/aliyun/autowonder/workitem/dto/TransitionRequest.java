package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class TransitionRequest {
    private Long toNodeId;
}
