package com.aliyun.autowonder.im;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class UserImIdentityDO {
    private Long id;
    private Long userId;
    private String provider;
    private String externalUserId;
    private Long creatorId;
    private Long modifierId;
    private Date gmtCreate;
    private Date gmtModified;
    private Integer isDeleted;
    private Integer version;
}
