package com.aliyun.autowonder.sdlc.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateSdlcRequest {
    private String name;
    private String description;
    private String workType;
}
