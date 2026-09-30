package com.aliyun.autowonder.memory.store;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class MemoryChangeDO {
    private Long id;
    private Long tenantId;
    private Long storeId;
    private Long documentId;
    private Long storeRevision;
    private String operation;
    private String path;
    private Integer documentVersion;
    private String contentSha256;
    private String actorType;
    private String actorRef;
    private Long dispatchId;
    private String providerSessionId;
    private String idempotencyKey;
    private String requestFingerprint;
    private Date gmtCreate;
}
