package com.aliyun.autowonder.memory.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReviewRequest {
    private String decision;
    private String editedContentMd;
    private String comment;
    private String scope;
    private Long ownerRef;
}
