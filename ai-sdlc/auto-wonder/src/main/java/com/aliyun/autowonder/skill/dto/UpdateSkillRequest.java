package com.aliyun.autowonder.skill.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateSkillRequest {
    private String name;
    private String type;
    private String installSpec;
    private String description;
}
