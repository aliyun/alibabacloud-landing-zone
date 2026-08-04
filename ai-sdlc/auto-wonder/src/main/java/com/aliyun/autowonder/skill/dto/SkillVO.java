package com.aliyun.autowonder.skill.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class SkillVO {
    private Long id;
    private String type;
    private String name;
    private String installSpec;
    private String description;
    private String sourceType;
    private String packageOssRef;
    private String packageFileName;
    private Long packageSize;
    private String packageMd5;
    private Integer version;
    private Date gmtCreate;
    private Date gmtModified;
    private Long modifierId;
    private String modifierName;
}
