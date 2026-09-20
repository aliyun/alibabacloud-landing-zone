package com.aliyun.autowonder.environment;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Date;

@Getter
@AllArgsConstructor
public class EnvironmentVariableVO {
    private final Long id;
    private final String name;
    private final String value;
    private final String description;
    private final Date gmtCreate;
    private final Date gmtModified;
    private final Integer version;
}
