package com.aliyun.autowonder.workspace;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class WorkspaceCleanupCandidate {
    private Long tenantId;
    private Long workitemId;
    private Long executorId;
    private Integer workitemVersion;
    private Date publishedAt;
}
