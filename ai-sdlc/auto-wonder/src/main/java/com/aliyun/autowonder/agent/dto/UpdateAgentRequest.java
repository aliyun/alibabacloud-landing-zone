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
    /** REST compatibility field containing the digital worker's SOUL.md Markdown content. */
    private String businessBackground;
    /** REST compatibility field containing the digital worker's AGENT.md Markdown content. */
    private String responsibilities;
}
