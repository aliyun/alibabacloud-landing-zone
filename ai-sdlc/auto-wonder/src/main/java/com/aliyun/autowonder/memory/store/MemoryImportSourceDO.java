package com.aliyun.autowonder.memory.store;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class MemoryImportSourceDO {
    private Long id;
    private Long tenantId;
    private Long agentId;
    private Long targetStoreId;
    private String providerFamily;
    private String logicalPath;
    private String installationFingerprint;
    private String lastObservedSanitizedSha256;
    private String lastImportedSanitizedSha256;
    private Long lastObservedExecutorId;
    private Date lastSeenAt;
    private String status;
    private Integer version;
    private Date gmtCreate;
    private Date gmtModified;
}
