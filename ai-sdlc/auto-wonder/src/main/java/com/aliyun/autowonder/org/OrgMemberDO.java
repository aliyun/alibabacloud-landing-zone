package com.aliyun.autowonder.org;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class OrgMemberDO {
    private Long id;
    private Long tenantId;
    private Long userId;
    private Integer status;
    private Date joinedAt;
    private String accessLevel;
    private String identityTags;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
}
