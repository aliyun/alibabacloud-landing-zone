package com.aliyun.autowonder.user.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class UserSettingVO {
    private String key;
    /** 配置值的 JSON 文本；从未设置过时为 null。 */
    private String valueJson;
}
