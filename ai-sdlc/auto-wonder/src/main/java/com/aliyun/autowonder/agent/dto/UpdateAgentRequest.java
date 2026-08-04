package com.aliyun.autowonder.agent.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateAgentRequest {
    private Long id;
    private String name;
    private String roleCode;
    private String roleName;
    private String businessBackground;
    private String responsibilities;
}
