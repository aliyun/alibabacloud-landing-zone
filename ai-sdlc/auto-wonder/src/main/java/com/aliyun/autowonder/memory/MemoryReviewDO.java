package com.aliyun.autowonder.memory;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class MemoryReviewDO {
    private Long id;
    private Long tenantId;
    private Long memoryId;
    private Long reviewerId;
    private String decision;
    private String editedContentMd;
    private String comment;
    private Date gmtCreate;
}
