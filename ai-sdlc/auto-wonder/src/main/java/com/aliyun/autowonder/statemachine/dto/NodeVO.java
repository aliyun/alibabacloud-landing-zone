package com.aliyun.autowonder.statemachine.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class NodeVO {
    private Long id;
    private Long templateId;
    private String code;
    private String name;
    private String category;
    private Integer sort;
    private Date gmtCreate;
}
