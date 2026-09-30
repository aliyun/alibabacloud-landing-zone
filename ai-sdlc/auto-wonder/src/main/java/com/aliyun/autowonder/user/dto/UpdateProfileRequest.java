package com.aliyun.autowonder.user.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpdateProfileRequest {
    private String nickname;
    private String email;
    private String phone;
}
