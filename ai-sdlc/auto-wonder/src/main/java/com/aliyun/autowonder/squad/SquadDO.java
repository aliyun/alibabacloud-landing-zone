package com.aliyun.autowonder.squad;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class SquadDO {
    private Long id;
    private Long tenantId;
    private String name;
    private String description;
    private Long ownerId;
    private Integer status;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
    private Integer version;
}
