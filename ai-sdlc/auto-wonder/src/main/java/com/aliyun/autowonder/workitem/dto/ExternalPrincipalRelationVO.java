package com.aliyun.autowonder.workitem.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ExternalPrincipalRelationVO {
    private String sourceKey;
    private String displayName;
    private List<ExternalPrincipalVO> principals = new ArrayList<>();
}
