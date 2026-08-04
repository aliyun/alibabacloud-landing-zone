package com.aliyun.autowonder.statemachine.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateTemplateRequest {
    private String workType;
    private String name;
}
