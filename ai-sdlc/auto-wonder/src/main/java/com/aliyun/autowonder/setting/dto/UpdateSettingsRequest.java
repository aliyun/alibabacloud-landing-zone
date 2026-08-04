package com.aliyun.autowonder.setting.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class UpdateSettingsRequest {
    private List<SettingItem> items;

    @Getter
    @Setter
    public static class SettingItem {
        private String key;
        private String valueJson;
        private boolean secret;
    }
}
