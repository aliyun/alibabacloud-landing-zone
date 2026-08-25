package com.aliyun.autowonder.integration.common;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
public class PrincipalRelationSnapshot {
    @JSONField(name = "source_key")
    private String sourceKey;
    @JSONField(name = "display_name")
    private String displayName;
    @JSONField(name = "principal_ids")
    private List<Long> principalIds = new ArrayList<>();
}
