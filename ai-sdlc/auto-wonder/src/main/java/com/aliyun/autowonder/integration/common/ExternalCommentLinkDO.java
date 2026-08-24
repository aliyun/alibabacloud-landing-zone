package com.aliyun.autowonder.integration.common;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class ExternalCommentLinkDO {
    private Long id;
    private Long tenantId;
    private String provider;
    private Long bindingId;
    private String externalWorkitemId;
    private String externalCommentId;
    private Long workitemCommentId;
    private String direction;
    private Date sourceUpdatedAt;
    private String sourceStatus;
    private Date gmtCreate;
    private Date gmtModified;
}
