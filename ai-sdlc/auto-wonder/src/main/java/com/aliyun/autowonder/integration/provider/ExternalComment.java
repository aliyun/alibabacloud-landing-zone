package com.aliyun.autowonder.integration.provider;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class ExternalComment {
    private String externalId;
    private String externalWorkitemId;
    private String authorStaffId;
    private String authorName;
    private String contentMd;
    private Date createdAt;
    private String rawJson;
}
