package com.aliyun.autowonder.executor.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class CreateExecutorRequest {
    private String name;
    private String clientKind;
}
