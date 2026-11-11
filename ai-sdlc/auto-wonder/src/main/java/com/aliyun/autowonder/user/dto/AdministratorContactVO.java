package com.aliyun.autowonder.user.dto;

import lombok.Getter;
import lombok.Setter;

/** 可用平台管理员的联系方式；只含昵称与邮箱，缺字段时保持 null。 */
@Getter
@Setter
public class AdministratorContactVO {
    private String nickname;
    private String email;

    public AdministratorContactVO() {
    }

    public AdministratorContactVO(String nickname, String email) {
        this.nickname = nickname;
        this.email = email;
    }
}
