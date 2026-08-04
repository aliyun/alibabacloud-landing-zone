package com.aliyun.autowonder.skill.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SkillPackageInspectVO {
    private String name;
    private String description;
    private String fileName;
    private Long packageSize;
}
