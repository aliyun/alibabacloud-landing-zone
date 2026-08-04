package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateContentRequest {
    private String title;
    private String contentMd;
}
