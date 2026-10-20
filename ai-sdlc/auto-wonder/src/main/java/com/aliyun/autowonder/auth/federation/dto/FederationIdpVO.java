package com.aliyun.autowonder.auth.federation.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/** 平台管理员视角的 IdP 实例。 */
@Getter
@Setter
public class FederationIdpVO {
    private Long id;
    private String name;
    private String description;
    /** 1 启用 / 0 停用：停用后其下所有连接都不出现在登录页。 */
    private Integer enabled;
    private Long createdBy;
    private Date gmtCreate;
    private Date gmtModified;
}
