package com.aliyun.autowonder.category;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class SkillCategoryRefDO {
    private Long id;
    private Long tenantId;
    private String assetType;
    private Long assetId;
    private Long categoryId;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
}
