package com.aliyun.autowonder.integration.dingtalk.dto;

import lombok.Data;

@Data
public class BindingUpsertRequest {
    private String appKey;
    private String appSecret; // 明文,仅提交时;编辑留空表示不改
    private String robotCode;
    private Long agentId;
    private String transportMode;
    private String streamEnv;
    private String callbackToken;
    private String baseUrl;
    private String regionId;
    private String status;
}
