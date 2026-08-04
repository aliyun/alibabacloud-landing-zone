package com.aliyun.autowonder.integration.dingtalk.dto;

import lombok.Data;
import java.util.Date;

@Data
public class BindingView {
    private Long id;
    private String appKey;
    private String appSecretMasked; // 脱敏,不回传明文
    private String robotCode;
    private Long agentId;
    private String transportMode;
    private String streamEnv;
    private String streamStatus;
    private String streamError;
    private Long streamStatusUpdatedAt;
    private String baseUrl;
    private String regionId;
    private String status;
    private Date lastSuccessAt;
    private String lastError;
    private String callbackUrl; // 回调地址提示(含 token)
}
