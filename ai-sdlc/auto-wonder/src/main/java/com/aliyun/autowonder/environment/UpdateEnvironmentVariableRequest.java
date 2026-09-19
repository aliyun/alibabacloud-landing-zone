package com.aliyun.autowonder.environment;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import javax.validation.constraints.NotNull;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UpdateEnvironmentVariableRequest {
    private String name;
    private String description;
    @NotNull
    private Boolean updateValue;
    private String value;

    public Boolean isUpdateValue() {
        return updateValue;
    }
}
