package com.aliyun.autowonder.harness;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class HarnessSnapshotDO {
    private Long id;
    private Long tenantId;
    private Long workspaceId;
    private Long onboardingId;
    private String snapshotType;
    private Integer onboardingVersion;
    private String templateKey;
    private String templateVersion;
    private String templateSha256;
    private String contentJson;
    private String contentSha256;
    private Date expiresAt;
    private Date consumedAt;
    private Long creatorId;
    private Date gmtCreate;
}
