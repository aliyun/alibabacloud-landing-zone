package com.aliyun.autowonder.environment;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EnvironmentVariableReference {
    private Long agentId;
    private String agentName;
    private Long agentVersionId;
    private Integer versionNo;
    private String refType;
}
