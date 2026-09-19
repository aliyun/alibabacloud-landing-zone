package com.aliyun.autowonder.category;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class CategoryDO {
    private Long id;
    private Long tenantId;
    private Long parentId;
    private String name;
    private String description;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
    private Integer version;
}
