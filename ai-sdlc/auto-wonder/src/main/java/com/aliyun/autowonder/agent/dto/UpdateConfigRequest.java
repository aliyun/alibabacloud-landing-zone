package com.aliyun.autowonder.agent.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateConfigRequest {
    private String roleName;
    private String roleCode;
    /** REST compatibility field containing the digital worker's SOUL.md Markdown content. */
    private String businessBackground;
    /** REST compatibility field containing the digital worker's AGENT.md Markdown content. */
    private String responsibilities;
    private Long sdlcId;
    private String evolutionMode;
}
