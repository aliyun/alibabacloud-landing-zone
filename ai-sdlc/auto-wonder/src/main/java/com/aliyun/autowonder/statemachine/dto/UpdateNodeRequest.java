package com.aliyun.autowonder.statemachine.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateNodeRequest {
    private String code;
    private String name;
    private String category;
    private Integer sort;
}
