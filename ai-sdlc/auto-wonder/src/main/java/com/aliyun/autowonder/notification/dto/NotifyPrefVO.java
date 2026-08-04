package com.aliyun.autowonder.notification.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class NotifyPrefVO {
    private String type;
    private boolean inApp;
    private boolean dingtalk;
}
