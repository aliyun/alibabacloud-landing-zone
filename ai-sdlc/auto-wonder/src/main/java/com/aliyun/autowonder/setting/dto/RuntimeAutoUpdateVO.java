package com.aliyun.autowonder.setting.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RuntimeAutoUpdateVO {
    /** Whether the platform proactively upgrades executors that report an older runtime version. */
    private boolean executorAutoUpdateEnabled;
    /** The global runtime version every upgrade targets; there is no per-executor override. */
    private String targetVersion;

    public RuntimeAutoUpdateVO() {
    }

    public RuntimeAutoUpdateVO(boolean executorAutoUpdateEnabled, String targetVersion) {
        this.executorAutoUpdateEnabled = executorAutoUpdateEnabled;
        this.targetVersion = targetVersion;
    }
}
