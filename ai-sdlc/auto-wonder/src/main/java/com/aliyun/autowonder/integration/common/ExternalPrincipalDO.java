package com.aliyun.autowonder.integration.common;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class ExternalPrincipalDO {
    private Long id;
    private String provider;
    private String subjectId;
    private String displayName;
    private Date gmtCreate;
    private Date gmtModified;
}
