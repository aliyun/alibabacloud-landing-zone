package com.aliyun.autowonder.memory.store;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class MemoryImportSnapshotDO {
    private Long id;
    private Long tenantId;
    private Long sourceId;
    private String sanitizedContentSha256;
    private String sanitizedContent;
    private Long byteSize;
    private Date sourceModifiedAt;
    private String scanSummaryJson;
    private String status;
    private String curationLeaseId;
    private Date curationLeaseUntil;
    private Date gmtCreate;
    private Date gmtModified;
}
