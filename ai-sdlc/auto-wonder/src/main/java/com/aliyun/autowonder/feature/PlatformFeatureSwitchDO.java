package com.aliyun.autowonder.feature;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/** 平台级功能开关（全局表，无 tenant_id）。 */
@Getter
@Setter
public class PlatformFeatureSwitchDO {
    private Long id;
    private String switchKey;
    private Integer enabled;
    private Long modifierId;
    private Date gmtCreate;
    private Date gmtModified;
    private Integer version;
}
