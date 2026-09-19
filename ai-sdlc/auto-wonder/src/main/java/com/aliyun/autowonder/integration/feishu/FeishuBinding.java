package com.aliyun.autowonder.integration.feishu;

import lombok.Data;
import java.util.Date;

@Data
public class FeishuBinding {
    private Long id;
    private Long tenantId;
    private String appId;
    private String credentialRef;
    private Long agentId;
    private String status;
    private Date lastSuccessAt;
    private String lastError;
    private Long creatorId;
    private Long modifierId;
    private Integer version;
}
