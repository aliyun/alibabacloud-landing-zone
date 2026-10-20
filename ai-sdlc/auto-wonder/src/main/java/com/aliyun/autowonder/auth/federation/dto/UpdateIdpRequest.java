package com.aliyun.autowonder.auth.federation.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateIdpRequest {
    private String name;
    private String description;
    /** null 表示保持现状。 */
    private Integer enabled;
}
