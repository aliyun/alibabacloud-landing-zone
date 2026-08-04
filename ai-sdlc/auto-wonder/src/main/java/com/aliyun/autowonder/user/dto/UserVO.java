package com.aliyun.autowonder.user.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserVO {
    private Long id;
    private String username;
    private String nickname;
    private String email;
}
