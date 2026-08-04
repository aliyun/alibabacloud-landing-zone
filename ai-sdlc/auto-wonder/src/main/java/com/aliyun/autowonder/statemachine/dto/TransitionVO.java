package com.aliyun.autowonder.statemachine.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class TransitionVO {
    private Long id;
    private Long templateId;
    private Long fromNodeId;
    private Long toNodeId;
    private String name;
    private Date gmtCreate;
}
