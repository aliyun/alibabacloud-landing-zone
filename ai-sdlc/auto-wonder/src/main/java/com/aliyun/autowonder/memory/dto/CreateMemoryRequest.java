package com.aliyun.autowonder.memory.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateMemoryRequest {
    private String scope;
    private Long ownerRef;
    private String type;
    private String title;
    private String contentMd;
}
