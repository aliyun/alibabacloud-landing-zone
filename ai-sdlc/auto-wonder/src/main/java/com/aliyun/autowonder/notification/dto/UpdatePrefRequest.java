package com.aliyun.autowonder.notification.dto;

import lombok.Getter;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
public class UpdatePrefRequest {
    private List<PrefItem> items;

    @Getter
    @Setter
    public static class PrefItem {
        private String type;
        private boolean inApp;
        private boolean dingtalk;
    }
}
