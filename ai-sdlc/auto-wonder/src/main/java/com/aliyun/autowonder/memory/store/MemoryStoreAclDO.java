package com.aliyun.autowonder.memory.store;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class MemoryStoreAclDO {
    private Long id;
    private Long tenantId;
    private Long storeId;
    private String subjectType;
    private String subjectRef;
    private String permission;
    private Long creatorId;
    private Long modifierId;
    private Date gmtCreate;
    private Date gmtModified;
}
