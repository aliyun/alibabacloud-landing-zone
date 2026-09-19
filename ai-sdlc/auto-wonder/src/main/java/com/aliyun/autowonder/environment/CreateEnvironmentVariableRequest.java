package com.aliyun.autowonder.environment;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateEnvironmentVariableRequest {
    private String name;
    private String value;
    private String description;
}
