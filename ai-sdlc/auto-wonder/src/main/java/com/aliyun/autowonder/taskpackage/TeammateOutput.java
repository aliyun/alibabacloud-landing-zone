package com.aliyun.autowonder.taskpackage;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class TeammateOutput {
    private String roleName;
    private String agentId;
    private String dispatchId;
    private String conclusionMd;
    private List<TaskArtifactRef> artifacts;
}
