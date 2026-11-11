package com.aliyun.autowonder.auth.federation.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 更新平台认证配置：null 字段保持现状。
 * 默认连接没有「显式置空」的 JSON 表达，用 clearDefaultProvider 明确清除，避免与「未传」混淆。
 */
@Getter
@Setter
public class UpdatePlatformAuthConfigRequest {
    private Boolean registrationEnabled;
    private Long defaultProviderId;
    private Boolean clearDefaultProvider;
}
