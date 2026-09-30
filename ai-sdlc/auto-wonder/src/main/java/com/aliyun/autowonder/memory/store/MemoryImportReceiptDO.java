package com.aliyun.autowonder.memory.store;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class MemoryImportReceiptDO {
    private Long id;
    private Long tenantId;
    private Long agentId;
    private Long sourceId;
    private Long snapshotId;
    private String sanitizedContentSha256;
    private Long curationDispatchId;
    private String outcome;
    private String targetVersionsJson;
    private Integer redactionCount;
    private Date gmtCreate;
}
