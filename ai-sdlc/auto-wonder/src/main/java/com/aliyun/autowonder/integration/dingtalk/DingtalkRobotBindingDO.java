package com.aliyun.autowonder.integration.dingtalk;

import lombok.Data;

import java.util.Date;

@Data
public class DingtalkRobotBindingDO {
    private Long id;
    private Long tenantId;
    private String appKey;
    private String credentialRef;
    private String robotCode;
    private Long agentId;
    private String transportMode;
    private String callbackToken;
    private String baseUrl;
    private String regionId;
    private String streamEnv;
    private Date lastSuccessAt;
    private String lastError;
    private String status;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
    private Integer version;
}
