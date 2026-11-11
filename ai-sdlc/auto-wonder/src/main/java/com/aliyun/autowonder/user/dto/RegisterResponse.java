package com.aliyun.autowonder.user.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

/** 注册成功的响应：保留原用户字段，待审时附带审核标记与可用平台管理员联系方式。 */
@Getter
@Setter
public class RegisterResponse {
    private Long id;
    private String username;
    private String nickname;
    private String email;
    /** true 表示账号进入待审核，前端应提示联系管理员。 */
    private boolean reviewRequired;
    /** 仅待审成功响应提供；其余情况为 null。 */
    private List<AdministratorContactVO> administratorContacts;

    public static RegisterResponse from(UserVO user) {
        RegisterResponse response = new RegisterResponse();
        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setNickname(user.getNickname());
        response.setEmail(user.getEmail());
        return response;
    }
}
