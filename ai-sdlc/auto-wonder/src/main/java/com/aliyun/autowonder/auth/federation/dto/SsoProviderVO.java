package com.aliyun.autowonder.auth.federation.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 登录页可见的一条已启用连接：登录页只按该列表渲染入口，
 * 不识别厂商，新增 IdP 或同协议第二条连接无需改动前端。
 */
@Getter
@Setter
public class SsoProviderVO {
    private Long providerId;
    private String name;
    private String protocol;
    /** 所属 IdP 实例名，仅用于展示分组（如「AD FS 生产」）。 */
    private String idpName;
}
