package com.aliyun.autowonder.user.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UpsertUserSettingRequest {
    /** 配置值的 JSON 文本，例如 {@code "shift-enter"} 或 {@code {"rows":6}}。 */
    private String valueJson;
}
