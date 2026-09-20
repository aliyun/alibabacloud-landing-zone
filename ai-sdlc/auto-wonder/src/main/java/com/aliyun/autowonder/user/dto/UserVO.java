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
    /** True when the user holds the platform-admin flag (user.is_admin); read fresh per login. */
    private Boolean isAdmin;
}
