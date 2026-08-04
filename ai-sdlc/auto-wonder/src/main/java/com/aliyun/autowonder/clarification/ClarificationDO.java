package com.aliyun.autowonder.clarification;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class ClarificationDO {
    private Long id;
    private Long tenantId;
    private Long workitemId;
    private String contentMd;
    private Integer version;
    private Date gmtCreate;
    private Date gmtModified;
}
