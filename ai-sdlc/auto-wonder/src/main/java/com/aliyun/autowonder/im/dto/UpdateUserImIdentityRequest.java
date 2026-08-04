package com.aliyun.autowonder.im.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.Size;

@Getter
@Setter
public class UpdateUserImIdentityRequest {
    @Size(max = 256)
    private String externalUserId;
}
