package com.aliyun.autowonder.aiusage;

import com.aliyun.autowonder.common.entity.BaseDO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AiUsageDO extends BaseDO {
    private Long tenantId;
    private String period;
    private String scene;
    private Long callCount;
    private Long inputTokens;
    private Long outputTokens;
}
