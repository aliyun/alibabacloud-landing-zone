package com.aliyun.autowonder.memory.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ImportFromArtifactRequest {
    private Long artifactId;
    private String scope;
    private Long ownerRef;
    private String title;
    private String contentMd;
    private String type;
}
