package com.aliyun.autowonder.statemachine.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class TemplateVO {
    private Long id;
    private String workType;
    private String name;
    private Boolean isDefault;
    private Date gmtCreate;
    private Date gmtModified;
}
