package com.aliyun.autowonder.integration.provider;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ExternalWorkitemDetail extends ExternalWorkitemSummary {
    private String contentMd;
    private Integer priority;
    private String assigneeStaffId;
    private String authorStaffId;
    private String externalUrl;
    private String sourceLifecycle;
    private ExternalPrincipalRef reporter;
    private ExternalPrincipalRef businessOwner;
    private List<ExternalPrincipalRelation> principalRelations = new ArrayList<>();
    private Date createdAt;
}
