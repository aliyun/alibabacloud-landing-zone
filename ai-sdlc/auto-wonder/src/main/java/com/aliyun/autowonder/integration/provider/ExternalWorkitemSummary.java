package com.aliyun.autowonder.integration.provider;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class ExternalWorkitemSummary {
    private String externalId;
    private String externalProjectId;
    private String externalIssueTypeId;
    private String workType;
    private String title;
    private String statusId;
    private String statusName;
    private Date updatedAt;
    private String rawJson;
}
