package com.aliyun.autowonder.skill;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class SkillDO {
    private Long id;
    private Long tenantId;
    private String type;
    private String name;
    private String installSpec;
    private String description;
    private String sourceType;
    private String packageOssRef;
    private String packageFileName;
    private Long packageSize;
    private String packageMd5;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
    private Integer version;
}
