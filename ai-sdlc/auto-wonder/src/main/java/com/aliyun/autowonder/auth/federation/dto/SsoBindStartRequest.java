package com.aliyun.autowonder.auth.federation.dto;

import lombok.Getter;
import lombok.Setter;

/** 受保护的跨 IdP 绑定发起请求：为当前登录用户新增一条外部身份。 */
@Getter
@Setter
public class SsoBindStartRequest {
    private Long providerId;
}
