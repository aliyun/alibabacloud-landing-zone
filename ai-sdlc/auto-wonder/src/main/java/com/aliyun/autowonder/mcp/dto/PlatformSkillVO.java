package com.aliyun.autowonder.mcp.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PlatformSkillVO {
    private String id;
    private String type;
    private String name;
    private String description;
    private String installSpec;

    public PlatformSkillVO() {
    }

    public PlatformSkillVO(String id, String type, String name, String description, String installSpec) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.description = description;
        this.installSpec = installSpec;
    }
}
