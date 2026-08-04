package com.aliyun.autowonder.artifact.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReportArtifactRequest {
    private Long workitemId;
    private Long dispatchId;
    private String name;
    private String type;
    private String ossRef;
    private Long size;
    private String metaJson;
}
