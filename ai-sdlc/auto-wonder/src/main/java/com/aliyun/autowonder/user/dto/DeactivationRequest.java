package com.aliyun.autowonder.user.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DeactivationRequest {
    private String confirmUsername;
}
