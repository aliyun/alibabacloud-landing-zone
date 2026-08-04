package com.aliyun.autowonder.agent.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateConfigRequest {
    private String roleName;
    private String roleCode;
    private String businessBackground;
    private String responsibilities;
    private Long sdlcId;
    private String evolutionMode;
}
