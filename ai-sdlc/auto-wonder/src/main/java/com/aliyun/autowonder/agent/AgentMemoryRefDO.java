package com.aliyun.autowonder.agent;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class AgentMemoryRefDO {
    private Long id;
    private Long tenantId;
    private Long agentVersionId;
    private Long memoryId;
    private String source;
    private Date gmtCreate;
}
