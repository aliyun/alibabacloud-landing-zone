package com.aliyun.autowonder.im;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class PlatformImChannelConfigDO {
    private Long id;
    private String provider;
    private Integer enabled;
    private String appKey;
    private String credentialRef;
    private String robotCode;
    private String baseUrl;
    private Long creatorId;
    private Long modifierId;
    private Date gmtCreate;
    private Date gmtModified;
    private Integer isDeleted;
    private Integer version;
}
