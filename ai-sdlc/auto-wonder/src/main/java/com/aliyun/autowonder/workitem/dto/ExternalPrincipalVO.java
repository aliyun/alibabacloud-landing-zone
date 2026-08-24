package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ExternalPrincipalVO {
    private Long id;
    private String provider;
    private String subjectId;
    private String displayName;
}
