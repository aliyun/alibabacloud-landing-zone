package com.aliyun.autowonder.org.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateOrgRequest {
    private String name;
    private String description;
    private String background;
}
