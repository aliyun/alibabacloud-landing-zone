package com.aliyun.autowonder.memory;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class MemoryDO {
    private Long id;
    private Long tenantId;
    private String scope;
    private Long ownerRef;
    private String type;
    private String title;
    private String contentMd;
    private String status;
    private String source;
    private String sourceRef;
    private String sourceDedupeKey;
    private Date gmtCreate;
    private Date gmtModified;
    private Long creatorId;
    private Long modifierId;
    private Integer isDeleted;
    private Integer version;
}
