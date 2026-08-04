package com.aliyun.autowonder.clarification.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class ClarificationVO {
    private Long workitemId;
    private String contentMd;
    private Integer version;
    private Date gmtModified;
}
