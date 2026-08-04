package com.aliyun.autowonder.setting.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SettingVO {
    private Long id;
    private String group;
    private String key;
    private String valueJson;
    private boolean secret;
}
