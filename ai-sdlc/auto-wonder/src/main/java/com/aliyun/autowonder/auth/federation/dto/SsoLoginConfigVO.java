package com.aliyun.autowonder.auth.federation.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 登录页公开配置：只含注册开关与默认连接 ID，不含端点、证书或任何管理面信息。
 * 前端仅在默认连接也出现在可用连接列表时才自动发起认证。
 */
@Getter
@Setter
public class SsoLoginConfigVO {
    private Boolean registrationEnabled;
    private Long defaultProviderId;
}
