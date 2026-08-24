package com.aliyun.autowonder.integration.provider;

import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class ExternalPrincipalRelation {
    private String sourceKey;
    private String displayName;
    private List<ExternalPrincipalRef> principals = new ArrayList<>();
}
