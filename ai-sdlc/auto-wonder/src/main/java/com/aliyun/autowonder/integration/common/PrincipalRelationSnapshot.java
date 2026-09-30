package com.aliyun.autowonder.integration.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class PrincipalRelationSnapshot {
    @JsonProperty("source_key")
    private String sourceKey;
    @JsonProperty("display_name")
    private String displayName;
    @JsonProperty("principal_ids")
    private List<Long> principalIds = new ArrayList<>();
}
