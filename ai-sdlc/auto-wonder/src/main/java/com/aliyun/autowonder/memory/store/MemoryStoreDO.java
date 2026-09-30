package com.aliyun.autowonder.memory.store;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class MemoryStoreDO {
    private Long id;
    private Long tenantId;
    private String scope;
    private Long ownerRef;
    private String name;
    private Long currentRevision;
    private String status;
    private Long creatorId;
    private Long modifierId;
    private Integer version;
    /** Effective caller permission; populated only for management API responses. */
    private String permission;
    private Date gmtCreate;
    private Date gmtModified;
}
