package com.aliyun.autowonder.user;

import lombok.Getter;
import lombok.Setter;
import java.util.Date;

@Getter
@Setter
public class UserDO {
    private Long id;
    private String username;
    private String email;
    private String passwordHash;
    private String nickname;
    private String avatarUrl;
    private String phone;
    private Integer status;
    private Date lastLoginAt;
    private Date gmtCreate;
    private Date gmtModified;
    private Integer isDeleted;
}
