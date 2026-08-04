package com.aliyun.autowonder.statemachine;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class StatusTransitionDO {
    private Long id;
    private Long tenantId;
    private Long templateId;
    private Long fromNodeId;
    private Long toNodeId;
    private String name;
    private Date gmtCreate;
}
