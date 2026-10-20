package com.aliyun.autowonder.user.dto;

import lombok.Getter;
import lombok.Setter;

/** 管理端调整用户在某空间的权限档位（三档：只读 / 读写 / 管理）。 */
@Getter
@Setter
public class UserWorkspaceLevelRequest {
    private String accessLevel;
}
