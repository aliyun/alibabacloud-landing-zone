package com.aliyun.autowonder.template;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class SquadTemplateDO {
    private Long id;
    private Long tenantId;
    private String name;
    private String description;
    private Integer squadSize;
    private String icon;
    private String tags;
    private String contentJson;
    private String status;
    private Date gmtCreate;
    private Date gmtModified;
    private Integer isDeleted;
}
