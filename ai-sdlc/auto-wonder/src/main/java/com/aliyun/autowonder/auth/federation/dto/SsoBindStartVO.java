package com.aliyun.autowonder.auth.federation.dto;

import lombok.Getter;
import lombok.Setter;

/** 由前端自行跳转到 IdP 的授权地址（受保护的绑定发起流程使用）。 */
@Getter
@Setter
public class SsoBindStartVO {
    private String authorizeUrl;

    public SsoBindStartVO() {
    }

    public SsoBindStartVO(String authorizeUrl) {
        this.authorizeUrl = authorizeUrl;
    }
}
