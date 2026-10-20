package com.aliyun.autowonder.executor.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateExecutorLaunchCommandRequest {
    private String command;
    private Integer version;
    private String restartMode;
}
