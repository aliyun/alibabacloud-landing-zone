package com.aliyun.autowonder.aiusage;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.Date;

@Getter
@Setter
public class AgentConversationUsageDO {
    private Long id;
    private Long tenantId;
    private Long workitemId;
    private Long conversationId;
    private Long turnId;
    private Long agentId;
    private Long executorId;
    private String channel = "";
    private String provider = "";
    private String model = "";
    private Long inputTokens = 0L;
    private Long outputTokens = 0L;
    private Long cacheReadTokens = 0L;
    private Long cacheWriteTokens = 0L;
    private Long reasoningTokens = 0L;
    private BigDecimal credits;
    private Date usageAt;
    private Date gmtCreate;
    private Date gmtModified;
}
