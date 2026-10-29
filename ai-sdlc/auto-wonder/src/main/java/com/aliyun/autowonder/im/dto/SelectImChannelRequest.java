package com.aliyun.autowonder.im.dto;

import lombok.Getter;
import lombok.Setter;

import javax.validation.constraints.NotBlank;

@Getter
@Setter
public class SelectImChannelRequest {
    @NotBlank
    private String provider;
    private Long expectedGeneration;
}
