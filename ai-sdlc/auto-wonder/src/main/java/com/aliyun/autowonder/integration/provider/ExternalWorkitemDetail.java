package com.aliyun.autowonder.integration.provider;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class ExternalWorkitemDetail extends ExternalWorkitemSummary {
    private String contentMd;
    private Integer priority;
    private String assigneeStaffId;
    private String authorStaffId;
    private Date createdAt;
}
