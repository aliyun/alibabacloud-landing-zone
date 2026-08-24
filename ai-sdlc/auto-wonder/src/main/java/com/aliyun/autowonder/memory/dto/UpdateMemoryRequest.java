package com.aliyun.autowonder.memory.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateMemoryRequest {
    private String title;
    private String contentMd;
    private String type;
    private String scope;
    private Long ownerRef;
}
