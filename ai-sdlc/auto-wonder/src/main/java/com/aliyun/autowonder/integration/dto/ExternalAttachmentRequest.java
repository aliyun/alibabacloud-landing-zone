package com.aliyun.autowonder.integration.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExternalAttachmentRequest {
    private String name;
    private String url;
    private String contentType;
    private Long size;
}
