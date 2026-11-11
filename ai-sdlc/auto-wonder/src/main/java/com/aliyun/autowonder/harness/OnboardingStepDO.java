package com.aliyun.autowonder.harness;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class OnboardingStepDO {
    private Long id;
    private Long tenantId;
    private Long onboardingId;
    private String stepKey;
    private String name;
    private String kind;
    private Integer stepOrder;
    private String status;
    private String reason;
    private String evidence;
    private Integer version;
    private Date gmtCreate;
    private Date gmtModified;
}
