package com.aliyun.autowonder.org;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class OrgDO {
    private Long id;
    private String name;
    private String slug;
    private String description;
    private String background;
    private Long ownerId;
    private Integer status;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
    private Integer version;
}
