package com.aliyun.autowonder.user.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RefreshRequest {
    private String refreshToken;
    private Long workspaceId;
    /**
     * 本次续期是否由用户前台操作触发。缺省（含旧客户端）按后台处理，只换发令牌不顺延会话。
     */
    private boolean userActivity;
}
