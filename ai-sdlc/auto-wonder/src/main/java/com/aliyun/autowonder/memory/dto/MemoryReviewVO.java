package com.aliyun.autowonder.memory.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class MemoryReviewVO {
    private Long id;
    private Long memoryId;
    private Long reviewerId;
    private String decision;
    private String editedContentMd;
    private String comment;
    private Date gmtCreate;
}
