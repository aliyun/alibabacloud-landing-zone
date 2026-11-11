package com.aliyun.autowonder.auth.federation;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/**
 * 平台级认证配置单行记录（id 固定为 1）：公开注册开关与全平台唯一的默认 SSO 连接。
 * 记录缺失时按「注册开放、无默认连接」处理，不要求初始化脚本先建行。
 */
@Getter
@Setter
public class PlatformAuthConfigDO {
    public static final long SINGLETON_ID = 1L;

    private Long id;
    private Integer registrationEnabled;
    private Long defaultProviderId;
    private Date gmtCreate;
    private Date gmtModified;
}
