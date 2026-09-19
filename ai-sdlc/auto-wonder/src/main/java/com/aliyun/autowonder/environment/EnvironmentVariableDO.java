package com.aliyun.autowonder.environment;

import com.aliyun.autowonder.common.entity.BaseDO;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class EnvironmentVariableDO extends BaseDO {
    private String name;
    private String credentialRef;
    private String description;
}
