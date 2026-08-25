package com.aliyun.autowonder.integration.provider;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class ExternalComment {
    private String externalId;
    private String externalWorkitemId;
    /** 来源系统中的用户内部 ID，仅用于在 Provider 内换取稳定身份 ID，不落库。 */
    private String authorInternalUserId;
    private String authorStaffId;
    private String authorName;
    private ExternalPrincipalRef author;
    private String contentMd;
    private Date createdAt;
    private Date updatedAt;
    private String sourceStatus;
    private String rawJson;
}
