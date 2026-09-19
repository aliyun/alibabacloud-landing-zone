package com.aliyun.autowonder.environment;

import lombok.Getter;
import lombok.Setter;

/** Internal persistence projection for resolving one agent-version environment snapshot. */
@Getter
@Setter
public class AgentEnvironmentVariableSnapshotRow {
    private Long refTenantId;
    private Long agentVersionId;
    private Long environmentVariableId;
    private Long variableId;
    private Long variableTenantId;
    private String name;
    private String credentialRef;
}
