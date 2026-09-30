package com.aliyun.autowonder.memory.store;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class MemoryDocumentDO {
    private Long id;
    private Long tenantId;
    private Long storeId;
    private String path;
    private String contentMd;
    private String contentSha256;
    private Long byteSize;
    private Date modifiedAt;
    private Date deletedAt;
    private String creatorType;
    private String creatorRef;
    private Long sourceDispatchId;
    private String sourceSessionId;
    private Long creatorId;
    private Long modifierId;
    private Integer version;
    private Date gmtCreate;
    private Date gmtModified;
    /** Human display fields, derived from frontmatter; not separate persisted memory. */
    private String title;
    private String memoryType;
    private String description;
    private String body;
}
