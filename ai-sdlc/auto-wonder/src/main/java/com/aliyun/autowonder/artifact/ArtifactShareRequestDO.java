package com.aliyun.autowonder.artifact;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ArtifactShareRequestDO {
    private Long id;
    private Long tenantId;
    private Long workitemId;
    private Long dispatchId;
    private String name;
    private String sha256;
    private String status;
    private Long artifactId;
    private Long commentId;
    private String error;
}
