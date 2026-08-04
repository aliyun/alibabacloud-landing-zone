package com.aliyun.autowonder.statemachine;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class StatusNodeDO {
    private Long id;
    private Long tenantId;
    private Long templateId;
    private String code;
    private String name;
    private String category;
    private Integer sort;
    private Date gmtCreate;
}
