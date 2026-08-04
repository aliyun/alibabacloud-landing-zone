package com.aliyun.autowonder.ai.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateSessionRequest {
    private String scene;
    private String bizRefType;
    private Long bizRefId;
    private String input;
}
