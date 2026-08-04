package com.aliyun.autowonder.agent.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateAgentRequest {
    private String name;
    private String avatarUrl;
    private String roleName;
    private String roleCode;
    private String businessBackground;
    private String responsibilities;
}
