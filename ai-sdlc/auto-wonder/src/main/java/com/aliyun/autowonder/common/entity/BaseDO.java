package com.aliyun.autowonder.common.entity;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class BaseDO {
    private Long id;
    private Long tenantId;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
    private Integer version;
}
