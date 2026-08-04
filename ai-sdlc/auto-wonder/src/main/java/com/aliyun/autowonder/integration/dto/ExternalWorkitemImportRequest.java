package com.aliyun.autowonder.integration.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class ExternalWorkitemImportRequest {
    private String sourceSystem;
    private String externalWorkitemId;
    private String externalProjectId;
    private String title;
    private String description;
    private String type;
    private Integer priority;
    private String assignee;
    private String creator;
    private String status;
    private String sourceUrl;
    private List<ExternalAttachmentRequest> attachments;
    private Map<String, String> fieldMappings;
    private Map<String, Object> extensions;
    private Boolean updateExisting;
    private String requestId;
}
