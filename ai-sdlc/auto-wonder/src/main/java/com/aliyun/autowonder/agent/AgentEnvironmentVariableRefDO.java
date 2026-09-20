package com.aliyun.autowonder.agent;

import lombok.Getter;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
public class AgentEnvironmentVariableRefDO {
    private Long id;
    private Long tenantId;
    private Long agentVersionId;
    private Long environmentVariableId;
    private Date gmtCreate;
}
