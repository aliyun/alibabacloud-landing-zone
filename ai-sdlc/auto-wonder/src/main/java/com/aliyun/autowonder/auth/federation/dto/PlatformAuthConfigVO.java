package com.aliyun.autowonder.auth.federation.dto;

import lombok.Getter;
import lombok.Setter;

/** 平台管理员的认证配置视图：注册开关与全平台唯一默认连接。 */
@Getter
@Setter
public class PlatformAuthConfigVO {
    private Boolean registrationEnabled;
    private Long defaultProviderId;
}
