package com.aliyun.autowonder.branding;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class PlatformBrandingDO {
    private Long id;
    private String platformName;
    private String logoOssRef;
    private String logoContentType;
    private String themeKey;
    private String primaryColor;
    private String domain;
    private Long creatorId;
    private Long modifierId;
    private Date gmtCreate;
    private Date gmtModified;
    private Integer isDeleted;
    private Integer version;
}
