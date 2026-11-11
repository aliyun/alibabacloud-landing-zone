package com.aliyun.autowonder.harness;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class HarnessRunStepDO {
    private Long id;
    private Long tenantId;
    private Long runId;
    private Integer stepNo;
    private String objectKey;
    private String objectType;
    private String status;
    private Long objectId;
    private String errorMessage;
    private Date gmtCreate;
    private Date gmtModified;
}
