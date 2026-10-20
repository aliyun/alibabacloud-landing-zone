package com.aliyun.autowonder.auth.federation;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

/** IdP 实例：一个 AD FS 部署或一个 Entra 租户等身份提供方实体，下挂多条协议连接。 */
@Getter
@Setter
public class FederationIdpDO {
    private Long id;
    private String name;
    private String description;
    private Integer enabled;
    private Long createdBy;
    private Date gmtCreate;
    private Date gmtModified;
}
