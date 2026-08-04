package com.aliyun.autowonder.statemachine.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateTransitionRequest {
    private Long fromNodeId;
    private Long toNodeId;
    private String name;
}
