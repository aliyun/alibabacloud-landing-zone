package com.aliyun.autowonder.statemachine;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class StatusTemplateDO {
    private Long id;
    private Long tenantId;
    private String workType;
    private String name;
    private Integer isDefault;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
    private Integer version;
}
